package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IecOutputRoutePolicyTest {

    @Test
    fun wiredAndUnknownRoutes_keepTheIecTrack() {
        for (key in listOf(
            "type:hdmi|name:am9_pro",
            "type:hdmi_arc|name:soundbar",
            "type:hdmi_earc|name:receiver",
            "type:usb_device|name:dac",
            "type:usb_headset|name:headset",
            "type:usb_accessory|name:dock",
            "type:wired_headphones|name:wired_headphones",
            "type:type_13|name:spdif",
            "type:type_14|name:optical",
            "something_else",
            ""
        )) {
            assertTrue(key, IecOutputRoutePolicy.allowsCustomIec(key, iecProvenOnOutput = false))
        }
        assertTrue(IecOutputRoutePolicy.allowsCustomIec(null, iecProvenOnOutput = false))
    }

    @Test
    fun bluetoothRoutes_neverTakeTheIecTrack() {
        for (type in listOf("bluetooth_a2dp", "bluetooth_sco", "ble_headset", "ble_speaker", "ble_broadcast")) {
            val key = "type:$type|name:buds"
            assertFalse(key, IecOutputRoutePolicy.allowsCustomIec(key, iecProvenOnOutput = false))
            assertFalse(key, IecOutputRoutePolicy.allowsCustomIec(key, iecProvenOnOutput = true))
        }
    }

    @Test
    fun builtInSpeaker_takesTheIecTrackOnlyAfterTheProbeOpenedIt() {
        val key = "type:built_in_speaker|name:smart_tv_pro"
        assertFalse(IecOutputRoutePolicy.allowsCustomIec(key, iecProvenOnOutput = false))
        assertTrue(IecOutputRoutePolicy.allowsCustomIec(key, iecProvenOnOutput = true))
    }

    @Test
    fun routeType_readsTheTypePart() {
        assertEquals("built_in_speaker", IecOutputRoutePolicy.routeType("type:built_in_speaker|name:aftkrt"))
        assertEquals("hdmi", IecOutputRoutePolicy.routeType("type:hdmi"))
        assertNull(IecOutputRoutePolicy.routeType("name:aftkrt"))
        assertNull(IecOutputRoutePolicy.routeType(null))
    }
}
