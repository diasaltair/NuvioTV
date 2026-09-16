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
    sink: AudioSink,
    /** User opt-in for the 7.1 (HBR) tunneled shape; see PlayerSettings.iecTunnelSurround. */
    private val surroundTunnelAllowed: Boolean = false
) : ForwardingAudioSink(sink) {

    private var listener: AudioSink.Listener? = null
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var audioAttributes: androidx.media3.common.AudioAttributes =
        androidx.media3.common.AudioAttributes.DEFAULT

    /** True while the IEC path owns playback for the configured format. */
    @Volatile
    private var iecActive: Boolean = false
    private var inputFormat: Format? = null
    private var iecChannels: Int = 8
    private var tunnelingRequested: Boolean = false
    /** The IEC track was opened with FLAG_HW_AV_SYNC on the tunneling session. */
    private var iecTunnelActive: Boolean = false
    /** Presentation time of the burst being built, for timestamped tunnel writes. */
    private var burstPtsUs: Long = C.TIME_UNSET
    private var lastFrameDurationUs: Long = 0

    private var audioTrack: AudioTrack? = null
    private var playing: Boolean = false
    private var handledEndOfStream: Boolean = false

    private var startMediaTimeUs: Long = C.TIME_UNSET
    /** Track frame position that corresponds to [startMediaTimeUs]. */
    private var anchorFrames: Long = 0
    private var writtenFrames: Long = 0
    private var writtenBursts: Long = 0
    private var stalledWrites: Int = 0

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

    // Stall detection (same idea as media3's AudioTrackPositionTracker.isStalled): the head
    // position stops moving while data is pending and the track is playing.
    private var lastHeadFrames: Long = -1
    private var lastHeadMoveMs: Long = 0
    private var trackRestarts: Int = 0

    // Wrapped-path health: a native passthrough track this HAL accepted but never plays.
    private var wrappedWindowStartMs: Long = 0
    private var wrappedWindowStartPosUs: Long = C.TIME_UNSET
    private var wrappedFedBuffers: Int = 0
    private var wrappedDeadReported: Boolean = false

    /**
     * True for any DTS track that may end up on the IEC path. Track selection sees the
     * extractor's initial label (MKV tags DTS-HD tracks as plain `audio/vnd.dts` until the
     * first frame is parsed), so the tunneling decision has to cover the whole family.
     */
    fun mayUseIecPassthrough(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        // Only the core/HD pair: DTS Express (no core frame) and DTS:X Profile 2 never take this path.
        if (mime != MimeTypes.AUDIO_DTS && mime != MimeTypes.AUDIO_DTS_HD) return false
        if (iecFailedInProcess) return false
        return iecProbeUsable()
    }

    /**
     * True when this HAL accepted an IEC61937 192 kHz track opened with FLAG_HW_AV_SYNC, so a
     * DTS-HD title can stay tunneled with the IEC path as the tunnel clock source.
     */
    fun canTunnelIecPassthrough(): Boolean {
        if (iecTunnelFailedInProcess) return false
        return iecTunnelMode(surroundTunnelAllowed) != TunnelMode.NONE
    }

    /**
     * True when [format] may still be played on a tunneled track: IEC-owned formats need a
     * HAL that clocks an IEC61937 stream; anything else needs its native tunneled rung alive.
     */
    fun tunnelingCapability(format: Format): Boolean {
        if (mayUseIecPassthrough(format)) return canTunnelIecPassthrough()
        return !PassthroughLadder.isDead(format.sampleMimeType, PassthroughLadder.Rung.NATIVE_TUNNEL)
    }

    /** Native (wrapped sink) passthrough is off the table for this encoding in this process. */
    private fun nativeDirectDead(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (mime == MimeTypes.AUDIO_RAW) return false
        return PassthroughLadder.isDead(mime, PassthroughLadder.Rung.NATIVE_DIRECT)
    }

    fun isIecPassthroughFormat(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        // DTS Express (LBR) has no core frame and needs a different burst; DTS core goes native.
        if (mime != MimeTypes.AUDIO_DTS_HD) return false
        if (iecFailedInProcess) {
            Log.i(TAG, "isIecPassthroughFormat=false: IEC failed earlier in this process")
            return false
        }
        return iecProbeUsable()
    }

    // ── Format support ──

    override fun getFormatSupport(format: Format): Int {
        if (isIecPassthroughFormat(format)) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        if (nativeDirectDead(format)) {
            Log.i(TAG, "getFormatSupport: ${format.sampleMimeType} native passthrough dead, decode instead")
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean {
        if (isIecPassthroughFormat(format)) return true
        if (nativeDirectDead(format)) return false
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
        // Tunneling is only requested here when the tunnel probe passed (the renderer reports
        // TUNNELING_NOT_SUPPORTED otherwise). If it is requested anyway and the HAL cannot
        // clock an IEC track, hand the format to the wrapped sink.
        val tunnelMode = if (tunnelingRequested) {
            if (canTunnelIecPassthrough()) iecTunnelMode(surroundTunnelAllowed) else TunnelMode.NONE
        } else {
            TunnelMode.NONE
        }
        if (isIecPassthroughFormat(inputFormat) && (!tunnelingRequested || tunnelMode != TunnelMode.NONE)) {
            iecTunnelActive = tunnelMode != TunnelMode.NONE
            // A HW_AV_SYNC IEC stream on this HAL may only be stereo-shaped (4 bytes per
            // 192 kHz frame): the burst period then follows IEC_SAMPLE_RATE and the largest
            // frame that fits is 8 KB. Bigger frames abandon the tunnel; nothing is stripped.
            iecChannels = when {
                inputFormat.channelCount in 1..2 -> 2
                tunnelMode == TunnelMode.STEREO -> 2
                else -> 8
            }
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
                "sampleRate=${inputFormat.sampleRate} channels=${inputFormat.channelCount} " +
                "tunnel=$tunnelMode iecChannels=$iecChannels session=$audioSessionId")
            return
        }
        if (inputFormat.sampleMimeType == MimeTypes.AUDIO_DTS_HD) {
            Log.i(TAG, "configure: DTS-HD handed to wrapped sink (tunnelingRequested=$tunnelingRequested " +
                "failed=$iecFailedInProcess probe=$iecProbeResult)")
        }
        if (iecActive) {
            releaseTrack()
            resetState()
            iecActive = false
            iecTunnelActive = false
            this.inputFormat = null
        }
        this.inputFormat = inputFormat
        resetWrappedWindow()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun play() {
        playing = true
        if (!iecActive) {
            super.play()
            return
        }
        audioTrack?.let { track ->
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
            }
        }
    }

    override fun pause() {
        playing = false
        resetWrappedWindow()
        if (!iecActive) {
            super.pause()
            return
        }
        audioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
        }
    }

    override fun flush() {
        resetWrappedWindow()
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
            iecTunnelActive = false
        }
        inputFormat = null
        resetWrappedWindow()
        super.reset()
    }

    override fun release() {
        releaseTrack()
        resetState()
        iecActive = false
        iecTunnelActive = false
        super.release()
    }

    override fun handleDiscontinuity() {
        if (!iecActive) {
            super.handleDiscontinuity()
            return
        }
        // Re-anchor: the next buffer's PTS maps to the frames written so far.
        startMediaTimeUs = C.TIME_UNSET
        anchorFrames = writtenFrames
    }

    override fun enableTunnelingV21() {
        Log.i(TAG, "enableTunnelingV21 (iecActive=$iecActive)")
        tunnelingRequested = true
        super.enableTunnelingV21()
    }

    override fun disableTunneling() {
        tunnelingRequested = false
        super.disableTunneling()
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
        val track = audioTrack ?: return BUFFER_TARGET_MS * 1000
        return track.bufferSizeInFrames.toLong() * C.MICROS_PER_SECOND / IEC_SAMPLE_RATE
    }

    // ── Data path ──

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!iecActive) {
            return handleWrappedBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        val track = audioTrack ?: createTrack() ?: run {
            if (iecTunnelActive) abandonTunnel("AudioTrack init failed")
            fallbackToWrappedSink("AudioTrack init failed")
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        if (isTrackStalled(track)) {
            if (trackRestarts >= MAX_TRACK_RESTARTS) {
                if (iecTunnelActive) abandonTunnel("track stalled ${trackRestarts + 1} times")
                fallbackToWrappedSink("track stalled ${trackRestarts + 1} times")
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            restartTrack("head position frozen for ${STALL_MS} ms with data pending")
            return false
        }

        if (!drainPending(track)) {
            if (stalledWrites >= MAX_WRITE_STALLS) {
                if (iecTunnelActive) abandonTunnel("$stalledWrites consecutive stalled writes")
                fallbackToWrappedSink("$stalledWrites consecutive stalled writes")
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            return false
        }

        if (startMediaTimeUs == C.TIME_UNSET) {
            startMediaTimeUs = presentationTimeUs
            anchorFrames = writtenFrames
        }

        // One buffer normally carries one DTS frame (core + HD substream). If the extractor
        // batched several, split on the core sync word.
        burstPtsUs = presentationTimeUs
        while (buffer.hasRemaining()) {
            val frameStart = buffer.position()
            val frameEnd = findNextCoreSync(buffer, frameStart + 4)
            val frameLen = frameEnd - frameStart
            if (!buildBurst(buffer, frameStart, frameLen)) {
                if (iecTunnelActive && lastFrameDurationUs < 0) {
                    // Frame larger than the tunnel-shaped burst: leave the tunnel, keep the audio.
                    abandonTunnel("DTS-HD frame does not fit the ${iecChannels}ch IEC burst")
                }
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
            burstPtsUs += lastFrameDurationUs
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
        // The renderer stops calling handleBuffer after playToEndOfStream; keep draining here.
        audioTrack?.let { if (pendingBurst != null) drainPending(it) }
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
        val played = (playedFrames(track) - anchorFrames).coerceAtLeast(0)
        return startMediaTimeUs + played * C.MICROS_PER_SECOND / IEC_SAMPLE_RATE
    }

    // ── Wrapped (native passthrough) path health ──

    private fun wrappedPassthroughFormat(): Format? {
        val format = inputFormat ?: return null
        val mime = format.sampleMimeType ?: return null
        if (mime == MimeTypes.AUDIO_RAW) return null
        return format
    }

    private fun resetWrappedWindow() {
        wrappedWindowStartMs = 0
        wrappedWindowStartPosUs = C.TIME_UNSET
        wrappedFedBuffers = 0
        wrappedDeadReported = false
    }

    /**
     * Feeds the wrapped sink and watches whether what it accepts ever plays. A HAL can accept
     * a passthrough open and then reopen the output as PCM or never start its clock; media3
     * then loops on "Resetting stalled audio track" forever. When [WRAPPED_DEAD_MS] pass with
     * at least [WRAPPED_MIN_FED] buffers accepted and the position advanced less than
     * [WRAPPED_ADVANCE_OK_US], the current rung (tunneled or direct) is marked dead and a
     * recoverable error makes the player reselect tracks on the next rung.
     */
    private fun handleWrappedBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val format = wrappedPassthroughFormat()
        if (format != null && playing) {
            val now = SystemClock.elapsedRealtime()
            val pos = super.getCurrentPositionUs(false)
            if (wrappedWindowStartMs == 0L) {
                wrappedWindowStartMs = now
                wrappedWindowStartPosUs = pos
                wrappedFedBuffers = 0
            } else if (wrappedWindowStartPosUs == AudioSink.CURRENT_POSITION_NOT_SET && pos != AudioSink.CURRENT_POSITION_NOT_SET) {
                wrappedWindowStartPosUs = pos
            } else if (pos != AudioSink.CURRENT_POSITION_NOT_SET &&
                wrappedWindowStartPosUs != AudioSink.CURRENT_POSITION_NOT_SET &&
                pos - wrappedWindowStartPosUs >= WRAPPED_ADVANCE_OK_US
            ) {
                wrappedWindowStartMs = now
                wrappedWindowStartPosUs = pos
                wrappedFedBuffers = 0
            } else if (now - wrappedWindowStartMs >= WRAPPED_DEAD_MS && wrappedFedBuffers >= WRAPPED_MIN_FED) {
                giveUpWrappedRung(
                    format,
                    "output silent: $wrappedFedBuffers buffers accepted, position moved " +
                        "${if (pos == AudioSink.CURRENT_POSITION_NOT_SET || wrappedWindowStartPosUs == AudioSink.CURRENT_POSITION_NOT_SET) "unset" else "${(pos - wrappedWindowStartPosUs) / 1000} ms"} " +
                        "in ${now - wrappedWindowStartMs} ms"
                )
            }
        }
        val accepted = try {
            super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (e: AudioSink.InitializationException) {
            if (format == null) throw e
            giveUpWrappedRung(format, "AudioTrack init failed: ${e.message}")
            throw e
        } catch (e: AudioSink.WriteException) {
            if (format == null) throw e
            giveUpWrappedRung(format, "AudioTrack write failed: ${e.message}")
            throw e
        }
        if (accepted) wrappedFedBuffers++
        return accepted
    }

    /** Marks the current native rung dead and raises a recoverable error so tracks are reselected. */
    private fun giveUpWrappedRung(format: Format, reason: String) {
        if (wrappedDeadReported) return
        wrappedDeadReported = true
        val rung = if (tunnelingRequested) PassthroughLadder.Rung.NATIVE_TUNNEL else PassthroughLadder.Rung.NATIVE_DIRECT
        PassthroughLadder.markDead(format.sampleMimeType, rung, reason)
        Log.w(TAG, "wrapped sink ${format.sampleMimeType} $rung gave up: $reason; dead=${PassthroughLadder.describe(format.sampleMimeType)}")
        try {
            super.flush()
        } catch (_: Exception) {
        }
        throw AudioSink.WriteException(AudioTrack.ERROR, format, true)
    }

    // ── AudioTrack ──

    private fun createTrack(): AudioTrack? {
        val channelMask = channelMaskFor(iecChannels)
        val bytesPerSecond = IEC_SAMPLE_RATE * iecChannels * 2
        val preferredBytes = (bytesPerSecond.toLong() * BUFFER_TARGET_MS / 1000).toInt()
        val minBytes = AudioTrack.getMinBufferSize(IEC_SAMPLE_RATE, channelMask, AudioFormat.ENCODING_IEC61937)
            .takeIf { it > 0 } ?: (bytesPerSecond / 10)
        // Feeder stalls of a few hundred ms were measured on this path; ask for ~1 s and fall
        // back to the HAL minimum if the larger request is refused.
        for (bufferBytes in listOf(preferredBytes, minBytes * 4, minBytes)) {
            val track = openTrack(channelMask, bufferBytes) ?: continue
            audioTrack = track
            lastUnderrunCount = 0
            stalledWrites = 0
            lastFeedMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "AudioTrack IEC61937 ${IEC_SAMPLE_RATE}Hz ${iecChannels}ch buffer=${bufferBytes}B " +
                "hwAvSync=$iecTunnelActive session=${track.audioSessionId}")
            if (playing) track.play()
            return track
        }
        Log.e(TAG, "AudioTrack(IEC61937 ${IEC_SAMPLE_RATE}Hz ${iecChannels}ch) create failed")
        listener?.onAudioSinkError(
            AudioSink.InitializationException(
                "AudioTrack IEC61937 ${IEC_SAMPLE_RATE}Hz ${iecChannels}ch init failed",
                AudioTrack.ERROR, inputFormat ?: Format.Builder().build(), false, null
            )
        )
        return null
    }

    private fun openTrack(channelMask: Int, bufferBytes: Int): AudioTrack? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_IEC61937)
            .setSampleRate(IEC_SAMPLE_RATE)
            .setChannelMask(channelMask)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .apply { if (iecTunnelActive) setFlags(AudioAttributes.FLAG_HW_AV_SYNC) }
            .build()
        if (iecTunnelActive && audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            Log.w(TAG, "tunneled IEC track without an audio session id: video will not lock to it")
        }
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
            Log.w(TAG, "AudioTrack(IEC61937, buffer=${bufferBytes}B, hwAvSync=$iecTunnelActive) create failed: $e")
            return null
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack(IEC61937, buffer=${bufferBytes}B, hwAvSync=$iecTunnelActive) state=${track.state}")
            track.release()
            return null
        }
        return track
    }

    /**
     * Leaves the tunnel for the rest of the process without giving up the IEC path: the
     * recoverable error makes the player reselect tracks, the renderer then reports
     * TUNNELING_NOT_SUPPORTED and the title continues non-tunneled on a plain IEC track.
     */
    private fun abandonTunnel(reason: String) {
        val format = inputFormat ?: Format.Builder().build()
        Log.w(TAG, "IEC tunnel disabled for this process: $reason")
        iecTunnelFailedInProcess = true
        releaseTrack()
        resetState()
        iecActive = false
        iecTunnelActive = false
        inputFormat = null
        throw AudioSink.WriteException(AudioTrack.ERROR, format, true)
    }

    /**
     * Hands the current format back to the wrapped sink and disables the IEC path for the
     * rest of the process. A ConfigurationException from the wrapped sink is a checked
     * exception media3 does not expect around handleBuffer, so it is rethrown as a recoverable
     * WriteException.
     */
    private fun fallbackToWrappedSink(reason: String) {
        val format = inputFormat
        Log.w(TAG, "IEC path disabled for this process: $reason")
        iecFailedInProcess = true
        releaseTrack()
        resetState()
        iecActive = false
        inputFormat = null
        if (format == null) return
        try {
            super.configure(format, 0, null)
            this.inputFormat = format
            resetWrappedWindow()
            if (playing) super.play()
        } catch (e: AudioSink.ConfigurationException) {
            throw AudioSink.WriteException(AudioTrack.ERROR, format, true)
        }
    }

    /**
     * True when the track is playing, holds unplayed data, and its head position has not
     * advanced for [STALL_MS]. On this HAL an underrun pauses the direct output and the
     * track never resumes on its own once the client buffer is full.
     */
    private fun isTrackStalled(track: AudioTrack): Boolean {
        if (!playing || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            lastHeadFrames = -1
            return false
        }
        val head = rawHeadFrames(track)
        val now = SystemClock.elapsedRealtime()
        if (head != lastHeadFrames) {
            lastHeadFrames = head
            lastHeadMoveMs = now
            return false
        }
        val pending = writtenFrames > head || pendingBurst != null
        return pending && now - lastHeadMoveMs >= STALL_MS
    }

    /** Drops the current track and its buffered audio; the next buffer re-opens and re-anchors. */
    private fun restartTrack(reason: String) {
        trackRestarts++
        Log.w(TAG, "restarting IEC track (#$trackRestarts): $reason")
        listener?.onPositionDiscontinuity()
        releaseTrack()
        val restarts = trackRestarts
        resetState()
        trackRestarts = restarts
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
        anchorFrames = 0
        writtenFrames = 0
        writtenBursts = 0
        stalledWrites = 0
        pendingBurst = null
        handledEndOfStream = false
        timestampValid = false
        lastTimestampPollMs = 0
        lastRawHeadPosition = 0
        headPositionWraps = 0
        lastHeadFrames = -1
        lastHeadMoveMs = 0
        trackRestarts = 0
        burstPtsUs = C.TIME_UNSET
        lastFrameDurationUs = 0
        burst.clear()
    }

    /** Writes what the track accepts; returns true when the whole [data] was consumed. */
    private fun writeBurst(track: AudioTrack, data: ByteBuffer): Boolean {
        val before = data.remaining()
        val written = if (iecTunnelActive && burstPtsUs != C.TIME_UNSET) {
            // HW_AV_SYNC: the framework prefixes each burst with an AV sync header carrying
            // this timestamp; a partial write continues the same header on the next call.
            track.write(data, before, AudioTrack.WRITE_NON_BLOCKING, burstPtsUs * 1000)
        } else {
            track.write(data, before, AudioTrack.WRITE_NON_BLOCKING)
        }
        if (written < 0) {
            Log.w(TAG, "AudioTrack.write error $written")
            // Treat a write error like a stall limit: the caller falls back to the wrapped sink.
            stalledWrites = MAX_WRITE_STALLS
            return false
        }
        if (written == 0) {
            // A paused track holds a full buffer by design; only count stalls while playing.
            if (playing && track.playState == AudioTrack.PLAYSTATE_PLAYING) stalledWrites++
            return false
        }
        stalledWrites = 0
        writtenFrames += written / (iecChannels * 2)
        if (trackRestarts > 0 && writtenFrames > RESTART_FORGET_FRAMES) trackRestarts = 0
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
            listener?.onUnderrun(track.bufferSizeInFrames * iecChannels * 2, BUFFER_TARGET_MS, elapsed)
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
        return rawHeadFrames(track).coerceAtMost(writtenFrames)
    }

    private fun rawHeadFrames(track: AudioTrack): Long {
        val raw = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (raw < lastRawHeadPosition) headPositionWraps++
        lastRawHeadPosition = raw
        return (headPositionWraps shl 32) + raw
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
        val iecRate = if (iecChannels > 2) IEC_HBR_RATE else IEC_SAMPLE_RATE
        val period = iecRate.toLong() * (blocks shl 5) / coreSampleRate
        val subtype = when (period) {
            512L -> 0; 1024L -> 1; 2048L -> 2; 4096L -> 3; 8192L -> 4; 16384L -> 5
            else -> {
                Log.w(TAG, "unsupported DTS-HD burst period $period (blocks=$blocks rate=$coreSampleRate)")
                return false
            }
        }
        val pktOffset = (period * 4).toInt()
        val outBytes = DTSHD_START_CODE.size + 2 + length
        lastFrameDurationUs = (blocks shl 5).toLong() * C.MICROS_PER_SECOND / coreSampleRate
        if (outBytes > pktOffset - BURST_HEADER_SIZE) {
            Log.w(TAG, "DTS-HD frame too large for burst: $outBytes > ${pktOffset - BURST_HEADER_SIZE}")
            lastFrameDurationUs = -1
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
        private const val BUFFER_TARGET_MS = 1000L
        /** Consecutive zero-byte non-blocking writes while playing before giving up on IEC. */
        private const val MAX_WRITE_STALLS = 1000
        /** Head position frozen this long with data pending = stalled track (media3 uses 5 s). */
        private const val STALL_MS = 5000L
        /** Stalls in a row (within RESTART_FORGET_FRAMES of audio) before giving up on IEC. */
        private const val MAX_TRACK_RESTARTS = 3
        /** After this much audio plays cleanly, earlier restarts are forgotten (30 s). */
        private const val RESTART_FORGET_FRAMES = 30L * IEC_SAMPLE_RATE

        private const val TIMESTAMP_POLL_MS = 500L
        /** Wrapped path: this long playing with data accepted and no position movement = dead output. */
        private const val WRAPPED_DEAD_MS = 5000L
        private const val WRAPPED_MIN_FED = 40
        private const val WRAPPED_ADVANCE_OK_US = 300_000L

        /** Set once an IEC track failed at runtime; the wrapped sink owns DTS-HD afterwards. */
        @Volatile
        private var iecFailedInProcess: Boolean = false

        /** null = not probed yet. Process-wide: the HAL answer does not change per player. */
        @Volatile
        private var iecProbeResult: Boolean? = null
        /** Set once a tunneled IEC track failed at runtime; DTS-HD then plays non-tunneled. */
        @Volatile
        private var iecTunnelFailedInProcess: Boolean = false
        @Volatile
        private var iecTunnelSurroundProbe: Boolean? = null
        @Volatile
        private var iecTunnelStereoProbe: Boolean? = null

        private fun probeTrack(mask: Int, hwAvSync: Boolean): Boolean {
            val min = AudioTrack.getMinBufferSize(IEC_SAMPLE_RATE, mask, AudioFormat.ENCODING_IEC61937)
            if (min <= 0) return false
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .apply { if (hwAvSync) setFlags(AudioAttributes.FLAG_HW_AV_SYNC) }
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_IEC61937)
                        .setSampleRate(IEC_SAMPLE_RATE)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(min)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val ok = track.state == AudioTrack.STATE_INITIALIZED
            track.release()
            return ok
        }

        /**
         * Which IEC61937 192 kHz shape this HAL opens with FLAG_HW_AV_SYNC. Each shape is probed
         * once per process. The 7.1 shape (full HBR bandwidth, what Kodi and the Amlogic/Tegra
         * HALs use) is only considered when the user allowed it: the MediaTek karat HAL logs
         * `set(), wrong channels 0x63f, when format is AUDIO_FORMAT_IEC61937`, reopens the
         * output as PCM and reports success, so the open probe cannot tell a real 7.1 tunnel
         * from white noise. The stereo shape is the one that HAL asks for
         * (`Hal|target ... channelMsk = 0x3|0x3`).
         */
        @Synchronized
        private fun iecTunnelMode(surroundAllowed: Boolean): TunnelMode {
            if (!iecProbeUsable()) return TunnelMode.NONE
            fun probe(mask: Int, cached: Boolean?, store: (Boolean) -> Unit, name: String): Boolean {
                cached?.let { return it }
                val ok = try {
                    probeTrack(mask, hwAvSync = true)
                } catch (e: Exception) {
                    Log.w(TAG, "IEC61937 HW_AV_SYNC $name probe threw: $e")
                    false
                }
                Log.i(TAG, "IEC61937 ${IEC_SAMPLE_RATE}Hz HW_AV_SYNC $name probe: ${if (ok) "opens" else "refused"}")
                store(ok)
                return ok
            }
            if (surroundAllowed &&
                probe(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, iecTunnelSurroundProbe, { iecTunnelSurroundProbe = it }, "7.1")
            ) {
                return TunnelMode.SURROUND
            }
            if (probe(AudioFormat.CHANNEL_OUT_STEREO, iecTunnelStereoProbe, { iecTunnelStereoProbe = it }, "stereo")) {
                return TunnelMode.STEREO
            }
            return TunnelMode.NONE
        }

        private fun channelMaskFor(channels: Int): Int =
            if (channels > 2) AudioFormat.CHANNEL_OUT_7POINT1_SURROUND else AudioFormat.CHANNEL_OUT_STEREO

        /**
         * One-shot check that an ENCODING_IEC61937 192 kHz track can be opened at all. Without
         * it a device with no IEC support would claim DTS-HD and then fail every createTrack.
         */
        @Synchronized
        private fun iecProbeUsable(): Boolean {
            iecProbeResult?.let { return it }
            val mask = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            val result = try {
                val min = AudioTrack.getMinBufferSize(IEC_SAMPLE_RATE, mask, AudioFormat.ENCODING_IEC61937)
                if (min <= 0) {
                    false
                } else {
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_IEC61937)
                                .setSampleRate(IEC_SAMPLE_RATE)
                                .setChannelMask(mask)
                                .build()
                        )
                        .setBufferSizeInBytes(min)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    val ok = track.state == AudioTrack.STATE_INITIALIZED
                    track.release()
                    ok
                }
            } catch (e: Exception) {
                false
            }
            Log.i(TAG, "IEC61937 ${IEC_SAMPLE_RATE}Hz probe: ${if (result) "usable" else "unavailable"}")
            iecProbeResult = result
            return result
        }
    }
}
