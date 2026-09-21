package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import com.nuvio.tv.core.player.EmbeddedSubtitleTimingCollector
import androidx.media3.common.C
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.autosync.EmbeddedSubtitleTimelineLoader
import com.nuvio.tv.ui.screens.player.autosync.ReferenceTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
private const val MIN_AUTO_OFFSET_MS = 150
/** Below this the selected subtitle is not trusted and the next addon entry is tried. */
private const val ACCEPT_SCORE = 0.60f
private val syncToastHandler = Handler(Looper.getMainLooper())
private const val MIN_RESCORE_NEW_CUES = 20
private const val RESCORE_INTERVAL_MS = 30_000L
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
    syncAppliedSubtitleDelay = false
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
    val streamAtStart = currentStreamUrl
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
    Log.i(
        MATCH_TAG,
        "reference track=${reference.trackNumber} codec=${reference.codecId} lang=${reference.language} " +
            "cues=${reference.cueCount} range=${reference.observedMinMs}..${reference.observedMaxMs}ms"
    )

    // Built-in (or no) subtitle selected: nothing to verify, and an embedded track never
    // carries a sync offset. Any delay left behind by an earlier sync pick is cleared.
    val selected = _uiState.value.selectedAddonSubtitle
    if (selected == null) {
        clearSyncAppliedSubtitleDelay(reason = "built-in subtitle selected")
        submitTimingVerdict(null)
        return
    }

    // AutoSync V2 (fork) owns synchronisation when enabled: it retimes the sidecar timeline
    // itself and may replace the subtitle. The matcher then only scores the selected entry
    // for the overlay (one addon request) and never applies an offset or switches.
    val autoSyncOwnsSync = AutoSyncPreferences.isEnabled(context)
    val selectedResult = scoreCandidate(selected, reference, fixedOffsetMs = null)
    val results = HashMap<String, SubtitleTimingMatcher.Result>()
    results[addonSubtitleKey(selected)] = selectedResult
    _uiState.update { it.copy(subtitleTimingMatches = it.subtitleTimingMatches + results) }
    Log.i(
        MATCH_TAG,
        "selected ${selected.addonName}/${selected.lang} id=${selected.id}: ${selectedResult.confidence} " +
            "${selectedResult.scorePercent}% offset=${selectedResult.offsetMs}ms " +
            "matched=${selectedResult.matchedCues}/${selectedResult.comparedCues}"
    )
    if (selectedResult.confidence == SubtitleTimingMatcher.Confidence.INSUFFICIENT) {
        // Not enough of the file read yet to judge; do not spend requests on other subtitles.
        submitTimingVerdict(null)
        scheduleSubtitleTimingRescore()
        return
    }

    var best: Subtitle = selected
    var bestResult = selectedResult
    if (autoSyncOwnsSync) {
        Log.i(MATCH_TAG, "autosync v2 enabled; score shown only, no apply")
        submitTimingVerdict(null)
        subtitleTimingMatchGeneration = embeddedSubtitleTimings.generation()
        return
    }
    if (bestResult.score >= ACCEPT_SCORE) {
        applySubtitleSyncPick(best, bestResult, switching = false)
    } else if (isUserExplicitSubtitleSelection) {
        Log.i(MATCH_TAG, "selected below ${(ACCEPT_SCORE * 100).toInt()}% but picked by the user; not looking further")
        if (bestResult.confidence == SubtitleTimingMatcher.Confidence.MEDIUM) applySubtitleSyncPick(best, bestResult, switching = false)
    } else {
        // Step 3: walk the addon list in order, one download at a time. A better-scoring
        // subtitle replaces the pick; the first one that is not better ends the search.
        val targets = subtitleLanguageTargets()
        val others = pickCandidates(_uiState.value.addonSubtitles, targets)
            .filter { addonSubtitleKey(it) != addonSubtitleKey(selected) }
        for (next in others) {
            if (bestResult.score >= ACCEPT_SCORE) break
            if (currentStreamUrl != streamAtStart) return
            val r = scoreCandidate(next, reference, fixedOffsetMs = null)
            results[addonSubtitleKey(next)] = r
            _uiState.update { it.copy(subtitleTimingMatches = it.subtitleTimingMatches + results) }
            Log.i(
                MATCH_TAG,
                "next ${next.addonName}/${next.lang} id=${next.id}: ${r.confidence} ${r.scorePercent}% " +
                    "offset=${r.offsetMs}ms matched=${r.matchedCues}/${r.comparedCues} vs best ${bestResult.scorePercent}%"
            )
            if (r.confidence == SubtitleTimingMatcher.Confidence.UNAVAILABLE) continue // no information, not "worse"
            if (r.score <= bestResult.score || !canAttachAddonSubtitleViaSidecar(next)) {
                Log.i(MATCH_TAG, "next is not better; stopping search")
                break
            }
            best = next
            bestResult = r
            applySubtitleSyncPick(best, bestResult, switching = true)
        }
        // Nothing better found and the selected one is below the bar: leave it untouched.
    }
    submitTimingVerdict(best to bestResult)
    subtitleTimingMatchGeneration = embeddedSubtitleTimings.generation()
    // The player only reads ~1-2 min ahead; keep re-scoring the cached cues as more of the
    // file's timeline arrives until the pick reaches HIGH or the attempts run out.
    if (bestResult.score < SubtitleTimingMatcher.Options().highThreshold) scheduleSubtitleTimingRescore()
}

/** Downloads (or reuses) the cues of one addon subtitle and scores them against [reference]. */
private suspend fun PlayerRuntimeController.scoreCandidate(
    subtitle: Subtitle,
    reference: EmbeddedSubtitleTimingCollector.TrackTiming,
    fixedOffsetMs: Long?
): SubtitleTimingMatcher.Result {
    val key = addonSubtitleKey(subtitle)
    val cues = subtitleTimingCueCache[key] ?: downloadAndParse(subtitle)?.also { subtitleTimingCueCache[key] = it }
        ?: return SubtitleTimingMatcher.unavailable(reference.trackNumber)
    return withContext(Dispatchers.Default) { SubtitleTimingMatcher.score(cues, reference, fixedOffsetMs = fixedOffsetMs) }
}

/** Keeps or switches to [subtitle] and applies its offset; the single place sync touches the player. */
private fun PlayerRuntimeController.applySubtitleSyncPick(
    subtitle: Subtitle,
    result: SubtitleTimingMatcher.Result,
    switching: Boolean
) {
    val offset = result.offsetMs.toInt().coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    val current = _uiState.value
    // Re-runs (rescore) on the same pick with the same offset change nothing: no toast, no re-apply.
    if (!switching && subtitleSyncComparison.winnerKey == addonSubtitleKey(subtitle) &&
        abs(offset - current.subtitleDelayMs) < MIN_AUTO_OFFSET_MS && abs(result.scale - subtitleTimeScale) <= SubtitleTimingMatcher.DRIFT_EPSILON
    ) {
        return
    }
    if (switching) {
        autoSubtitleSelected = true
        subtitleTimingMatchApplied = true
        selectAddonSubtitle(subtitle)
        _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
    }
    applySubtitleTimeScale(result.scale)
    if (abs(offset) >= MIN_AUTO_OFFSET_MS || _uiState.value.subtitleDelayMs != 0) {
        setSubtitleDelayMs(offset, showOverlay = false)
        syncAppliedSubtitleDelay = offset != 0
    }
    subtitleSyncComparison = subtitleSyncComparison.copy(
        decided = true,
        winnerKey = addonSubtitleKey(subtitle),
        winnerScore = result.score.toDouble(),
        winnerMethod = "timing"
    )
    val n = _uiState.value.addonSubtitles.indexOfFirst { addonSubtitleKey(it) == addonSubtitleKey(subtitle) }.takeIf { it >= 0 }?.plus(1)
    Log.i(MATCH_TAG, "APPLY ${if (switching) "switch to" else "keep"} #${n ?: "?"} ${subtitle.addonName}/${subtitle.lang} id=${subtitle.id} ${result.scorePercent}% offset=${offset}ms")
    showSyncToast("Sync: #${n ?: subtitle.lang} ${if (switching) "selected" else "kept"} ${result.scorePercent}% • %+.2fs".format(offset / 1000.0))
}

/** Called when an embedded track (or no subtitle) becomes active: a sync offset never applies to it. */
internal fun PlayerRuntimeController.clearSyncAppliedSubtitleDelay(reason: String) {
    if (!syncAppliedSubtitleDelay) return
    syncAppliedSubtitleDelay = false
    if (_uiState.value.subtitleDelayMs == 0) return
    Log.i(MATCH_TAG, "offset reset to 0ms: $reason")
    setSubtitleDelayMs(0, showOverlay = false)
}

private fun PlayerRuntimeController.showSyncToast(message: String) {
    val ctx = context
    syncToastHandler.post { Toast.makeText(ctx, message, Toast.LENGTH_LONG).show() }
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
}
