package com.nuvio.tv.core.player

import androidx.media3.common.C
import com.nuvio.tv.core.player.dvmkv.SubtitleTimingListener

/**
 * Accumulates the timing of every embedded subtitle sample the Matroska extractor reads,
 * for all text tracks, so external subtitles can be scored against the file's own cue
 * timeline. Samples arrive on the extractor loading thread: appends are lock-light and
 * allocation-free in steady state; [snapshot] copies the data for the matcher.
 */
class EmbeddedSubtitleTimingCollector(
    private val maxCuesPerTrack: Int = MAX_CUES_PER_TRACK
) : SubtitleTimingListener {

    class TrackTiming internal constructor(
        val trackNumber: Int,
        val codecId: String,
        val language: String?,
        val forced: Boolean,
        /** Sorted cue start times in ms. */
        val startsMs: LongArray,
        /** Cue end times in ms, aligned with [startsMs]; -1 when the duration is unknown. */
        val endsMs: LongArray,
        /** Lowest / highest start time seen for this track, in ms. */
        val observedMinMs: Long,
        val observedMaxMs: Long,
        /** Total samples received, including the ones dropped by the cap. */
        val receivedCount: Int
    ) {
        val cueCount: Int get() = startsMs.size
        val hasEndTimes: Boolean get() = codecId.startsWith("S_TEXT")
        val isBitmap: Boolean get() = !hasEndTimes
    }

    class Snapshot internal constructor(
        val tracks: List<TrackTiming>,
        val generation: Int
    ) {
        fun track(trackNumber: Int): TrackTiming? = tracks.firstOrNull { it.trackNumber == trackNumber }
    }

    private class MutableTrack(
        val trackNumber: Int,
        val codecId: String,
        val language: String?,
        val forced: Boolean,
        capacity: Int
    ) {
        var starts = LongArray(capacity)
        var ends = LongArray(capacity)
        var size = 0
        var received = 0
        var lastStartMs = Long.MIN_VALUE
        var minMs = Long.MAX_VALUE
        var maxMs = Long.MIN_VALUE
        var dirty = false
    }

    private val lock = Any()
    private val tracks = HashMap<Int, MutableTrack>()
    @Volatile private var generation = 0

    override fun onSubtitleTrack(trackNumber: Int, codecId: String, language: String?, forced: Boolean) {
        synchronized(lock) {
            if (tracks.containsKey(trackNumber)) return
            tracks[trackNumber] = MutableTrack(trackNumber, codecId, language, forced, INITIAL_CAPACITY)
            generation++
        }
    }

    override fun onSubtitleSample(trackNumber: Int, startUs: Long, durationUs: Long, sizeBytes: Int) {
        val startMs = startUs / 1000
        val endMs = if (durationUs == C.TIME_UNSET || durationUs <= 0) -1L else (startUs + durationUs) / 1000
        synchronized(lock) {
            val track = tracks[trackNumber] ?: return
            track.received++
            if (track.isPgs() && sizeBytes < PGS_MIN_PAYLOAD_BYTES) {
                // PGS end-of-display sets carry no picture: they are the previous cue's end, not a cue.
                return
            }
            if (startMs == track.lastStartMs) return // stacked ASS lines share a start; count once
            if (startMs < track.minMs) track.minMs = startMs
            if (startMs > track.maxMs) track.maxMs = startMs
            if (track.size >= maxCuesPerTrack) return
            if (track.size == track.starts.size) {
                val grown = minOf(track.starts.size * 2, maxCuesPerTrack)
                track.starts = track.starts.copyOf(grown)
                track.ends = track.ends.copyOf(grown)
            }
            track.starts[track.size] = startMs
            track.ends[track.size] = endMs
            track.size++
            track.lastStartMs = startMs
            track.dirty = true
            generation++
        }
    }

    private fun MutableTrack.isPgs(): Boolean = codecId == "S_HDMV/PGS"

    /** Current generation counter; changes whenever new samples arrive. */
    fun generation(): Int = generation

    fun snapshot(): Snapshot {
        synchronized(lock) {
            val list = tracks.values.map { t ->
                val starts = t.starts.copyOf(t.size)
                val ends = t.ends.copyOf(t.size)
                // Seeks make the extractor emit out of order; the matcher needs sorted starts.
                if (!isSorted(starts)) sortPairs(starts, ends)
                TrackTiming(
                    trackNumber = t.trackNumber,
                    codecId = t.codecId,
                    language = t.language,
                    forced = t.forced,
                    startsMs = starts,
                    endsMs = ends,
                    observedMinMs = if (t.size == 0) 0L else t.minMs,
                    observedMaxMs = if (t.size == 0) 0L else t.maxMs,
                    receivedCount = t.received
                )
            }
            return Snapshot(list, generation)
        }
    }

    fun reset() {
        synchronized(lock) {
            tracks.clear()
            generation++
        }
    }

    private fun isSorted(a: LongArray): Boolean {
        for (i in 1 until a.size) if (a[i] < a[i - 1]) return false
        return true
    }

    private fun sortPairs(starts: LongArray, ends: LongArray) {
        val idx = (starts.indices).sortedBy { starts[it] }
        val s = LongArray(starts.size) { starts[idx[it]] }
        val e = LongArray(ends.size) { ends[idx[it]] }
        s.copyInto(starts)
        e.copyInto(ends)
    }

    companion object {
        const val MAX_CUES_PER_TRACK = 4000
        private const val INITIAL_CAPACITY = 256
        private const val PGS_MIN_PAYLOAD_BYTES = 64
    }
}
