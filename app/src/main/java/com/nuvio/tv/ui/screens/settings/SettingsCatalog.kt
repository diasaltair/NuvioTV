package com.nuvio.tv.ui.screens.settings

internal enum class SettingsRailGroup {
    FIND,
    YOU,
    LOOK,
    WATCH,
    EXTRAS,
    SERVICES,
    SYSTEM
}

internal enum class SettingsCategory(val group: SettingsRailGroup) {
    SEARCH(SettingsRailGroup.FIND),
    ACCOUNT(SettingsRailGroup.YOU),
    PROFILES(SettingsRailGroup.YOU),
    APPEARANCE(SettingsRailGroup.LOOK),
    LAYOUT(SettingsRailGroup.LOOK),
    SIDE_MENU(SettingsRailGroup.LOOK), // Live TV fork
    CONTENT_DISCOVERY(SettingsRailGroup.WATCH),
    PLAYBACK(SettingsRailGroup.WATCH),
    SCOUT(SettingsRailGroup.WATCH),
    LIVE_TV(SettingsRailGroup.EXTRAS), // Live TV fork
    ON_DEMAND(SettingsRailGroup.EXTRAS), // Live TV fork
    SPORTS(SettingsRailGroup.EXTRAS), // Live TV fork
    CALENDAR(SettingsRailGroup.EXTRAS), // Live TV fork
    VPN(SettingsRailGroup.EXTRAS),
    INTEGRATION(SettingsRailGroup.SERVICES),
    TRACKING(SettingsRailGroup.SERVICES),
    ADVANCED(SettingsRailGroup.SYSTEM),
    BACKUP(SettingsRailGroup.SYSTEM),
    REPORT(SettingsRailGroup.SYSTEM),
    ABOUT(SettingsRailGroup.SYSTEM),
    DEBUG(SettingsRailGroup.SYSTEM),
    EXPERIENCE(SettingsRailGroup.SYSTEM)
}

internal fun visibleSettingsCategories(
    isPrimaryProfile: Boolean,
    isEssentialMode: Boolean,
    isDebugBuild: Boolean,
    reportsAvailable: Boolean = false
): List<SettingsCategory> = SettingsCategory.entries.filter { category ->
    when (category) {
        SettingsCategory.ACCOUNT,
        SettingsCategory.PROFILES,
        SettingsCategory.BACKUP -> isPrimaryProfile
        SettingsCategory.DEBUG -> isDebugBuild && !isEssentialMode
        SettingsCategory.EXPERIENCE -> false
        SettingsCategory.SCOUT -> com.nuvio.tv.domain.model.ScoutFeature.available
        SettingsCategory.REPORT -> reportsAvailable
        else -> true
    }
}

internal fun List<SettingsCategory>.startsNewGroup(index: Int): Boolean =
    index > 0 && this[index - 1].group != this[index].group
