package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.EmbeddedSubtitleTimingCollector
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Verifies addon subtitles against the embedded subtitle timeline of the playing MKV
 * (see [EmbeddedSubtitleTimingCollector] / [SubtitleTimingMatcher]) and, when allowed,
 * swaps the auto-selected subtitle for the best-synced candidate and applies its offset.
 *
 * Runs only on the ExoPlayer engine for Matroska streams; every other case leaves the
 * language-based auto-selection untouched. Candidate downloads are best effort: an addon
 * that lists a subtitle it cannot serve is marked UNAVAILABLE and skipped.
 */

private const val MATCH_TAG = "SubtitleTimingMatch"
private const val MIN_REFERENCE_CUES = 20
private const val REFERENCE_WAIT_POLL_MS = 2_000L
private const val REFERENCE_WAIT_TIMEOUT_MS = 180_000L
private const val MAX_CANDIDATES_PER_LANGUAGE = 20
private const val MAX_CANDIDATES_TOTAL = 24
private const val PARALLEL_DOWNLOADS = 3
private const val MIN_AUTO_OFFSET_MS = 150L
private const val MIN_RESCORE_NEW_CUES = 20
private const val RESCORE_INTERVAL_MS = 30_000L
private const val EARLY_STOP_MARGIN = 0.05f
private const val MAX_RESCORE_ATTEMPTS = 20
/**
 * Addon entries that are machine translations produced on demand (SubMaker: "translate_<src>_to_<lang>").
 * Their first download returns a "translating…" stub, so they are never usable as sync candidates.
 */
private val onDemandTranslationIdPrefixes = listOf("translate_")

internal fun isOnDemandTranslationSubtitle(subtitle: Subtitle): Boolean =
    onDemandTranslationIdPrefixes.any { subtitle.id.startsWith(it, ignoreCase = true) }

private const val PLACEHOLDER_MAX_CUES = 10
private const val PLACEHOLDER_MIN_SPAN_MS = 60L * 60L * 1000L

internal fun PlayerRuntimeController.resetSubtitleTimingMatchState() {
    subtitleTimingMatchJob?.cancel()
    subtitleTimingMatchJob = null
    subtitleTimingRescoreJob?.cancel()
    subtitleTimingRescoreJob = null
    subtitleTimingRescoreAttempts = 0
    subtitleSyncArbiterJob?.cancel()
    subtitleSyncArbiterJob = null
    embeddedSubtitleTimings.reset()
    subtitleTimingCueCache.clear()
    subtitleTimingMatchGeneration = -1
    subtitleTimingMatchApplied = false
    subtitleSyncComparison = SubtitleSyncComparison()
    _uiState.update {
        it.copy(
            subtitleTimingMatches = emptyMap(),
            subtitleTimingMatchInProgress = false,
            autoSyncPickKey = null,
            autoSyncPickOffsetMs = null
        )
    }
}

/** Logs both methods' verdicts side by side once each has reported for this stream. */
internal fun PlayerRuntimeController.logSubtitleSyncComparison() {
    val c = subtitleSyncComparison
    if (c.logged || !c.autoSyncDone || !c.timingDone) return
    subtitleSyncComparison = c.copy(logged = true)
    val agree = when {
        c.autoSyncKey == null && c.timingKey == null -> "both-none"
        c.autoSyncKey == c.timingKey -> "same-subtitle"
        else -> "DIFFERENT"
    }
    val offsetDelta = if (c.autoSyncOffsetMs != null && c.timingOffsetMs != null) {
        abs(c.autoSyncOffsetMs - c.timingOffsetMs)
    } else {
        null
    }
    Log.i(
        MATCH_TAG,
        "SUBTITLE_SYNC_COMPARE verdict=$agree offsetDelta=${offsetDelta ?: "n/a"}ms | " +
            "autosync(cues-index): pick=${c.autoSyncLabel ?: "none"} offset=${c.autoSyncOffsetMs ?: "n/a"}ms " +
            "score=${c.autoSyncScore?.let { "%.3f".format(it) } ?: "n/a"} | " +
            "timing(extractor): pick=${c.timingLabel ?: "none"} offset=${c.timingOffsetMs ?: "n/a"}ms " +
            "score=${c.timingScore?.let { "%.3f".format(it) } ?: "n/a"}"
    )
    Log.i(
        PlayerRuntimeController.TAG,
        "SUBTITLE_SYNC_COMPARE verdict=$agree autosync=${c.autoSyncLabel ?: "none"}@${c.autoSyncOffsetMs ?: "n/a"} " +
            "timing=${c.timingLabel ?: "none"}@${c.timingOffsetMs ?: "n/a"}"
    )
}

/**
 * Starts (or re-runs) the timing match when it can produce something new: the setting is on,
 * the engine is ExoPlayer, the file exposes embedded subtitle tracks and there are addon
 * candidates. Safe to call often; a running job is kept, a finished one re-runs only when
 * the extractor has read materially more cues since the last pass.
 */
internal fun PlayerRuntimeController.maybeStartSubtitleTimingMatch(trigger: String) {
    if (!currentPlayerSettingsForReport.subtitleStyle.autoMatchEmbeddedTiming) return
    if (isUsingMpvEngine()) return
    val state = _uiState.value
    if (state.addonSubtitles.isEmpty()) {
        if (trigger == "addon-fetch") submitTimingVerdict(null)
        return
    }
    if (subtitleTimingMatchJob?.isActive == true) return
    val snapshot = embeddedSubtitleTimings.snapshot()
    if (snapshot.tracks.isEmpty()) {
        Log.i(MATCH_TAG, "skip($trigger): no embedded subtitle tracks reported yet")
        // Let the arbiter proceed with the other method instead of waiting for a timeout.
        if (trigger == "addon-fetch") submitTimingVerdict(null)
        return
    }
    if (subtitleTimingMatchGeneration >= 0) {
        val previousCues = state.subtitleTimingMatches.values.maxOfOrNull { it.referenceCueCount } ?: 0
        val currentCues = SubtitleTimingMatcher.chooseReference(snapshot, MIN_REFERENCE_CUES)?.cueCount ?: 0
        if (currentCues - previousCues < MIN_RESCORE_NEW_CUES) return
    }
    Log.i(MATCH_TAG, "start($trigger): tracks=${snapshot.tracks.size} candidates=${state.addonSubtitles.size}")
    subtitleTimingMatchJob = scope.launch {
        try {
            _uiState.update { it.copy(subtitleTimingMatchInProgress = true) }
            runSubtitleTimingMatch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(MATCH_TAG, "match failed", e)
        } finally {
            _uiState.update { it.copy(subtitleTimingMatchInProgress = false) }
        }
    }
}

private suspend fun PlayerRuntimeController.runSubtitleTimingMatch() {
    val reference = awaitReferenceTrack() ?: run {
        Log.i(MATCH_TAG, "no usable embedded reference track (forced-only or too few cues)")
        submitTimingVerdict(null)
        return
    }
    val targets = subtitleLanguageTargets()
    val candidates = pickCandidates(_uiState.value.addonSubtitles, targets)
    if (candidates.isEmpty()) {
        Log.i(MATCH_TAG, "no addon candidates for targets=$targets")
        submitTimingVerdict(null)
        return
    }
    candidates.forEachIndexed { i, c ->
        Log.i(MATCH_TAG, "candidate[$i] ${c.addonName}/${c.lang} id=${c.id} url=${c.url.take(160)}")
    }
    Log.i(
        MATCH_TAG,
        "reference track=${reference.trackNumber} codec=${reference.codecId} lang=${reference.language} " +
            "cues=${reference.cueCount} range=${reference.observedMinMs}..${reference.observedMaxMs}ms; " +
            "candidates=${candidates.size}"
    )

    // Pipeline: PARALLEL_DOWNLOADS candidates in flight, each scored the moment its body
    // lands; stop everything once one candidate is clearly in sync.
    val semaphore = Semaphore(PARALLEL_DOWNLOADS)
    val results = HashMap<String, SubtitleTimingMatcher.Result>()
    val early = SubtitleTimingMatcher.Options().highThreshold + EARLY_STOP_MARGIN
    coroutineScope {
        val channel = Channel<Pair<Subtitle, SubtitleTimingMatcher.Result>>(Channel.UNLIMITED)
        val producers = candidates.map { candidate ->
            launch {
                val key = addonSubtitleKey(candidate)
                val cues = subtitleTimingCueCache[key] ?: semaphore.withPermit { downloadAndParse(candidate) }
                val result = if (cues == null) {
                    SubtitleTimingMatcher.unavailable(reference.trackNumber)
                } else {
                    subtitleTimingCueCache[key] = cues
                    withContext(Dispatchers.Default) { SubtitleTimingMatcher.score(cues, reference) }
                }
                channel.send(candidate to result)
            }
        }
        var received = 0
        while (received < candidates.size) {
            val (candidate, result) = channel.receive()
            received++
            results[addonSubtitleKey(candidate)] = result
            Log.i(
                MATCH_TAG,
                "candidate ${candidate.addonName}/${candidate.lang} id=${candidate.id}: ${result.confidence} " +
                    "${result.scorePercent}% offset=${result.offsetMs}ms matched=${result.matchedCues}/${result.comparedCues} " +
                    "credits=${result.excludedCreditCues}"
            )
            // Publish progressively so the overlay fills in as candidates finish.
            _uiState.update { it.copy(subtitleTimingMatches = it.subtitleTimingMatches + results) }
            // Only a candidate that needs no offset ends the search early; an offset match is
            // kept as fallback while the rest are checked for one that fits as-is.
            if (result.confidence == SubtitleTimingMatcher.Confidence.HIGH && result.score >= early &&
                abs(result.offsetMs) < MIN_AUTO_OFFSET_MS
            ) {
                Log.i(MATCH_TAG, "early stop: ${candidate.addonName}/${candidate.lang} id=${candidate.id} at ${result.scorePercent}% (${candidates.size - received} skipped)")
                producers.forEach { it.cancel() }
                break
            }
        }
    }
    subtitleTimingMatchGeneration = embeddedSubtitleTimings.generation()
    applySubtitleTimingDecision(candidates, results, targets)
    // The player only reads ~1-2 min ahead; keep re-scoring the cached cues as more of the
    // file's timeline arrives until a candidate reaches HIGH or the attempts run out.
    val bestScore = results.values.maxOfOrNull { it.score } ?: 0f
    if (bestScore < SubtitleTimingMatcher.Options().highThreshold) scheduleSubtitleTimingRescore()
}

private fun PlayerRuntimeController.scheduleSubtitleTimingRescore() {
    if (subtitleTimingRescoreAttempts >= MAX_RESCORE_ATTEMPTS) return
    if (subtitleTimingRescoreJob?.isActive == true) return
    subtitleTimingRescoreAttempts++
    val streamAtStart = currentStreamUrl
    subtitleTimingRescoreJob = scope.launch {
        delay(RESCORE_INTERVAL_MS)
        if (currentStreamUrl != streamAtStart) return@launch
        maybeStartSubtitleTimingMatch(trigger = "rescore#$subtitleTimingRescoreAttempts")
        // If the guard skipped (not enough new cues yet), try again later.
        if (subtitleTimingMatchJob?.isActive != true) scheduleSubtitleTimingRescore()
    }
}

private suspend fun PlayerRuntimeController.awaitReferenceTrack(): EmbeddedSubtitleTimingCollector.TrackTiming? {
    val deadline = System.currentTimeMillis() + REFERENCE_WAIT_TIMEOUT_MS
    while (true) {
        val snapshot = embeddedSubtitleTimings.snapshot()
        val reference = SubtitleTimingMatcher.chooseReference(snapshot, MIN_REFERENCE_CUES)
        if (reference != null) return reference
        if (snapshot.tracks.isEmpty() || System.currentTimeMillis() > deadline) return null
        delay(REFERENCE_WAIT_POLL_MS)
    }
}

private fun PlayerRuntimeController.pickCandidates(all: List<Subtitle>, targets: List<String>): List<Subtitle> {
    val useForced = _uiState.value.subtitleStyle.useForcedSubtitles
    val seen = HashSet<String>()
    val picked = ArrayList<Subtitle>()
    val languages = if (targets.isEmpty()) all.map { it.lang }.distinct() else targets
    for (target in languages) {
        var perLanguage = 0
        for (subtitle in all) {
            if (picked.size >= MAX_CANDIDATES_TOTAL || perLanguage >= MAX_CANDIDATES_PER_LANGUAGE) break
            if (!PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)) continue
            if (useForced && addonSubtitleIsForced(subtitle)) continue
            if (isOnDemandTranslationSubtitle(subtitle)) continue
            if (!seen.add(addonSubtitleKey(subtitle))) continue
            picked.add(subtitle)
            perLanguage++
        }
    }
    return picked
}

/** Returns null when the addon lists a subtitle it cannot actually serve (HTTP error, empty, unparsable). */
private suspend fun PlayerRuntimeController.downloadAndParse(subtitle: Subtitle): List<SubtitleSyncCue>? {
    return try {
        val body = downloadSubtitleBody(subtitle.url, subtitle.lang, subtitle.headers)
        if (body.isBlank()) return null
        val cues = withContext(Dispatchers.Default) {
            PlayerSubtitleCueParser.parseFromText(body, subtitle.url)
        }
        if (cues.isEmpty()) return null
        val spanMs = cues.maxOf { it.endTimeMs } - cues.minOf { it.startTimeMs }
        if (cues.size < PLACEHOLDER_MAX_CUES && spanMs > PLACEHOLDER_MIN_SPAN_MS) {
            // SubMaker-style stub: 2-3 cues covering hours ("translating, please wait").
            Log.i(MATCH_TAG, "candidate placeholder ${subtitle.addonName}/${subtitle.lang} id=${subtitle.id}: cues=${cues.size} span=${spanMs}ms text=${cues.first().text.take(80)}")
            return null
        }
        cues
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(MATCH_TAG, "candidate unavailable ${subtitle.addonName}/${subtitle.lang} id=${subtitle.id}: ${e.message}")
        null
    }
}

private fun PlayerRuntimeController.applySubtitleTimingDecision(
    candidates: List<Subtitle>,
    results: Map<String, SubtitleTimingMatcher.Result>,
    targets: List<String>
) {
    val state = _uiState.value
    val current = state.selectedAddonSubtitle
    val currentResult = current?.let { results[addonSubtitleKey(it)] }
    // Best HIGH candidate, primary language first, then by score; only sidecar-attachable
    // entries can be switched to without a media reload.
    val best = targets.ifEmpty { candidates.map { it.lang }.distinct() }
        .asSequence()
        .mapNotNull { target ->
            candidates
                .filter { PlayerSubtitleUtils.matchesLanguageCode(it.lang, target) }
                .mapNotNull { c -> results[addonSubtitleKey(c)]?.let { r -> c to r } }
                .filter { (c, r) -> r.confidence == SubtitleTimingMatcher.Confidence.HIGH && canAttachAddonSubtitleViaSidecar(c) }
                // Prefer a subtitle that fits the file as-is; fall back to one that needs an offset.
                .sortedWith(compareBy<Pair<Subtitle, SubtitleTimingMatcher.Result>> { (_, r) -> r.needsOffset }.thenByDescending { (_, r) -> r.score })
                .firstOrNull()
        }
        .firstOrNull()
    val verdict: Pair<Subtitle, SubtitleTimingMatcher.Result>? = when {
        current != null && currentResult?.confidence == SubtitleTimingMatcher.Confidence.HIGH &&
            (!currentResult.needsOffset || best == null || best.second.needsOffset) -> current to currentResult
        best != null -> best
        // No HIGH anywhere: a consistent MEDIUM on the current pick still carries an offset.
        current != null && currentResult?.confidence == SubtitleTimingMatcher.Confidence.MEDIUM -> current to currentResult
        else -> null
    }
    submitTimingVerdict(verdict)
}

internal fun PlayerRuntimeController.submitTimingVerdict(verdict: Pair<Subtitle, SubtitleTimingMatcher.Result>?) {
    subtitleSyncComparison = subtitleSyncComparison.copy(
        timingDone = true,
        timingSubtitle = verdict?.first,
        timingKey = verdict?.let { addonSubtitleKey(it.first) },
        timingLabel = verdict?.let { (s, _) -> "${s.addonName}/${s.lang}#${s.id}" },
        timingOffsetMs = verdict?.second?.offsetMs,
        timingScore = verdict?.second?.score
    )
    logSubtitleSyncComparison()
    scheduleSubtitleSyncArbitration()
}
