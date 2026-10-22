package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSpsTest {

    // SPS of a 4K upscale coded 3840x2176 and cropped to 2160 by the conformance window.
    private val codedTallSps =
        "420101222000000300900000030000030099a001e02002207c4b6595529084645fd0c05a84880482000007d20000bb8010"

    // SPS of a letterboxed 4K Blu-ray encode coded 3840x1608.
    private val letterboxedSps =
        "420101022000000300900000030000030096a001e0200649365959a4930b9a848804db0800001f480002ee0308a4b2f00009896800017d7844"

    @Test
    fun `coded size includes the rows the conformance window crops`() {
        assertEquals(HevcSps.CodedSize(3840, 2176), HevcSps.parseCodedSize(hex(codedTallSps)))
        assertEquals(HevcSps.CodedSize(3840, 1608), HevcSps.parseCodedSize(hex(letterboxedSps)))
    }

    @Test
    fun `coded size is found in Annex-B initialization data`() {
        val vps = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0, 0, 0, 1) + hex(codedTallSps)
        val pps = byteArrayOf(0, 0, 0, 1, 0x44, 0x01, 0xC1.toByte())
        assertEquals(HevcSps.CodedSize(3840, 2176), HevcSps.codedSize(listOf(vps + sps + pps)))
    }

    @Test
    fun `no SPS gives no size`() {
        assertNull(HevcSps.codedSize(listOf(byteArrayOf(0, 0, 1, 0x40, 0x01))))
    }

    @Test
    fun `profile 8 coded taller than 2160 plays as HDR10`() {
        assertTrue(
            DolbyVisionDecodeLimits.exceedsDolbyVisionCodedHeight("dvhe.08.06", HevcSps.CodedSize(3840, 2176))
        )
    }

    @Test
    fun `profile 8 within 2160 keeps Dolby Vision`() {
        assertFalse(
            DolbyVisionDecodeLimits.exceedsDolbyVisionCodedHeight("dvhe.08.06", HevcSps.CodedSize(3840, 2160))
        )
        assertFalse(
            DolbyVisionDecodeLimits.exceedsDolbyVisionCodedHeight("dvhe.08.06", HevcSps.CodedSize(3840, 1608))
        )
    }

    @Test
    fun `profile 5 is never moved off Dolby Vision`() {
        assertFalse(
            DolbyVisionDecodeLimits.exceedsDolbyVisionCodedHeight("dvhe.05.06", HevcSps.CodedSize(3840, 2176))
        )
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
