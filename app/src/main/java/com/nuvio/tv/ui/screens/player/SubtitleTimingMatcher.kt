package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.EmbeddedSubtitleTimingCollector
import kotlin.math.abs

/**
 * Scores an external subtitle against the timing of an embedded subtitle track of the same
 * file. Language does not matter: dialogue cues start at the same moments in every language,
 * so a high fraction of aligned starts means the external file was timed for this exact cut.
 *
 * Steps: estimate a single global offset (mode of the start-time deltas), then count external
 * cues whose start (and end, when known) lands on an embedded cue within tolerance. Credit cues
 * ("subs by …") near the edges with no embedded counterpart are excluded from the denominator.
 */
object SubtitleTimingMatcher {

    enum class Confidence { HIGH, MEDIUM, LOW, INSUFFICIENT, UNAVAILABLE }

    data class Result(
        val confidence: Confidence,
        /** matched / compared, 0..1. */
        val score: Float,
        /** Delay to add to the external cues so they align with the file, in ms. */
        val offsetMs: Long,
        val matchedCues: Int,
        val comparedCues: Int,
        val excludedCreditCues: Int,
        val referenceTrackNumber: Int,
        val referenceCueCount: Int
    ) {
        val scorePercent: Int get() = (score * 100f).toInt()
        /** True when the subtitle only lines up after shifting it by [offsetMs]. */
        val needsOffset: Boolean get() = abs(offsetMs) >= NO_OFFSET_TOLERANCE_MS
    }

    const val NO_OFFSET_TOLERANCE_MS = 150L

    data class Options(
        val startToleranceMs: Long = 120,
        val looseToleranceMs: Long = 400,
        val bitmapToleranceMs: Long = 200,
        val maxOffsetMs: Long = 30_000,
        val histogramBinMs: Long = 100,
        val minComparedCues: Int = 20,
        val minBitmapComparedCues: Int = 30,
        val creditEdgeWindowMs: Long = 90_000,
        val creditNoNeighbourMs: Long = 5_000,
        val maxCreditFraction: Float = 0.03f,
        val highThreshold: Float = 0.92f,
        val mediumThreshold: Float = 0.80f
    )

    private val creditTextRegex = Regex(
        """(subs?\b|subtitle|sync|correct|translat|ripp|legenda|tradu|www\.|https?:|\.com\b|\.net\b|\.org\b|@)""",
        RegexOption.IGNORE_CASE
    )

    private val insufficient = Result(
        confidence = Confidence.INSUFFICIENT,
        score = 0f,
        offsetMs = 0,
        matchedCues = 0,
        comparedCues = 0,
        excludedCreditCues = 0,
        referenceTrackNumber = -1,
        referenceCueCount = 0
    )

    /** Verdict for an addon entry whose file could not be downloaded or parsed. */
    fun unavailable(referenceTrackNumber: Int): Result =
        insufficient.copy(confidence = Confidence.UNAVAILABLE, referenceTrackNumber = referenceTrackNumber)

    /**
     * Picks the embedded track to compare against: the text track with most cues; bitmap
     * tracks only when no text track is available. Forced tracks have too few cues to be
     * a reference and are used last.
     */
    fun chooseReference(
        snapshot: EmbeddedSubtitleTimingCollector.Snapshot,
        minCues: Int = 20
    ): EmbeddedSubtitleTimingCollector.TrackTiming? {
        val candidates = snapshot.tracks.filter { it.cueCount >= minCues }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareBy<EmbeddedSubtitleTimingCollector.TrackTiming> { it.forced }
                .thenBy { it.isBitmap }
                .thenByDescending { it.cueCount }
        ).first()
    }

    fun score(
        external: List<SubtitleSyncCue>,
        reference: EmbeddedSubtitleTimingCollector.TrackTiming,
        options: Options = Options()
    ): Result {
        if (external.isEmpty() || reference.cueCount == 0) return insufficient
        val embStarts = reference.startsMs
        val embEnds = reference.endsMs
        val useEnds = reference.hasEndTimes
        val tolerance = if (reference.isBitmap) options.bitmapToleranceMs else options.startToleranceMs
        val minCompared = if (reference.isBitmap) options.minBitmapComparedCues else options.minComparedCues

        // Only the part of the file the player has already read is known on the embedded side.
        val rangeMin = reference.observedMinMs - options.maxOffsetMs
        val rangeMax = reference.observedMaxMs + options.maxOffsetMs
        val ext = external
            .asSequence()
            .filter { it.startTimeMs in rangeMin..rangeMax }
            .sortedBy { it.startTimeMs }
            .toList()
        if (ext.size < minCompared) return insufficient.copy(referenceTrackNumber = reference.trackNumber, referenceCueCount = reference.cueCount)

        val offsetMs = estimateOffset(ext, embStarts, options) ?: 0L

        // With the offset known, only cues that land inside the observed embedded range can be
        // judged; the rest of the file has simply not been read yet.
        val scoreMin = reference.observedMinMs - options.looseToleranceMs
        val scoreMax = reference.observedMaxMs + options.looseToleranceMs
        val scored = ext.filter { (it.startTimeMs + offsetMs) in scoreMin..scoreMax }
        if (scored.size < minCompared) return insufficient.copy(referenceTrackNumber = reference.trackNumber, referenceCueCount = reference.cueCount)

        var matched = 0
        var compared = 0
        var credits = 0
        val maxCredits = maxOf(1, (scored.size * options.maxCreditFraction).toInt())
        val fileStart = reference.observedMinMs
        val fileEnd = reference.observedMaxMs
        var cursor = 0
        for (cue in scored) {
            val start = cue.startTimeMs + offsetMs
            val end = if (cue.endTimeMs > cue.startTimeMs) cue.endTimeMs + offsetMs else -1L
            // Advance to first embedded cue whose start is within the loose window.
            while (cursor < embStarts.size && embStarts[cursor] < start - options.looseToleranceMs) cursor++
            var hit = false
            var nearest = Long.MAX_VALUE
            var i = cursor
            while (i < embStarts.size && embStarts[i] <= start + options.looseToleranceMs) {
                val delta = abs(embStarts[i] - start)
                if (delta < nearest) nearest = delta
                if (delta <= tolerance) { hit = true; break }
                if (useEnds && end > 0 && embEnds[i] > 0 &&
                    delta <= options.looseToleranceMs && abs(embEnds[i] - end) <= options.looseToleranceMs
                ) { hit = true; break }
                if (useEnds && end > 0 && embEnds[i] > 0 && overlapFraction(start, end, embStarts[i], embEnds[i]) >= 0.7f) {
                    hit = true; break
                }
                i++
            }
            if (hit) { matched++; compared++; continue }
            // Credit / advert cue: near the edges, no embedded cue anywhere close, and looks like one.
            val nearEdge = start - fileStart <= options.creditEdgeWindowMs || fileEnd - start <= options.creditEdgeWindowMs
            val isolated = nearest > options.creditNoNeighbourMs || !hasNeighbour(embStarts, start, options.creditNoNeighbourMs)
            if (credits < maxCredits && isolated && (nearEdge || creditTextRegex.containsMatchIn(cue.text))) {
                credits++
                continue
            }
            compared++
        }
        if (compared < minCompared) {
            return insufficient.copy(referenceTrackNumber = reference.trackNumber, referenceCueCount = reference.cueCount)
        }
        val score = matched.toFloat() / compared.toFloat()
        val confidence = when {
            score >= options.highThreshold -> Confidence.HIGH
            score >= options.mediumThreshold -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return Result(
            confidence = confidence,
            score = score,
            offsetMs = offsetMs,
            matchedCues = matched,
            comparedCues = compared,
            excludedCreditCues = credits,
            referenceTrackNumber = reference.trackNumber,
            referenceCueCount = reference.cueCount
        )
    }

    /**
     * Mode of (embedded start - external start) over all pairs within ±maxOffset, refined to
     * the median of the deltas inside the winning bin and its neighbours. Returns null when
     * no bin stands out (different cut or unrelated subtitle).
     */
    internal fun estimateOffset(
        ext: List<SubtitleSyncCue>,
        embStarts: LongArray,
        options: Options
    ): Long? {
        if (ext.isEmpty() || embStarts.isEmpty()) return null
        val bin = options.histogramBinMs
        val bins = HashMap<Long, Int>()
        var lo = 0
        for (cue in ext) {
            val s = cue.startTimeMs
            while (lo < embStarts.size && embStarts[lo] < s - options.maxOffsetMs) lo++
            var i = lo
            while (i < embStarts.size && embStarts[i] <= s + options.maxOffsetMs) {
                val delta = embStarts[i] - s
                val key = Math.floorDiv(delta, bin)
                bins[key] = (bins[key] ?: 0) + 1
                i++
            }
        }
        if (bins.isEmpty()) return null
        val best = bins.maxByOrNull { it.value } ?: return null
        // A real alignment concentrates a large share of cues in one bin; noise spreads evenly.
        if (best.value < maxOf(5, ext.size / 10)) return null
        val deltas = ArrayList<Long>(best.value * 2)
        lo = 0
        for (cue in ext) {
            val s = cue.startTimeMs
            while (lo < embStarts.size && embStarts[lo] < s - options.maxOffsetMs) lo++
            var i = lo
            while (i < embStarts.size && embStarts[i] <= s + options.maxOffsetMs) {
                val delta = embStarts[i] - s
                val key = Math.floorDiv(delta, bin)
                if (abs(key - best.key) <= 1) deltas.add(delta)
                i++
            }
        }
        if (deltas.isEmpty()) return best.key * bin + bin / 2
        deltas.sort()
        return deltas[deltas.size / 2]
    }

    private fun hasNeighbour(embStarts: LongArray, start: Long, windowMs: Long): Boolean {
        var lo = 0
        var hi = embStarts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (embStarts[mid] < start - windowMs) lo = mid + 1 else hi = mid
        }
        return lo < embStarts.size && embStarts[lo] <= start + windowMs
    }

    private fun overlapFraction(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Float {
        val overlap = minOf(aEnd, bEnd) - maxOf(aStart, bStart)
        if (overlap <= 0) return 0f
        val shorter = minOf(aEnd - aStart, bEnd - bStart)
        if (shorter <= 0) return 0f
        return overlap.toFloat() / shorter.toFloat()
    }
}
