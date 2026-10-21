package com.nuvio.tv.ui.screens.player

import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Some Dolby Vision Profile 8 releases (re-encodes and upscales) carry an RPU whose mastering
 * metadata is nonsense, e.g. 10,000,000 nits. The Amlogic DV decoder copies it into its output
 * format, the platform rejects it and leaves the static info zeroed, and the box then falls back
 * from HDR to SDR on the HDMI output a few seconds in. The same file decoded as HEVC keeps its
 * valid HDR10 metadata, so playing it with the DV layer stripped is the fix.
 *
 * Profile 8 always has an HDR10 base layer, so only it is stripped. Profile 5 has no such
 * layer and is never touched.
 */
internal object DolbyVisionHdrMetadataPolicy {

    /** Above the PQ ceiling, so no real mastering display can report it. */
    private const val MAX_VALID_MASTERING_NITS = 10_000

    // CTA-861.3 static metadata as MediaFormat.KEY_HDR_STATIC_INFO packs it: a descriptor id
    // byte, then little-endian uint16 values, max mastering luminance after the 8 chromaticities.
    private const val MAX_LUMINANCE_OFFSET = 17

    /**
     * True when the DV decoder output carries static info whose mastering luminance is zero or
     * out of range. [outputMaxLuminanceNits] is null when the output has no static info at all;
     * that says nothing about the stream, so it never qualifies.
     */
    fun shouldStripDolbyVision(
        codecs: String?,
        outputIsPq: Boolean,
        outputMaxLuminanceNits: Int?,
    ): Boolean {
        if (!isProfile8(codecs) || !outputIsPq || outputMaxLuminanceNits == null) return false
        return outputMaxLuminanceNits <= 0 || outputMaxLuminanceNits > MAX_VALID_MASTERING_NITS
    }

    internal fun isProfile8(codecs: String?): Boolean {
        val match = Regex("(?i)^(dvhe|dvh1)\\.(\\d+)").find(codecs?.trim().orEmpty()) ?: return false
        return match.groupValues[2].toIntOrNull() == 8
    }

    fun outputIsPq(mediaFormat: MediaFormat): Boolean =
        mediaFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
            mediaFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_ST2084

    fun outputMaxLuminanceNits(mediaFormat: MediaFormat): Int? {
        if (!mediaFormat.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) return null
        val buffer = mediaFormat.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO) ?: return null
        return maxLuminanceNits(buffer)
    }

    internal fun maxLuminanceNits(staticInfo: ByteBuffer): Int? {
        val bytes = staticInfo.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.limit() < MAX_LUMINANCE_OFFSET + 2) return null
        return bytes.getShort(MAX_LUMINANCE_OFFSET).toInt() and 0xFFFF
    }
}
