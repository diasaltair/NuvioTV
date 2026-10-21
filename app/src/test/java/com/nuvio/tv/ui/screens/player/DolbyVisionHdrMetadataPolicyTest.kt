package com.nuvio.tv.ui.screens.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionHdrMetadataPolicyTest {

    @Test
    fun `profile 8 whose decoder dropped the static info is stripped`() {
        assertTrue(strip(codecs = "dvhe.08.06", outputNits = null))
    }

    @Test
    fun `profile 8 with nonsense mastering luminance is stripped`() {
        assertTrue(strip(codecs = "dvh1.08.06", outputNits = 0))
        assertTrue(strip(codecs = "dvh1.08.06", outputNits = 65_535))
    }

    @Test
    fun `profile 8 with valid metadata keeps Dolby Vision`() {
        assertFalse(strip(codecs = "dvhe.08.06", outputNits = 1_000))
        assertFalse(strip(codecs = "dvhe.08.06", outputNits = 4_000))
    }

    @Test
    fun `profile 5 is never stripped, it has no HDR10 base layer`() {
        assertFalse(strip(codecs = "dvhe.05.06", outputNits = null))
    }

    @Test
    fun `a stream without its own HDR metadata is left alone`() {
        assertFalse(strip(codecs = "dvhe.08.06", inputHasStaticInfo = false, outputNits = null))
    }

    @Test
    fun `non PQ output is left alone`() {
        assertFalse(strip(codecs = "dvhe.08.06", outputIsPq = false, outputNits = null))
    }

    @Test
    fun `max luminance is read from the CTA-861 static info layout`() {
        val buffer = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, 0)
        buffer.putShort(17, 1_000.toShort())
        assertEquals(1_000, DolbyVisionHdrMetadataPolicy.maxLuminanceNits(buffer))
        assertNull(DolbyVisionHdrMetadataPolicy.maxLuminanceNits(ByteBuffer.allocate(10)))
    }

    private fun strip(
        codecs: String,
        inputHasStaticInfo: Boolean = true,
        outputIsPq: Boolean = true,
        outputNits: Int?,
    ): Boolean = DolbyVisionHdrMetadataPolicy.shouldStripDolbyVision(
        codecs = codecs,
        inputHasHdrStaticInfo = inputHasStaticInfo,
        outputIsPq = outputIsPq,
        outputMaxLuminanceNits = outputNits,
    )
}
