@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun ContentDiscoverySettingsContent(
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToWatchParty: () -> Unit,
    onNavigateToHomeRows: () -> Unit,
    showPlugins: Boolean,
    showHomeRows: Boolean,
    initialFocusRequester: FocusRequester?
) {
    var serverPage by rememberSaveable { mutableStateOf<String?>(null) }
    var lastOpenedRow by rememberSaveable { mutableStateOf(ContentDiscoveryRow.ADDONS) }
    val hubServersRequester = remember { FocusRequester() }
    val serversRequester = remember { FocusRequester() }
    val serverRequester = remember { FocusRequester() }
    var returningToHub by remember { mutableStateOf(false) }
    // While one page replaces another, the pane itself holds the focus. Otherwise the focused row
    // leaves with the old page, the focus falls back to the category rail, and settings keeps it there.
    val paneRequester = remember { FocusRequester() }
    var holdingFocus by remember { mutableStateOf(false) }
    val openPage: (String?) -> Unit = { page ->
        holdingFocus = true
        if (!runCatching { paneRequester.requestFocus() }.isSuccess) holdingFocus = false
        if (page == null) returningToHub = true
        serverPage = page
    }

    BackHandler(enabled = serverPage != null) {
        openPage(if (serverPage == SERVERS_PAGE) null else SERVERS_PAGE)
    }

    LaunchedEffect(serverPage) {
        val requester = when (serverPage) {
            null -> hubServersRequester.takeIf { returningToHub }
            SERVERS_PAGE -> serversRequester
            else -> serverRequester
        }
        returningToHub = false
        if (requester != null) {
            repeat(FOCUS_ATTEMPTS) {
                withFrameNanos { }
                if (runCatching { requester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) {
                    holdingFocus = false
                    return@LaunchedEffect
                }
            }
        }
        holdingFocus = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { canFocus = holdingFocus }
            .focusRequester(paneRequester)
            .focusTarget()
    ) {
        when (val page = serverPage) {
            null -> ContentDiscoveryHub(
                onNavigateToAddons = { lastOpenedRow = ContentDiscoveryRow.ADDONS; onNavigateToAddons() },
                onNavigateToPlugins = { lastOpenedRow = ContentDiscoveryRow.PLUGINS; onNavigateToPlugins() },
                onNavigateToServers = { lastOpenedRow = ContentDiscoveryRow.SERVERS; openPage(SERVERS_PAGE) },
                onNavigateToWatchParty = { lastOpenedRow = ContentDiscoveryRow.WATCH_PARTY; onNavigateToWatchParty() },
                onNavigateToHomeRows = onNavigateToHomeRows,
                showPlugins = showPlugins,
                showHomeRows = showHomeRows,
                initialFocusRequester = initialFocusRequester,
                initialFocusRow = contentDiscoveryFocusRow(lastOpenedRow, showPlugins),
                serversFocusRequester = hubServersRequester
            )
            SERVERS_PAGE -> MediaServersSettingsContent(
                initialFocusRequester = serversRequester,
                onOpenServer = { openPage(it) }
            )
            else -> MediaServerSettingsContent(
                connectionId = page,
                initialFocusRequester = serverRequester,
                onOpenServer = { openPage(it) },
                onBack = { openPage(SERVERS_PAGE) }
            )
        }
    }
}

@Composable
private fun ContentDiscoveryHub(
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToHomeRows: () -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToWatchParty: () -> Unit,
    showPlugins: Boolean,
    showHomeRows: Boolean,
    initialFocusRequester: FocusRequester?,
    initialFocusRow: ContentDiscoveryRow,
    serversFocusRequester: FocusRequester
) {
    fun Modifier.initialFocus(row: ContentDiscoveryRow): Modifier =
        if (initialFocusRequester != null && row == initialFocusRow) focusRequester(initialFocusRequester) else this

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_content_discovery),
            subtitle = stringResource(R.string.settings_content_discovery_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            if (showHomeRows) {
                SettingsActionRow(
                    title = stringResource(R.string.settings_home_screen_rows),
                    subtitle = stringResource(R.string.settings_home_screen_rows_subtitle),
                    onClick = onNavigateToHomeRows,
                    leadingIcon = Icons.Default.Reorder,
                    modifier = Modifier
                )
            }
            SettingsActionRow(
                title = stringResource(R.string.addon_title),
                subtitle = stringResource(R.string.settings_content_discovery_addons_subtitle),
                onClick = onNavigateToAddons,
                leadingIcon = Icons.Default.Extension,
                modifier = Modifier.initialFocus(ContentDiscoveryRow.ADDONS)
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_media_servers),
                subtitle = stringResource(R.string.settings_media_servers_subtitle),
                onClick = onNavigateToServers,
                leadingIcon = Icons.Default.Dns,
                modifier = Modifier.focusRequester(serversFocusRequester).initialFocus(ContentDiscoveryRow.SERVERS)
            )
            if (showPlugins) {
                SettingsActionRow(
                    title = stringResource(R.string.plugin_title),
                    subtitle = stringResource(R.string.settings_content_discovery_plugins_subtitle),
                    onClick = onNavigateToPlugins,
                    leadingIcon = Icons.Default.Power,
                    modifier = Modifier.initialFocus(ContentDiscoveryRow.PLUGINS)
                )
            }
            SettingsActionRow(
                title = stringResource(R.string.party_title),
                subtitle = stringResource(R.string.party_settings_row_subtitle),
                onClick = onNavigateToWatchParty,
                leadingIcon = Icons.Default.Groups,
                modifier = Modifier.initialFocus(ContentDiscoveryRow.WATCH_PARTY)
            )
        }
    }
}

internal enum class ContentDiscoveryRow { ADDONS, SERVERS, PLUGINS, WATCH_PARTY }

/** The row that takes focus when the page comes back: the one the user last opened. */
internal fun contentDiscoveryFocusRow(lastOpened: ContentDiscoveryRow, showPlugins: Boolean): ContentDiscoveryRow =
    if (lastOpened == ContentDiscoveryRow.PLUGINS && !showPlugins) ContentDiscoveryRow.ADDONS else lastOpened

private const val SERVERS_PAGE = "servers"
private const val FOCUS_ATTEMPTS = 10
