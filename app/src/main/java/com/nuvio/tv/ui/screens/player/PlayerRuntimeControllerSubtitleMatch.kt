package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.EmbeddedSubtitleTimingCollector
import androidx.media3.common.C
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.autosync.EmbeddedSubtitleTimelineLoader
import com.nuvio.tv.ui.screens.player.autosync.ReferenceTrack
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
private const val REFERENCE_WAIT_TIMEOUT_MS = 40_000L
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
    applySubtitleTimeScale(1.0)
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

/**
 * Whole-file reference from the Matroska Cues index (shared with the fork's AutoSync loader,
 * so the bytes are fetched once). Start times only; null when the file does not index its
 * subtitle tracks.
 */
private suspend fun PlayerRuntimeController.indexedReferenceTrack(): EmbeddedSubtitleTimingCollector.TrackTiming? {
    val timeline = try {
        EmbeddedSubtitleTimelineLoader.load(currentStreamUrl, currentHeaders.toMap())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(MATCH_TAG, "cues-index load failed: ${e.message}")
        null
    } ?: return null
    val track = timeline.tracks
        .filter { it.cues.size >= MIN_REFERENCE_CUES }
        .sortedWith(
            compareBy<ReferenceTrack> { (it.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 }
                // Text tracks first: PGS indexes carry "clear" points too and are coarser.
                .thenBy { !it.codecId.startsWith("S_TEXT") }
                .thenByDescending { it.cues.size }
        )
        .firstOrNull() ?: return null
    val number = track.key.substringAfterLast(':').toIntOrNull() ?: -1
    return EmbeddedSubtitleTimingCollector.fromStartTimes(
        trackNumber = number,
        language = track.language,
        forced = (track.selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
        startTimesMs = track.cues.map { it.startTimeMs },
        codecId = EmbeddedSubtitleTimingCollector.CODEC_CUES_INDEX + ":" + track.codecId
    )
}

private suspend fun PlayerRuntimeController.runSubtitleTimingMatch() {
    // Prefer the whole-file index (instant, full coverage); fall back to what the extractor
    // has read so far, which is exact but limited to the player's read-ahead window.
    val extractorNow = SubtitleTimingMatcher.chooseReference(embeddedSubtitleTimings.snapshot(), MIN_REFERENCE_CUES)
    val indexed = indexedReferenceTrack()
    val reference = when {
        indexed != null && (extractorNow == null || extractorNow.cueCount < indexed.cueCount) -> {
            Log.i(MATCH_TAG, "reference from cues-index: track=${indexed.trackNumber} lang=${indexed.language} cues=${indexed.cueCount}")
            indexed
        }
        extractorNow != null -> extractorNow
        else -> awaitReferenceTrack()
    } ?: run {
        Log.i(MATCH_TAG, "no usable embedded reference track yet (forced-only or too few cues); will retry")
        submitTimingVerdict(null)
        scheduleSubtitleTimingRescore()
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

    val results = HashMap<String, SubtitleTimingMatcher.Result>()

    // The subtitle already on screen is scored alone first. When it fits the reference
    // (HIGH or MEDIUM, offset or not) the other candidates are never downloaded: each
    // download is one addon request, and OpenSubtitles rate-limits a burst of 20+.
    val selected = _uiState.value.selectedAddonSubtitle
        ?.takeIf { s -> candidates.any { addonSubtitleKey(it) == addonSubtitleKey(s) } }
    if (selected != null) {
        val key = addonSubtitleKey(selected)
        val cues = subtitleTimingCueCache[key] ?: downloadAndParse(selected)?.also { subtitleTimingCueCache[key] = it }
        val result = if (cues == null) {
            SubtitleTimingMatcher.unavailable(reference.trackNumber)
        } else {
            withContext(Dispatchers.Default) { SubtitleTimingMatcher.score(cues, reference) }
        }
        results[key] = result
        Log.i(
            MATCH_TAG,
            "selected ${selected.addonName}/${selected.lang} id=${selected.id}: ${result.confidence} " +
                "${result.scorePercent}% offset=${result.offsetMs}ms matched=${result.matchedCues}/${result.comparedCues}"
        )
        _uiState.update { it.copy(subtitleTimingMatches = it.subtitleTimingMatches + results) }
        if (result.confidence == SubtitleTimingMatcher.Confidence.HIGH ||
            result.confidence == SubtitleTimingMatcher.Confidence.MEDIUM
        ) {
            Log.i(MATCH_TAG, "selected subtitle fits; skipping ${candidates.size - 1} other candidates")
            subtitleTimingMatchGeneration = embeddedSubtitleTimings.generation()
            applySubtitleTimingDecision(candidates, results, targets)
            return
        }
    }

    // Pipeline: PARALLEL_DOWNLOADS candidates in flight, each scored the moment its body
    // lands; stop everything once one candidate is clearly in sync.
    val semaphore = Semaphore(PARALLEL_DOWNLOADS)
    val early = SubtitleTimingMatcher.Options().highThreshold + EARLY_STOP_MARGIN
    coroutineScope {
        val channel = Channel<Pair<Subtitle, SubtitleTimingMatcher.Result>>(Channel.UNLIMITED)
        val producers = candidates.filter { addonSubtitleKey(it) !in results }.map { candidate ->
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
        var earlyApplied = false
        while (received < producers.size) {
            val (candidate, result) = channel.receive()
            received++
            results[addonSubtitleKey(candidate)] = result
            Log.i(
                MATCH_TAG,
                "candidate ${candidate.addonName}/${candidate.lang} id=${candidate.id}: ${result.confidence} " +
                    "${result.scorePercent}% offset=${result.offsetMs}ms matched=${result.matchedCues}/${result.comparedCues} " +
                    "credits=${result.excludedCreditCues}" + (if (result.hasDrift) " scale=${"%.5f".format(result.scale)}" else "")
            )
            // Publish progressively so the overlay fills in as candidates finish.
            _uiState.update { it.copy(subtitleTimingMatches = it.subtitleTimingMatches + results) }
            // A clear match is handed to the arbiter right away; scoring continues in the
            // background so every entry in the list gets its score and offset.
            if (!earlyApplied && result.confidence == SubtitleTimingMatcher.Confidence.HIGH && result.score >= early) {
                Log.i(MATCH_TAG, "early verdict: ${candidate.addonName}/${candidate.lang} id=${candidate.id} at ${result.scorePercent}% offset=${result.offsetMs}ms (${producers.size - received} still scoring)")
                earlyApplied = true
                applySubtitleTimingDecision(candidates, HashMap(results), targets)
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
    var polls = 0
    while (true) {
        val snapshot = embeddedSubtitleTimings.snapshot()
        val reference = SubtitleTimingMatcher.chooseReference(snapshot, MIN_REFERENCE_CUES)
        if (reference != null) return reference
        if (polls % 5 == 0) {
            Log.i(MATCH_TAG, "waiting for reference: " + snapshot.tracks.joinToString { t ->
                "#${t.trackNumber}(${t.codecId},${t.language ?: "?"},forced=${t.forced},cues=${t.cueCount},received=${t.receivedCount},range=${t.observedMinMs}..${t.observedMaxMs})"
            } + " position=${currentPlaybackPositionMs() ?: -1}ms")
        }
        polls++
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
                // Score first; a no-offset match only breaks ties (the delay is saved per video,
                // so a subtitle that fits as-is leaves nothing behind if the user switches later).
                .sortedWith(compareByDescending<Pair<Subtitle, SubtitleTimingMatcher.Result>> { (_, r) -> r.score }.thenBy { (_, r) -> r.needsOffset })
                .firstOrNull()
        }
        .firstOrNull()
    val verdict: Pair<Subtitle, SubtitleTimingMatcher.Result>? = when {
        current != null && currentResult?.confidence == SubtitleTimingMatcher.Confidence.HIGH -> current to currentResult
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
        timingScore = verdict?.second?.score,
        timingScale = verdict?.second?.scale ?: 1.0
    )
    logSubtitleSyncComparison()
    scheduleSubtitleSyncArbitration()
}
