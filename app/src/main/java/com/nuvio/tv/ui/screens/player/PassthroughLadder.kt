package com.nuvio.tv.ui.screens.player

import android.util.Log

/**
 * Process-wide memory of which platform passthrough rungs have failed for an encoding.
 *
 * The audio sink walks a ladder per format when tunneling is on: native tunneled track,
 * app-framed IEC61937 tunneled track, IEC61937 non-tunneled, native non-tunneled, and
 * finally decode to PCM. Every rung that opens but never plays (a HAL that accepts the
 * stream and then reopens it as PCM, or whose clock never starts) is marked here so the
 * next track selection skips it. Nothing is keyed by device: the answers come from the
 * HAL at runtime.
 */
internal object PassthroughLadder {
    private const val TAG = "PassthroughLadder"

    enum class Rung { NATIVE_TUNNEL, NATIVE_DIRECT }

    private val dead = HashMap<String, MutableSet<Rung>>()

    /** The extractor relabels DTS tracks after the first frame; treat the family as one key. */
    fun key(mime: String?): String = when {
        mime == null -> ""
        mime.startsWith("audio/vnd.dts") -> "audio/vnd.dts"
        else -> mime
    }

    @Synchronized
    fun isDead(mime: String?, rung: Rung): Boolean = dead[key(mime)]?.contains(rung) == true

    @Synchronized
    fun markDead(mime: String?, rung: Rung, reason: String) {
        val set = dead.getOrPut(key(mime)) { HashSet() }
        if (set.add(rung)) {
            Log.w(TAG, "${key(mime)}: $rung disabled for this process: $reason")
        }
    }

    @Synchronized
    fun describe(mime: String?): String = dead[key(mime)]?.toString() ?: "[]"
}
