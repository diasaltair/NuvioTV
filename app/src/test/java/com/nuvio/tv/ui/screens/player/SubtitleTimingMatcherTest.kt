package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.core.player.EmbeddedSubtitleTimingCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SubtitleTimingMatcherTest {

    private fun embedded(cues: List<SubtitleSyncCue>, codec: String = "S_TEXT/UTF8"): EmbeddedSubtitleTimingCollector.TrackTiming {
        val collector = EmbeddedSubtitleTimingCollector()
        collector.onSubtitleTrack(3, codec, "eng", false)
        for (c in cues) {
            val dur = if (codec.startsWith("S_TEXT")) (c.endTimeMs - c.startTimeMs) * 1000 else C.TIME_UNSET
            collector.onSubtitleSample(3, c.startTimeMs * 1000, dur, 200)
        }
        return collector.snapshot().track(3)!!
    }

    private fun dialogue(count: Int, seed: Int = 1): List<SubtitleSyncCue> {
        val rnd = Random(seed)
        var t = 5_000L
        return List(count) { i ->
            t += 1_500 + rnd.nextLong(0, 4_000)
            val d = 900 + rnd.nextLong(0, 3_000)
            SubtitleSyncCue(t, t + d, "line $i")
        }
    }

    @Test
    fun identicalTimingScoresHigh() {
        val cues = dialogue(400)
        val r = SubtitleTimingMatcher.score(cues, embedded(cues))
        assertEquals(SubtitleTimingMatcher.Confidence.HIGH, r.confidence)
        assertEquals(0L, r.offsetMs)
        assertTrue(r.score >= 0.99f)
    }

    @Test
    fun shiftedExternalGetsOffsetAndHigh() {
        val cues = dialogue(400)
        val shifted = cues.map { it.copy(startTimeMs = it.startTimeMs - 2_300, endTimeMs = it.endTimeMs - 2_300) }
        val r = SubtitleTimingMatcher.score(shifted, embedded(cues))
        assertEquals(SubtitleTimingMatcher.Confidence.HIGH, r.confidence)
        assertTrue("offset=${r.offsetMs}", r.offsetMs in 2_200L..2_400L)
    }

    @Test
    fun creditCuesAreExcludedNotPenalised() {
        val cues = dialogue(300)
        // Credits in the gap before the first line, in a mid-file silence, and after the last line.
        val gapStart = cues[0].startTimeMs - 300
        val quiet = cues.indices.first { i -> i > 0 && cues[i].startTimeMs - cues[i - 1].endTimeMs > 3_000 }
        val withCredits = listOf(
            SubtitleSyncCue(gapStart - 1_000, gapStart, "Subs by SomeGroup"),
            SubtitleSyncCue(cues[quiet - 1].endTimeMs + 1_000, cues[quiet].startTimeMs - 500, "www.example.com")
        ) + cues + listOf(SubtitleSyncCue(cues.last().endTimeMs + 200, cues.last().endTimeMs + 3_000, "Synced by X"))
        val r = SubtitleTimingMatcher.score(withCredits, embedded(cues))
        assertEquals(SubtitleTimingMatcher.Confidence.HIGH, r.confidence)
        assertTrue("excluded=${r.excludedCreditCues}", r.excludedCreditCues in 1..3)
        assertTrue(r.score >= 0.98f)
    }

    @Test
    fun jitteredWithinToleranceStillHigh() {
        val cues = dialogue(400)
        val rnd = Random(7)
        val jittered = cues.map { val j = rnd.nextLong(-80, 80); it.copy(startTimeMs = it.startTimeMs + j, endTimeMs = it.endTimeMs + j) }
        val r = SubtitleTimingMatcher.score(jittered, embedded(cues))
        assertEquals(SubtitleTimingMatcher.Confidence.HIGH, r.confidence)
    }

    @Test
    fun unrelatedSubtitleIsLow() {
        val a = dialogue(400, seed = 1)
        val b = dialogue(400, seed = 99)
        val r = SubtitleTimingMatcher.score(b, embedded(a))
        assertTrue(r.confidence == SubtitleTimingMatcher.Confidence.LOW || r.confidence == SubtitleTimingMatcher.Confidence.INSUFFICIENT)
    }

    @Test
    fun differentCutInsertedSceneIsNotHigh() {
        val a = dialogue(400)
        // Extended cut: 90 s inserted at cue 200 -> second half drifts by 90 s.
        val b = a.mapIndexed { i, c -> if (i >= 200) c.copy(startTimeMs = c.startTimeMs + 90_000, endTimeMs = c.endTimeMs + 90_000) else c }
        val r = SubtitleTimingMatcher.score(b, embedded(a))
        assertTrue(r.confidence != SubtitleTimingMatcher.Confidence.HIGH)
    }

    @Test
    fun pgsReferenceUsesStartOnly() {
        val cues = dialogue(300)
        val r = SubtitleTimingMatcher.score(cues, embedded(cues, codec = "S_HDMV/PGS"))
        assertEquals(SubtitleTimingMatcher.Confidence.HIGH, r.confidence)
    }

    @Test
    fun tooFewCuesIsInsufficient() {
        val cues = dialogue(10)
        val r = SubtitleTimingMatcher.score(cues, embedded(cues))
        assertEquals(SubtitleTimingMatcher.Confidence.INSUFFICIENT, r.confidence)
    }

    @Test
    fun onlyObservedRangeIsCompared() {
        val full = dialogue(600)
        // Player only read cues 300..450 (resume mid-file).
        val partial = full.subList(300, 450)
        val r = SubtitleTimingMatcher.score(full, embedded(partial))
        assertEquals(SubtitleTimingMatcher.Confidence.HIGH, r.confidence)
        assertTrue(r.comparedCues in 140..200)
    }

    @Test
    fun chooseReferencePrefersTextOverBitmapAndNonForced() {
        val collector = EmbeddedSubtitleTimingCollector()
        collector.onSubtitleTrack(2, "S_HDMV/PGS", "eng", false)
        collector.onSubtitleTrack(3, "S_TEXT/UTF8", "eng", true)
        collector.onSubtitleTrack(4, "S_TEXT/UTF8", "fre", false)
        var t = 0L
        repeat(100) { t += 2000; collector.onSubtitleSample(2, t * 1000, C.TIME_UNSET, 500) }
        t = 0; repeat(30) { t += 2000; collector.onSubtitleSample(3, t * 1000, 1_000_000, 50) }
        t = 0; repeat(60) { t += 2000; collector.onSubtitleSample(4, t * 1000, 1_000_000, 50) }
        val ref = SubtitleTimingMatcher.chooseReference(collector.snapshot())
        assertNotNull(ref)
        assertEquals(4, ref!!.trackNumber)
    }
}
