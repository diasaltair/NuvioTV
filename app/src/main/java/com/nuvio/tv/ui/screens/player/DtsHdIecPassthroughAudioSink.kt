package com.nuvio.tv.ui.screens.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Routes DTS-HD (DTS-HD MA / DTS-HD HRA / DTS:X) access units to the HDMI sink as
 * IEC 61937-5 high-bitrate bursts written to an [AudioTrack] opened with
 * [AudioFormat.ENCODING_IEC61937] at 192 kHz / 8 channels.
 *
 * Why: on Fire OS (MediaTek) the platform SPDIF encoder used by AudioFlinger for
 * [AudioFormat.ENCODING_DTS_HD] only recognises the DTS core frame and produces an invalid
 * burst on the 192 kHz link, so the receiver mutes. Framing the burst in-app and handing the
 * HAL a ready IEC61937 stream (what the HAL profile `hdmi_passthrough_direct` expects) plays
 * correctly. TrueHD and (E-)AC-3 stay on the native encodings, which the HAL accepts, so
 * every other format is forwarded untouched to the wrapped [AudioSink].
 *
 * Tunneling: the HAL refuses `AUDIO_OUTPUT_FLAG_HW_AV_SYNC` for IEC61937 streams, so this
 * path never creates a tunneled track. [PlaybackSpeedAwareAudioRenderer] reports
 * `TUNNELING_NOT_SUPPORTED` for formats this sink handles, which makes the track selector
 * fall back to non-tunneled playback for those titles only.
 *
 * Burst layout follows IEC 61937-5 as implemented by FFmpeg `libavformat/spdifenc.c`
 * (`spdif_header_dts4`, `dtshd_rate = 768000`).
 */
internal class DtsHdIecPassthroughAudioSink(
    sink: AudioSink
) : ForwardingAudioSink(sink) {

    private var listener: AudioSink.Listener? = null
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var audioAttributes: androidx.media3.common.AudioAttributes =
        androidx.media3.common.AudioAttributes.DEFAULT

    /** True while the IEC path owns playback for the configured format. */
    @Volatile
    private var iecActive: Boolean = false
    private var inputFormat: Format? = null

    private var audioTrack: AudioTrack? = null
    private var playing: Boolean = false
    private var handledEndOfStream: Boolean = false

    private var startMediaTimeUs: Long = C.TIME_UNSET
    private var writtenFrames: Long = 0
    private var writtenBursts: Long = 0

    // Pending burst not fully accepted by a non-blocking write.
    private var pendingBurst: ByteBuffer? = null

    private val burst = ByteBuffer.allocateDirect(MAX_BURST_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    private val timestamp = AudioTimestamp()
    private var lastTimestampPollMs: Long = 0
    private var timestampValid: Boolean = false
    private var lastRawHeadPosition: Long = 0
    private var headPositionWraps: Long = 0

    private var lastUnderrunCount: Int = 0
    private var lastFeedMs: Long = 0

    fun isIecPassthroughFormat(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        // DTS Express (LBR) has no core frame and needs a different burst; DTS core goes native.
        return mime == MimeTypes.AUDIO_DTS_HD
    }

    // ── Format support ──

    override fun getFormatSupport(format: Format): Int {
        if (isIecPassthroughFormat(format)) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean {
        if (isIecPassthroughFormat(format)) return true
        return super.supportsFormat(format)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (isIecPassthroughFormat(format)) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        return super.getFormatOffloadSupport(format)
    }

    // ── Lifecycle ──

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        super.setAudioSessionId(audioSessionId)
    }

    override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) {
        this.audioAttributes = audioAttributes
        super.setAudioAttributes(audioAttributes)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (isIecPassthroughFormat(inputFormat)) {
            if (iecActive) {
                // Same path, new format: drop the current track so the next buffer re-opens it.
                releaseTrack()
            } else {
                // Leaving the wrapped sink: make sure it is not holding a track.
                super.flush()
                iecActive = true
            }
            this.inputFormat = inputFormat
            resetState()
            Log.i(TAG, "configure: IEC61937 DTS-HD path, mime=${inputFormat.sampleMimeType} " +
                "sampleRate=${inputFormat.sampleRate} channels=${inputFormat.channelCount}")
            return
        }
        if (iecActive) {
            releaseTrack()
            resetState()
            iecActive = false
            this.inputFormat = null
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun play() {
        if (!iecActive) {
            super.play()
            return
        }
        playing = true
        audioTrack?.let { track ->
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
            }
        }
    }

    override fun pause() {
        if (!iecActive) {
            super.pause()
            return
        }
        playing = false
        audioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
        }
    }

    override fun flush() {
        if (!iecActive) {
            super.flush()
            return
        }
        // Like DefaultAudioSink: a flush releases the track; the next buffer re-creates it.
        releaseTrack()
        resetState()
    }

    override fun reset() {
        if (iecActive) {
            releaseTrack()
            resetState()
            iecActive = false
            inputFormat = null
        }
        super.reset()
    }

    override fun release() {
        releaseTrack()
        resetState()
        iecActive = false
        super.release()
    }

    override fun handleDiscontinuity() {
        if (!iecActive) {
            super.handleDiscontinuity()
        }
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return if (iecActive) PlaybackParameters.DEFAULT else super.getPlaybackParameters()
    }

    override fun setVolume(volume: Float) {
        // Bitstream: volume is applied by the receiver.
        if (!iecActive) super.setVolume(volume)
    }

    override fun getAudioTrackBufferSizeUs(): Long {
        if (!iecActive) return super.getAudioTrackBufferSizeUs()
        return BUFFER_BURSTS * BURST_DURATION_US
    }

    // ── Data path ──

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!iecActive) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        val track = audioTrack ?: createTrack() ?: return false

        if (!drainPending(track)) {
            return false
        }

        if (startMediaTimeUs == C.TIME_UNSET) {
            startMediaTimeUs = presentationTimeUs
        }

        // One buffer normally carries one DTS frame (core + HD substream). If the extractor
        // batched several, split on the core sync word.
        while (buffer.hasRemaining()) {
            val frameStart = buffer.position()
            val frameEnd = findNextCoreSync(buffer, frameStart + 4)
            val frameLen = frameEnd - frameStart
            if (!buildBurst(buffer, frameStart, frameLen)) {
                // Unparseable frame: drop it rather than feed garbage to the receiver.
                buffer.position(frameEnd)
                continue
            }
            buffer.position(frameEnd)
            burst.flip()
            val fullyWritten = writeBurst(track, burst)
            if (!fullyWritten) {
                // Keep the remainder; the renderer will retry with the same buffer only if we
                // did not consume it, so stash a copy and report the buffer as consumed.
                val rest = ByteBuffer.allocateDirect(burst.remaining()).order(ByteOrder.LITTLE_ENDIAN)
                rest.put(burst).flip()
                pendingBurst = rest
                // Consume the rest of the input so the renderer does not re-submit it.
                buffer.position(buffer.limit())
                return true
            }
        }
        return true
    }

    override fun playToEndOfStream() {
        if (!iecActive) {
            super.playToEndOfStream()
            return
        }
        val track = audioTrack
        if (track != null && drainPending(track)) {
            handledEndOfStream = true
        } else if (track == null) {
            handledEndOfStream = true
        }
    }

    override fun isEnded(): Boolean {
        if (!iecActive) return super.isEnded()
        return handledEndOfStream && !hasPendingData()
    }

    override fun hasPendingData(): Boolean {
        if (!iecActive) return super.hasPendingData()
        val track = audioTrack ?: return false
        if (pendingBurst != null) return true
        return playedFrames(track) < writtenFrames
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!iecActive) return super.getCurrentPositionUs(sourceEnded)
        val track = audioTrack ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (startMediaTimeUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        val played = playedFrames(track)
        return startMediaTimeUs + played * C.MICROS_PER_SECOND / IEC_SAMPLE_RATE
    }

    // ── AudioTrack ──

    private fun createTrack(): AudioTrack? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_IEC61937)
            .setSampleRate(IEC_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val bufferBytes = (BUFFER_BURSTS * BURST_BYTES).toInt()
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) setSessionId(audioSessionId)
                }
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack(IEC61937 192k 8ch) create failed", e)
            listener?.onAudioSinkError(
                AudioSink.InitializationException(
                    AudioTrack.ERROR, IEC_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
                    bufferBytes, inputFormat ?: Format.Builder().build(), false, e
                )
            )
            return null
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack(IEC61937) state=${track.state}")
            track.release()
            return null
        }
        audioTrack = track
        lastUnderrunCount = 0
        lastFeedMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "AudioTrack IEC61937 ${IEC_SAMPLE_RATE}Hz 8ch buffer=${bufferBytes}B (${BUFFER_BURSTS} bursts)")
        if (playing) track.play()
        return track
    }

    private fun releaseTrack() {
        val track = audioTrack ?: return
        audioTrack = null
        try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.pause()
            track.flush()
        } catch (_: Exception) {
        }
        track.release()
    }

    private fun resetState() {
        startMediaTimeUs = C.TIME_UNSET
        writtenFrames = 0
        writtenBursts = 0
        pendingBurst = null
        handledEndOfStream = false
        timestampValid = false
        lastTimestampPollMs = 0
        lastRawHeadPosition = 0
        headPositionWraps = 0
        burst.clear()
    }

    /** Writes what the track accepts; returns true when the whole [data] was consumed. */
    private fun writeBurst(track: AudioTrack, data: ByteBuffer): Boolean {
        val before = data.remaining()
        val written = track.write(data, before, AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) {
            Log.w(TAG, "AudioTrack.write error $written")
            listener?.onAudioSinkError(
                AudioSink.WriteException(written, inputFormat ?: Format.Builder().build(), false)
            )
            return false
        }
        writtenFrames += written / BYTES_PER_FRAME
        checkUnderrun(track)
        lastFeedMs = SystemClock.elapsedRealtime()
        return written == before
    }

    private fun drainPending(track: AudioTrack): Boolean {
        val rest = pendingBurst ?: return true
        if (writeBurst(track, rest)) {
            pendingBurst = null
            return true
        }
        return false
    }

    private fun checkUnderrun(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val count = track.underrunCount
        if (count > lastUnderrunCount) {
            val elapsed = SystemClock.elapsedRealtime() - lastFeedMs
            Log.w(TAG, "underrun count=$count (+${count - lastUnderrunCount}) sinceLastFeed=${elapsed}ms")
            listener?.onUnderrun(
                (BUFFER_BURSTS * BURST_BYTES).toInt(),
                BUFFER_BURSTS * BURST_DURATION_US / 1000,
                elapsed
            )
            lastUnderrunCount = count
        }
    }

    private fun playedFrames(track: AudioTrack): Long {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTimestampPollMs >= TIMESTAMP_POLL_MS) {
            lastTimestampPollMs = now
            timestampValid = try {
                track.getTimestamp(timestamp)
            } catch (_: Exception) {
                false
            }
        }
        if (timestampValid && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            val elapsedUs = (System.nanoTime() - timestamp.nanoTime) / 1000
            val extrapolated = timestamp.framePosition + elapsedUs * IEC_SAMPLE_RATE / C.MICROS_PER_SECOND
            return extrapolated.coerceIn(0, writtenFrames)
        }
        val raw = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (raw < lastRawHeadPosition) headPositionWraps++
        lastRawHeadPosition = raw
        return ((headPositionWraps shl 32) + raw).coerceAtMost(writtenFrames)
    }

    // ── IEC 61937-5 framing ──

    /**
     * Fills [burst] with one IEC 61937-5 DTS-HD data burst for the DTS frame at
     * [offset]..[offset]+[length] of [src]. Returns false when the frame cannot be parsed.
     */
    private fun buildBurst(src: ByteBuffer, offset: Int, length: Int): Boolean {
        if (length < 10) return false
        val sync = readBE32(src, offset)
        if (sync != DCA_SYNCWORD_CORE_BE) {
            if (writtenBursts == 0L) {
                // Stray HD-only frame at stream start (no core): skip, like FFmpeg.
                Log.w(TAG, "skip DTS frame without core sync (0x${Integer.toHexString(sync)})")
            }
            return false
        }
        // Core header (IEC 61937-5 / FFmpeg spdif_header_dts): blocks, core size, sample rate.
        val blocks = ((readBE16(src, offset + 4) shr 2) and 0x7f) + 1
        val coreSampleRate = DCA_SAMPLE_RATES[(src.get(offset + 8).toInt() shr 2) and 0x0f]
        if (coreSampleRate == 0) {
            Log.w(TAG, "unsupported DTS core sample rate index")
            return false
        }
        val period = IEC_HBR_RATE.toLong() * (blocks shl 5) / coreSampleRate
        val subtype = when (period) {
            512L -> 0; 1024L -> 1; 2048L -> 2; 4096L -> 3; 8192L -> 4; 16384L -> 5
            else -> {
                Log.w(TAG, "unsupported DTS-HD burst period $period (blocks=$blocks rate=$coreSampleRate)")
                return false
            }
        }
        val pktOffset = (period * 4).toInt()
        val outBytes = DTSHD_START_CODE.size + 2 + length
        if (outBytes > pktOffset - BURST_HEADER_SIZE) {
            Log.w(TAG, "DTS-HD frame too large for burst: $outBytes > ${pktOffset - BURST_HEADER_SIZE}")
            return false
        }
        if (pktOffset > MAX_BURST_BYTES) return false

        // Pd: length in bytes, aligned so (length_code & 0xf) == 0x8 (FFmpeg note: needed by some receivers).
        val lengthCode = ((outBytes + 0x8 + 0xf) and 0xf.inv()) - 0x8
        val dataType = IEC61937_DTSHD or (subtype shl 8)

        burst.clear()
        burst.putShort(SYNCWORD1.toShort())
        burst.putShort(SYNCWORD2.toShort())
        burst.putShort(dataType.toShort())
        burst.putShort(lengthCode.toShort())
        // Payload bytes are emitted as 16-bit little-endian words of the big-endian stream,
        // i.e. every byte pair is swapped (FFmpeg ff_spdif_bswap_buf16).
        putPayloadSwapped(src, offset, length)
        val padding = pktOffset - BURST_HEADER_SIZE - outBytes
        repeat(padding) { burst.put(0) }
        writtenBursts++
        return true
    }

    /**
     * Emits start code + 2-byte big-endian frame length + frame bytes, byte-swapped per
     * 16-bit word (FFmpeg ff_spdif_bswap_buf16): the stream is big-endian, the track is LE.
     */
    private fun putPayloadSwapped(src: ByteBuffer, offset: Int, length: Int) {
        val head = DTSHD_START_CODE.size
        val total = head + 2 + length
        fun byteAt(index: Int): Byte = when {
            index < head -> DTSHD_START_CODE[index]
            index == head -> ((length shr 8) and 0xff).toByte()
            index == head + 1 -> (length and 0xff).toByte()
            else -> src.get(offset + (index - head - 2))
        }
        var i = 0
        while (i + 1 < total) {
            burst.put(byteAt(i + 1))
            burst.put(byteAt(i))
            i += 2
        }
        if (i < total) {
            // A final lone byte is MSB-aligned.
            burst.put(0)
            burst.put(byteAt(i))
        }
    }

    private fun findNextCoreSync(buffer: ByteBuffer, from: Int): Int {
        val limit = buffer.limit()
        var i = from
        while (i + 4 <= limit) {
            if (readBE32(buffer, i) == DCA_SYNCWORD_CORE_BE) return i
            i++
        }
        return limit
    }

    private fun readBE16(b: ByteBuffer, i: Int): Int =
        ((b.get(i).toInt() and 0xff) shl 8) or (b.get(i + 1).toInt() and 0xff)

    private fun readBE32(b: ByteBuffer, i: Int): Int =
        (readBE16(b, i) shl 16) or readBE16(b, i + 2)

    companion object {
        private const val TAG = "DtsHdIecSink"

        private const val IEC_SAMPLE_RATE = 192000
        /** IEC 60958 rate the burst period is computed against (4 sub-frame pairs × 192 kHz). */
        private const val IEC_HBR_RATE = 768000
        private const val BYTES_PER_FRAME = 8 * 2

        private const val SYNCWORD1 = 0xF872
        private const val SYNCWORD2 = 0x4E1F
        private const val IEC61937_DTSHD = 0x11
        private const val BURST_HEADER_SIZE = 8
        private const val DCA_SYNCWORD_CORE_BE = 0x7FFE8001
        private val DTSHD_START_CODE = byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0, 0xfe.toByte(), 0xfe.toByte())
        private val DCA_SAMPLE_RATES = intArrayOf(
            0, 8000, 16000, 32000, 0, 0, 11025, 22050, 44100, 0, 0, 12000, 24000, 48000, 0, 0
        )

        /** Largest supported burst: period 16384 × 4 bytes. */
        private const val MAX_BURST_BYTES = 16384 * 4
        /** Typical burst for 48 kHz / 512-sample frames: 8192 × 4 = 32768 bytes = 10.67 ms. */
        private const val BURST_BYTES = 32768L
        private const val BURST_DURATION_US = 512L * C.MICROS_PER_SECOND / 48000L
        private const val BUFFER_BURSTS = 16L

        private const val TIMESTAMP_POLL_MS = 500L
    }
}
