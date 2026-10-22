package com.nuvio.tv.core.player

import android.os.Build

/**
 * The Amlogic Dolby Vision core does not take frames coded taller than 2160 lines. Some 4K
 * encodes code 3840x2176 and crop to 2160 with the conformance window; on those the box never
 * engages DV and falls back to SDR on HDMI. The same stream plays as HDR10 through the plain
 * HEVC path, so a Profile 8 track (which always has an HDR10 base layer) is played that way.
 */
internal object DolbyVisionDecodeLimits {

    private const val MAX_AMLOGIC_DV_CODED_HEIGHT = 2160

    private val isAmlogic: Boolean by lazy {
        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
        socManufacturer.equals("Amlogic", ignoreCase = true) ||
            Build.HARDWARE.contains("amlogic", ignoreCase = true)
    }

    /** True when this device should play the DV track as its HDR10 base layer instead. */
    @JvmStatic
    fun shouldPlayAsHdr10(codecs: String?, initializationData: List<ByteArray>?): Boolean {
        if (!isAmlogic || initializationData == null) return false
        return exceedsDolbyVisionCodedHeight(codecs, HevcSps.codedSize(initializationData))
    }

    internal fun exceedsDolbyVisionCodedHeight(codecs: String?, codedSize: HevcSps.CodedSize?): Boolean {
        if (codedSize == null || !isProfile8(codecs)) return false
        return codedSize.height > MAX_AMLOGIC_DV_CODED_HEIGHT
    }

    private fun isProfile8(codecs: String?): Boolean {
        val match = Regex("(?i)^(dvhe|dvh1)\\.(\\d+)").find(codecs?.trim().orEmpty()) ?: return false
        return match.groupValues[2].toIntOrNull() == 8
    }
}
