package com.nuvio.tv.ui.screens.player

import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Feed accounting for [DtsHdIecPassthroughAudioSink]. The Mi Box freeze starts with the app
 * going quiet ~1 s (one AudioTrack buffer) before AudioFlinger drains, so every window in
 * which nothing reached the track is logged with enough counters to tell the three causes
 * apart: the renderer stopped calling handleBuffer, frames were dropped by the packer, or
 * writes were refused.
 */
internal class IecFeedDiagnostics(private val tag: String) {

    private var windowStartMs = 0L
    private var handleCalls = 0
    private var inBytes = 0L
    private var bursts = 0
    private var dropNoSync = 0
    private var dropOther = 0
    private var writeCalls = 0
    private var writeZero = 0
    private var writtenBytes = 0L
    private var pendingBlocked = 0
    private var lastDropHead = ""
    private var lastHandleMs = 0L
    private var lastHandlePtsUs = 0L
    private var maxHandleGapMs = 0L

    private var gapWindows = 0
    private var summaryStartMs = 0L
    private var summaryWrittenBytes = 0L
    private var summaryDrops = 0

    fun onHandle(bytes: Int, ptsUs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (lastHandleMs != 0L) maxHandleGapMs = maxOf(maxHandleGapMs, now - lastHandleMs)
        lastHandleMs = now
        lastHandlePtsUs = ptsUs
        handleCalls++
        inBytes += bytes
    }

    fun onBurst() { bursts++ }

    fun onDrop(noSync: Boolean, head: String) {
        if (noSync) dropNoSync++ else dropOther++
        summaryDrops++
        lastDropHead = head
    }

    fun onWrite(written: Int) {
        writeCalls++
        if (written == 0) writeZero++
        if (written > 0) writtenBytes += written
    }

    fun onPendingBlocked() { pendingBlocked++ }

    /** Called from every handleBuffer and position query, so it also runs when no input arrives. */
    fun tick(track: AudioTrack?, playing: Boolean, writtenFrames: Long, headFrames: Long) {
        val now = SystemClock.elapsedRealtime()
        if (windowStartMs == 0L) {
            windowStartMs = now
            summaryStartMs = now
            return
        }
        if (now - windowStartMs < WINDOW_MS) return
        val trackPlaying = track?.playState == AudioTrack.PLAYSTATE_PLAYING
        if (playing && trackPlaying && writtenBytes == 0L) {
            gapWindows++
            val underruns = if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) track.underrunCount else -1
            Log.w(
                tag,
                "FEED_GAP ${now - windowStartMs}ms: handle=$handleCalls in=${inBytes}B " +
                    "sinceHandle=${if (lastHandleMs == 0L) -1 else now - lastHandleMs}ms maxHandleGap=${maxHandleGapMs}ms " +
                    "lastPts=${lastHandlePtsUs / 1000}ms bursts=$bursts dropNoSync=$dropNoSync dropOther=$dropOther " +
                    "lastDrop=[$lastDropHead] writes=$writeCalls zero=$writeZero pendingBlocked=$pendingBlocked " +
                    "fill=${writtenFrames - headFrames}f head=$headFrames written=$writtenFrames underruns=$underruns"
            )
        } else if (gapWindows > 0) {
            Log.w(tag, "FEED_RESUMED after $gapWindows gap windows: wrote=${writtenBytes}B handle=$handleCalls")
            gapWindows = 0
        }
        summaryWrittenBytes += writtenBytes
        if (now - summaryStartMs >= SUMMARY_MS) {
            Log.d(
                tag,
                "FEED ok ${now - summaryStartMs}ms: wrote=${summaryWrittenBytes}B drops=$summaryDrops " +
                    "fill=${writtenFrames - headFrames}f playing=$playing trackPlaying=$trackPlaying"
            )
            summaryStartMs = now
            summaryWrittenBytes = 0
            summaryDrops = 0
        }
        windowStartMs = now
        handleCalls = 0
        inBytes = 0
        bursts = 0
        dropNoSync = 0
        dropOther = 0
        writeCalls = 0
        writeZero = 0
        writtenBytes = 0
        pendingBlocked = 0
        maxHandleGapMs = 0
    }

    private companion object {
        const val WINDOW_MS = 250L
        const val SUMMARY_MS = 10_000L
    }
}
