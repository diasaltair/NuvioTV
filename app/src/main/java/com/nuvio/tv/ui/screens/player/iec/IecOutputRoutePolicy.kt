package com.nuvio.tv.ui.screens.player.iec

/**
 * Whether the own IEC 61937 track is worth trying on the media output named by [routeKey]
 * (an AudioOutputRouteDetector key). Bluetooth never carries it, and a built-in speaker only
 * when the probe already opened IEC 61937 on this output.
 */
internal object IecOutputRoutePolicy {
    private val BLUETOOTH_TYPES = setOf(
        "bluetooth_a2dp",
        "bluetooth_sco",
        "ble_headset",
        "ble_speaker",
        "ble_broadcast"
    )

    fun routeType(routeKey: String?): String? =
        routeKey?.substringBefore('|')?.takeIf { it.startsWith("type:") }?.removePrefix("type:")

    fun allowsCustomIec(routeKey: String?, iecProvenOnOutput: Boolean): Boolean =
        when (routeType(routeKey)) {
            in BLUETOOTH_TYPES -> false
            "built_in_speaker" -> iecProvenOnOutput
            else -> true
        }
}
