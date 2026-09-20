package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncDebugLog
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Thin bridge between the isolated AutoSync feature and NuvioTV's existing subtitle/player APIs. */
private val autoSyncToastHandler = Handler(Looper.getMainLooper())

private fun PlayerRuntimeController.showAutoSyncToast(
    message: String,
    duration: Int = Toast.LENGTH_SHORT,
) {
    autoSyncToastHandler.post {
        Toast.makeText(context, message, duration).show()
    }
}

internal fun PlayerRuntimeController.maybeRunAutomaticSubtitleSync(
    selectedSubtitle: Subtitle,
) {
    AutoSyncPreferences.ensureLoaded(context)
    if (!AutoSyncPreferences.isEnabled(context)) return
    if (selectedSubtitle.lang.isBlank()) return
    if (!currentStreamUrl.startsWith("http://", ignoreCase = true) &&
        !currentStreamUrl.startsWith("https://", ignoreCase = true)
    ) {
        return
    }

    automaticSubtitleSyncJob?.cancel()

    val sourceUrlAtStart = currentStreamUrl
    val sourceHeadersAtStart = currentHeaders.toMap()
    val selectedKey = addonSubtitleKey(selectedSubtitle)
    // AutoSync only aligns the selected subtitle (one addon request). Looking at other
    // entries is the timing matcher's job, one at a time, after this offset is known.
    val candidatesAtStart = listOf(selectedSubtitle)

    automaticSubtitleSyncJob = scope.launch {
        AutoSyncDebugLog.setEnabled(AutoSyncPreferences.isDebugLogsEnabled(context))
        try {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_TV start lang=${selectedSubtitle.lang} candidates=${candidatesAtStart.size}",
            )
            showAutoSyncToast("Auto Sync: checking embedded subtitles…")

            val recommendation = AutomaticSubtitleSync.findBestSubtitleRecommendation(
                sourceUrl = sourceUrlAtStart,
                sourceHeaders = sourceHeadersAtStart,
                selectedSubtitle = selectedSubtitle,
                candidates = candidatesAtStart,
                subtitleBodyLoader = { subtitle ->
                    downloadSubtitleBody(
                        subtitle.url,
                        subtitle.lang,
                        subtitle.headers,
                    )
                },
                onReferenceReady = {
                    showAutoSyncToast("Auto Sync: comparing same-language subtitles…")
                },
            ) ?: run {
                Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC_TV no reliable match")
                recordAutoSyncVerdict(subtitle = null, correctionMs = null, score = null)
                AutoSyncDebugLog.finishAndCopy(context, "no reliable match")
                showAutoSyncToast(
                    "Auto Sync: couldn't find a reliable subtitle match",
                    Toast.LENGTH_LONG,
                )
                return@launch
            }

            if (currentStreamUrl != sourceUrlAtStart) {
                AutoSyncDebugLog.finishAndCopy(context, "discarded: source changed")
                return@launch
            }

            val activeAddon = _uiState.value.selectedAddonSubtitle
            if (
                activeAddon != null &&
                addonSubtitleKey(activeAddon) != selectedKey &&
                addonSubtitleKey(activeAddon) != addonSubtitleKey(recommendation.subtitle)
            ) {
                Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC_TV discarded: subtitle changed")
                AutoSyncDebugLog.finishAndCopy(context, "discarded: subtitle changed")
                return@launch
            }

            val correctionMs = (
                recommendation.correctionMs / SUBTITLE_DELAY_STEP_MS.toDouble()
                ).roundToInt() * SUBTITLE_DELAY_STEP_MS

            Log.i(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_TV verdict addon=${recommendation.subtitle.id} " +
                    "correction=${correctionMs}ms score=${"%.4f".format(recommendation.score)} " +
                    "matches=${recommendation.matchedCues} reference=${recommendation.referenceKey}",
            )
            AutoSyncDebugLog.finishAndCopy(
                context,
                "verdict ${correctionMs}ms for ${recommendation.subtitle.id} (handed to arbiter)",
            )
            // Selection and delay are applied by the arbiter once the extractor-timing
            // matcher has also reported (or timed out); the higher-confidence verdict wins.
            recordAutoSyncVerdict(recommendation.subtitle, correctionMs, recommendation.score)
        } catch (cancel: CancellationException) {
            AutoSyncDebugLog.finishAndCopy(context, "cancelled")
            throw cancel
        } catch (error: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC_TV failed", error)
            recordAutoSyncVerdict(subtitle = null, correctionMs = null, score = null)
            AutoSyncDebugLog.error(error) { "bridge failed" }
            AutoSyncDebugLog.finishAndCopy(context, "failed")
            showAutoSyncToast("Auto Sync: failed", Toast.LENGTH_LONG)
        }
    }
}

private fun PlayerRuntimeController.recordAutoSyncVerdict(subtitle: Subtitle?, correctionMs: Int?, score: Double?) {
    val key = subtitle?.let(::addonSubtitleKey)
    subtitleSyncComparison = subtitleSyncComparison.copy(
        autoSyncDone = true,
        autoSyncSubtitle = subtitle,
        autoSyncKey = key,
        autoSyncLabel = subtitle?.let { "${it.addonName}/${it.lang}#${it.id}" },
        autoSyncOffsetMs = correctionMs,
        autoSyncScore = score
    )
    _uiState.update { it.copy(autoSyncPickKey = key, autoSyncPickOffsetMs = correctionMs) }
    logSubtitleSyncComparison()
    // On ExoPlayer the timing matcher consumes this offset and applies the decision itself;
    // only when it cannot run (MPV) does the arbiter apply the AutoSync verdict alone.
    if (!timingExpected()) scheduleSubtitleSyncArbitration()
}
