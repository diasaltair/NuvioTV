@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.settings.SettingsPageAtmosphere
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.screens.settings.settingsSubmenuSurface
import com.nuvio.tv.ui.screens.settings.isV2Settings
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.graphics.Color

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.LoadingIndicator
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import kotlinx.coroutines.launch

@Composable
fun CatalogOrderScreen(
    viewModel: CatalogOrderViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    // Live TV fork: adding and changing Live TV / Sports rows.
    val rowsSetup: com.nuvio.tv.livetv.home.HomeRowsSetupViewModel = hiltViewModel()
    var addingRow by remember { mutableStateOf(false) }
    var editingRowId by remember { mutableStateOf<String?>(null) }
    val liveRows by rowsSetup.rows.collectAsState()
    val emptyRowIds by rowsSetup.emptyRowIds.collectAsState()
    if (addingRow) {
        com.nuvio.tv.livetv.home.AddHomeRowDialog(viewModel = rowsSetup, onDismiss = { addingRow = false })
    }
    editingRowId?.let { id ->
        val row = liveRows.firstOrNull { it.id == id }
        if (row == null) editingRowId = null
        else com.nuvio.tv.livetv.home.HomeRowOptionsDialog(row = row, viewModel = rowsSetup, onDismiss = { editingRowId = null })
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BackHandler { onBackPress() }

    SettingsPageAtmosphere()
    SettingsStandaloneScaffold(
        title = stringResource(R.string.catalog_order_title),
        subtitle = stringResource(R.string.catalog_order_subtitle)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            item {
                Text(
                    text = stringResource(R.string.catalog_order_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = stringResource(R.string.catalog_order_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                SettingsToggleRow(
                    title = stringResource(R.string.catalog_order_follow_addons),
                    subtitle = stringResource(R.string.catalog_order_follow_addons_desc),
                    checked = uiState.followAddonsOrder,
                    onToggle = { viewModel.toggleFollowAddonsOrder(!uiState.followAddonsOrder) }
                )
                // Live TV fork: add a Live TV or Sports row.
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                AddLiveRowCard(onClick = { addingRow = true })
                // Live TV fork: rows from a playlist's group or category, named with the playlist.
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                val playlistNames by rowsSetup.playlistNames.collectAsState()
                com.nuvio.tv.ui.screens.settings.SettingsToggleRow(
                    title = stringResource(R.string.home_rows_playlist_names),
                    subtitle = stringResource(R.string.home_rows_playlist_names_hint),
                    checked = playlistNames,
                    onToggle = { rowsSetup.setPlaylistNames(!playlistNames) }
                )
            }

            when {
                uiState.isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = NuvioTheme.spacing.xl),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    }
                }

                uiState.items.isEmpty() -> {
                    item {
                        Text(
                            text = stringResource(R.string.catalog_order_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }

                else -> {
                    itemsIndexed(
                        items = uiState.items,
                        key = { _, item -> item.key }
                    ) { index, item ->
                        CatalogOrderCard(
                            item = item,
                            onMoveUp = {
                                viewModel.moveUp(item.key)
                                scope.launch {
                                    listState.animateScrollToItem((index - 1).coerceAtLeast(0))
                                }
                            },
                            onMoveDown = {
                                viewModel.moveDown(item.key)
                                scope.launch {
                                    listState.animateScrollToItem(
                                        (index + 1).coerceAtMost(uiState.items.lastIndex)
                                    )
                                }
                            },
                            onToggleEnabled = { viewModel.toggleCatalogEnabled(item.disableKey) },
                            onEdit = item.liveRow?.let { r -> { editingRowId = r.id } },
                            hiddenEmpty = item.liveRow?.id?.let { it in emptyRowIds } == true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogOrderCard(
    item: CatalogOrderItem,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: () -> Unit,
    onEdit: (() -> Unit)? = null,
    /** Live TV fork: a row left off Home for now, having nothing to show. */
    hiddenEmpty: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth().settingsSubmenuSurface(),
        colors = CardDefaults.cardColors(containerColor = if (isV2Settings()) Color.Transparent else NuvioTheme.colors.BackgroundCard),
        shape = RoundedCornerShape(NuvioTheme.radii.md)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val live = item.liveRow
                val typeText = when (live?.kind) {
                    com.nuvio.tv.livetv.home.HomeLiveRow.KIND_SPORTS -> stringResource(R.string.home_rows_type_sports)
                    com.nuvio.tv.livetv.home.HomeLiveRow.KIND_CHANNELS -> stringResource(R.string.home_rows_type_live)
                    com.nuvio.tv.livetv.home.HomeLiveRow.KIND_VOD -> stringResource(R.string.home_rows_type_vod)
                    else -> item.typeLabel.toDisplayTypeLabel()
                }
                Text(
                    text = if (ServerCatalog.isServerKey(item.key)) {
                        item.catalogName
                    } else {
                        "${item.catalogName} - ${item.typeLabel.toDisplayTypeLabel()}"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (item.isDisabled) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = when (live?.kind) {
                        com.nuvio.tv.livetv.home.HomeLiveRow.KIND_SPORTS -> stringResource(
                            R.string.home_rows_desc_sports,
                            stringResource(if (live.startingSoon) R.string.home_rows_pick_sports_all_hint else R.string.home_rows_type_sports),
                            live.limit
                        )
                        com.nuvio.tv.livetv.home.HomeLiveRow.KIND_CHANNELS -> stringResource(
                            R.string.home_rows_desc_channels, stringResource(R.string.home_rows_type_live), live.limit
                        )
                        com.nuvio.tv.livetv.home.HomeLiveRow.KIND_VOD -> stringResource(
                            R.string.home_rows_desc_vod, stringResource(R.string.home_rows_type_vod), live.limit
                        )
                        else -> item.addonName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                if (hiddenEmpty && !item.isDisabled) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.home_rows_hidden_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
                if (item.isDisabled) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.catalog_order_disabled_on_home),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Live TV fork: a Live TV or Sports row's options.
                if (onEdit != null) {
                    Button(
                        onClick = onEdit,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                        Text(text = stringResource(R.string.home_rows_edit))
                    }
                }
                CatalogOrderButton(
                    onClick = onMoveUp,
                    enabled = item.canMoveUp,

                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = stringResource(R.string.cd_move_up)
                    )
                }

                CatalogOrderButton(
                    onClick = onMoveDown,
                    enabled = item.canMoveDown,

                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = stringResource(R.string.cd_move_down)
                    )
                }

                CatalogOrderButton(
                    onClick = onToggleEnabled,

                ) {
                    Text(text = if (item.isDisabled) stringResource(R.string.catalog_order_enable) else stringResource(R.string.catalog_order_disable))
                }
            }
        }
    }
}

/** Live TV fork: the Add a row button at the top of the list. */
@Composable
private fun AddLiveRowCard(onClick: () -> Unit) {
    androidx.tv.material3.Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = NuvioTheme.colors.Primary)
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Column {
                Text(
                    text = stringResource(R.string.home_rows_add_button),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.home_rows_add_button_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}

private fun String.toDisplayTypeLabel(): String {
    return replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}

@Composable
private fun CatalogOrderButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    if (isV2Settings()) {
        NuvioActionPill(onClick = onClick, enabled = enabled, content = content)
    } else {
        Button(onClick = onClick, enabled = enabled,
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextSecondary,
                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                focusedContentColor = NuvioTheme.colors.TextPrimary
            ), content = content)
    }
}
