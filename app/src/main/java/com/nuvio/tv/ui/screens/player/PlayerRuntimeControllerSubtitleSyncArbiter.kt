package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Arbitrates between the two embedded-timing sync methods that run in parallel:
 *
 *  - AutoSync (fork): Matroska Cues index fetched over HTTP range; works on ExoPlayer and MPV.
 *  - Timing matcher: real subtitle samples from the vendored extractor; ExoPlayer/libass only.
 *
 * Each method submits one verdict per stream (subtitle + offset + score in 0..1, or none).
 * Once both have reported — or one has and the other is not expected / times out — the
 * higher-confidence verdict is applied: switch subtitle if needed, then set the delay.
 */

private const val ARBITER_TAG = "SubtitleSyncArbiter"
/** How long to hold the first verdict for the second method before deciding alone. */
private const val ARBITER_WAIT_FOR_OTHER_MS = 45_000L
private const val MIN_APPLY_OFFSET_MS = 150

private val arbiterToastHandler = Handler(Looper.getMainLooper())

private fun PlayerRuntimeController.autoSyncExpected(): Boolean =
    AutoSyncPreferences.isEnabled(context) &&
        (currentStreamUrl.startsWith("http://", true) || currentStreamUrl.startsWith("https://", true))

private fun PlayerRuntimeController.timingExpected(): Boolean =
    currentPlayerSettingsForReport.subtitleStyle.autoMatchEmbeddedTiming && !isUsingMpvEngine()

/** Called after either method records its verdict. Decides now if possible, else waits. */
internal fun PlayerRuntimeController.scheduleSubtitleSyncArbitration() {
    val c = subtitleSyncComparison
    if (c.decided) {
        reviseSubtitleSyncDecision()
        return
    }
    val autoDone = c.autoSyncDone || !autoSyncExpected()
    val timingDone = c.timingDone || !timingExpected()
    if (autoDone && timingDone) {
        subtitleSyncArbiterJob?.cancel()
        subtitleSyncArbiterJob = null
        decideSubtitleSync(reason = "both-reported")
        return
    }
    if (subtitleSyncArbiterJob?.isActive == true) return
    val streamAtStart = currentStreamUrl
    subtitleSyncArbiterJob = scope.launch {
        delay(ARBITER_WAIT_FOR_OTHER_MS)
        if (currentStreamUrl != streamAtStart || subtitleSyncComparison.decided) return@launch
        decideSubtitleSync(reason = "timeout-waiting-other")
    }
}

private fun PlayerRuntimeController.decideSubtitleSync(reason: String) {
    val c = subtitleSyncComparison
    if (c.decided) return
    subtitleSyncComparison = c.copy(decided = true)

    data class Verdict(val method: String, val subtitle: Subtitle, val offsetMs: Int, val score: Double) {
        val needsOffset: Boolean get() = abs(offsetMs) >= SubtitleTimingMatcher.NO_OFFSET_TOLERANCE_MS
    }

    val auto = c.autoSyncSubtitle?.let { Verdict("autosync", it, c.autoSyncOffsetMs ?: 0, c.autoSyncScore ?: 0.0) }
    val timing = c.timingSubtitle?.let { Verdict("timing", it, (c.timingOffsetMs ?: 0L).toInt(), (c.timingScore ?: 0f).toDouble()) }

    val winner: Verdict? = when {
        auto == null && timing == null -> null
        auto == null -> timing
        timing == null -> auto
        // Same subtitle: keep the extractor offset (120 ms cue tolerance) over the Cues-index one (1.8 s).
        addonSubtitleKey(auto.subtitle) == addonSubtitleKey(timing.subtitle) ->
            if (timing.score >= 0.92) timing.copy(score = maxOf(auto.score, timing.score)) else auto
        timing.score > auto.score -> timing
        auto.score > timing.score -> auto
        // Tie: the one that fits as-is leaves no per-video delay behind.
        !timing.needsOffset && auto.needsOffset -> timing
        else -> auto
    }

    Log.i(
        ARBITER_TAG,
        "DECISION reason=$reason winner=${winner?.method ?: "none"} " +
            "pick=${winner?.let { "${it.subtitle.addonName}/${it.subtitle.lang}#${it.subtitle.id}" } ?: "none"} " +
            "offset=${winner?.offsetMs ?: "n/a"}ms score=${winner?.let { "%.3f".format(it.score) } ?: "n/a"} | " +
            "autosync=${auto?.let { "%.3f@%dms".format(it.score, it.offsetMs) } ?: "none"} " +
            "timing=${timing?.let { "%.3f@%dms".format(it.score, it.offsetMs) } ?: "none"}"
    )
    if (winner == null) {
        if (autoSyncExpected() || timingExpected()) {
            showArbiterToast("Sync: no reliable subtitle match", Toast.LENGTH_LONG)
        }
        return
    }
    subtitleSyncComparison = subtitleSyncComparison.copy(
        winnerKey = addonSubtitleKey(winner.subtitle),
        winnerScore = winner.score,
        winnerMethod = winner.method
    )

    val current = _uiState.value.selectedAddonSubtitle
    val switching = current == null || addonSubtitleKey(current) != addonSubtitleKey(winner.subtitle)
    if (switching) {
        if (isUserExplicitSubtitleSelection) {
            Log.i(ARBITER_TAG, "user picked a subtitle explicitly; not switching, offset only if same")
            return
        }
        autoSubtitleSelected = true
        subtitleTimingMatchApplied = true
        selectAddonSubtitle(winner.subtitle)
        _uiState.update { it.copy(selectedAddonSubtitle = winner.subtitle, selectedSubtitleTrackIndex = -1) }
    }
    val offset = winner.offsetMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    if (abs(offset) >= MIN_APPLY_OFFSET_MS || _uiState.value.subtitleDelayMs != 0) {
        setSubtitleDelayMs(offset, showOverlay = false)
    }

    val listNumber = _uiState.value.addonSubtitles
        .indexOfFirst { addonSubtitleKey(it) == addonSubtitleKey(winner.subtitle) }
        .takeIf { it >= 0 }?.plus(1)
    val label = listNumber?.let { "#$it" } ?: winner.subtitle.lang
    showArbiterToast(
        "Sync (${winner.method}): $label ${if (switching) "selected" else "kept"} • %+.2fs".format(offset / 1000.0),
        Toast.LENGTH_LONG
    )
}

private fun PlayerRuntimeController.showArbiterToast(message: String, duration: Int) {
    val ctx = context
    arbiterToastHandler.post { Toast.makeText(ctx, message, duration).show() }
}

/**
 * A later, stronger timing verdict (the extractor sees more of the file as playback goes on)
 * may refine the offset of the chosen subtitle or, when clearly better, replace it.
 */
private fun PlayerRuntimeController.reviseSubtitleSyncDecision() {
    val c = subtitleSyncComparison
    val subtitle = c.timingSubtitle ?: return
    val score = (c.timingScore ?: 0f).toDouble()
    val offset = (c.timingOffsetMs ?: 0L).toInt().coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    if (score < 0.92) return
    val key = addonSubtitleKey(subtitle)
    val current = _uiState.value.selectedAddonSubtitle
    val currentKey = current?.let(::addonSubtitleKey)
    val currentDelay = _uiState.value.subtitleDelayMs
    when {
        key == currentKey -> {
            if (abs(offset - currentDelay) < MIN_APPLY_OFFSET_MS) return
            Log.i(ARBITER_TAG, "REVISION same-subtitle offset ${currentDelay}ms -> ${offset}ms (timing score %.3f)".format(score))
            subtitleSyncComparison = c.copy(winnerKey = key, winnerScore = score, winnerMethod = "timing")
            setSubtitleDelayMs(offset, showOverlay = false)
            showArbiterToast("Sync (timing): offset refined • %+.2fs".format(offset / 1000.0), Toast.LENGTH_SHORT)
        }
        !isUserExplicitSubtitleSelection && score > c.winnerScore + 0.05 -> {
            Log.i(ARBITER_TAG, "REVISION switch ${c.winnerMethod}:${c.winnerKey} (%.3f) -> timing:$key (%.3f) offset=${offset}ms".format(c.winnerScore, score))
            subtitleSyncComparison = c.copy(winnerKey = key, winnerScore = score, winnerMethod = "timing")
            autoSubtitleSelected = true
            subtitleTimingMatchApplied = true
            selectAddonSubtitle(subtitle)
            _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
            if (abs(offset) >= MIN_APPLY_OFFSET_MS || currentDelay != 0) setSubtitleDelayMs(offset, showOverlay = false)
            val n = _uiState.value.addonSubtitles.indexOfFirst { addonSubtitleKey(it) == key }.takeIf { it >= 0 }?.plus(1)
            showArbiterToast("Sync (timing): #${n ?: "?"} selected • %+.2fs".format(offset / 1000.0), Toast.LENGTH_LONG)
        }
        else -> Log.i(ARBITER_TAG, "REVISION ignored: timing %.3f vs winner ${c.winnerMethod} %.3f".format(score, c.winnerScore))
    }
}
