package com.nuvio.tv.livetv.branding

import com.nuvio.tv.R

/**
 * "Nuvio + IPTV" branding. The fork's own logo images sit next to Nuvio's (they don't replace
 * them), so Nuvio's artwork stays untouched and future Nuvio updates merge cleanly.
 */
object LiveTvBranding {
    const val APP_NAME = "Nuvio + IPTV"
    const val TAGLINE = "Live TV  ·  On Demand  ·  Streaming"

    /** The "Nuvio + IPTV" version of a theme's Nuvio wordmark. */
    fun iptvWordmark(@Suppress("UNUSED_PARAMETER") nuvioWordmark: Int): Int = R.drawable.livetv_wordmark_iptv
}
