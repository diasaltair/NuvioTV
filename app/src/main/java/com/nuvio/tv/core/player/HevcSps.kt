package com.nuvio.tv.core.player

/**
 * Reads the coded picture size from an HEVC SPS. The coded size is the decoded frame before
 * the conformance window crop, e.g. 3840x2176 for a stream displayed at 3840x2160.
 */
internal object HevcSps {

    data class CodedSize(val width: Int, val height: Int)

    private const val NAL_TYPE_SPS = 33

    /** The coded size from the first SPS in [initializationData] (Annex-B NAL units). */
    fun codedSize(initializationData: List<ByteArray>): CodedSize? {
        for (buffer in initializationData) {
            val sps = findSps(buffer) ?: continue
            return parseCodedSize(sps)
        }
        return null
    }

    private fun findSps(buffer: ByteArray): ByteArray? {
        var i = 0
        while (i + 3 < buffer.size) {
            val startCode = buffer[i].toInt() == 0 && buffer[i + 1].toInt() == 0 && buffer[i + 2].toInt() == 1
            if (!startCode) {
                i++
                continue
            }
            val header = i + 3
            if ((buffer[header].toInt() shr 1) and 0x3F == NAL_TYPE_SPS) {
                var end = header + 1
                while (end + 2 < buffer.size &&
                    !(buffer[end].toInt() == 0 && buffer[end + 1].toInt() == 0 && buffer[end + 2].toInt() == 1)
                ) {
                    end++
                }
                if (end + 2 >= buffer.size) end = buffer.size
                return buffer.copyOfRange(header, end)
            }
            i = header
        }
        return null
    }

    /** [sps] is one SPS NAL unit starting at its two-byte header, without a start code. */
    internal fun parseCodedSize(sps: ByteArray): CodedSize? = runCatching {
        val bits = BitReader(unescape(sps))
        bits.skip(16) // NAL unit header
        bits.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = bits.read(3)
        bits.skip(1) // sps_temporal_id_nesting_flag
        skipProfileTierLevel(bits, maxSubLayersMinus1)
        bits.readUe() // sps_seq_parameter_set_id
        if (bits.readUe() == 3) bits.skip(1) // chroma_format_idc, separate_colour_plane_flag
        CodedSize(width = bits.readUe(), height = bits.readUe())
    }.getOrNull()

    private fun skipProfileTierLevel(bits: BitReader, maxSubLayersMinus1: Int) {
        bits.skip(88 + 8) // general profile fields and general_level_idc
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = bits.read(1) == 1
            levelPresent[i] = bits.read(1) == 1
        }
        if (maxSubLayersMinus1 > 0) bits.skip(2 * (8 - maxSubLayersMinus1))
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) bits.skip(88)
            if (levelPresent[i]) bits.skip(8)
        }
    }

    /** Drops the emulation prevention byte from every 00 00 03 sequence. */
    private fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size)
        var length = 0
        var zeros = 0
        for (b in nal) {
            val value = b.toInt() and 0xFF
            if (zeros >= 2 && value == 3) {
                zeros = 0
                continue
            }
            zeros = if (value == 0) zeros + 1 else 0
            out[length++] = b
        }
        return out.copyOf(length)
    }

    private class BitReader(private val data: ByteArray) {
        private var position = 0

        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = data[position shr 3].toInt()
                value = (value shl 1) or ((byte shr (7 - (position and 7))) and 1)
                position++
            }
            return value
        }

        fun skip(count: Int) {
            position += count
            require(position <= data.size * 8)
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (read(1) == 0) {
                leadingZeros++
                require(leadingZeros < 32)
            }
            return (1 shl leadingZeros) - 1 + read(leadingZeros)
        }
    }
}
