package com.nuvio.tv.ui.screens.detail

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpisodeOptionsOverlayStyle
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.screens.ambient.AmbientDialogEffect
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.BlurTransformation
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.ui.util.toAbsoluteAlignment
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import kotlinx.coroutines.launch
import java.util.Locale

private data class EpisodeOverlayAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

internal enum class EpisodeOverlayActionKind {
    TOGGLE_WATCHED,
    TOGGLE_SEASON_WATCHED,
    MARK_PREVIOUS_WATCHED,
    PLAY,
    OPEN_COMMENTS,
    PLAY_MANUALLY,
    START_FROM_BEGINNING
}

internal fun episodeOverlayActionKinds(
    hasPreviousEpisodes: Boolean,
    showOpenEpisodeComments: Boolean,
    showPlayManually: Boolean,
    hasProgress: Boolean,
    isPlayEnabled: Boolean,
    showWatchedActions: Boolean = true
): List<EpisodeOverlayActionKind> = buildList {
    if (showWatchedActions) {
        add(EpisodeOverlayActionKind.TOGGLE_WATCHED)
        add(EpisodeOverlayActionKind.TOGGLE_SEASON_WATCHED)
        if (hasPreviousEpisodes) add(EpisodeOverlayActionKind.MARK_PREVIOUS_WATCHED)
    }
    add(EpisodeOverlayActionKind.PLAY)
    if (showOpenEpisodeComments) add(EpisodeOverlayActionKind.OPEN_COMMENTS)
    if (showPlayManually && isPlayEnabled) add(EpisodeOverlayActionKind.PLAY_MANUALLY)
    if (hasProgress && isPlayEnabled) add(EpisodeOverlayActionKind.START_FROM_BEGINNING)
}

internal fun isEpisodeOverlayActionEnabled(
    kind: EpisodeOverlayActionKind,
    isPending: Boolean,
    isPlayEnabled: Boolean,
    isCurrentlyPlaying: Boolean = false
): Boolean = when (kind) {
    EpisodeOverlayActionKind.TOGGLE_WATCHED -> !isPending
    EpisodeOverlayActionKind.PLAY -> isPlayEnabled && !isCurrentlyPlaying
    else -> true
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun EpisodeOptionsOverlay(
    episode: Video,
    imdbRating: Double? = null,
    isWatched: Boolean,
    blurUnwatchedEpisodes: Boolean = false,
    style: EpisodeOptionsOverlayStyle = EpisodeOptionsOverlayStyle.ARTWORK,
    isPending: Boolean,
    isSeasonFullyWatched: Boolean = false,
    hasPreviousEpisodes: Boolean = false,
    hasProgress: Boolean = false,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    isPlayEnabled: Boolean = true,
    onStartFromBeginning: () -> Unit = {},
    onOpenEpisodeComments: () -> Unit = {},
    showOpenEpisodeComments: Boolean = false,
    onPlayManually: () -> Unit = {},
    showPlayManually: Boolean = false,
    onToggleWatched: () -> Unit,
    onMarkSeasonWatched: () -> Unit = {},
    onMarkSeasonUnwatched: () -> Unit = {},
    onMarkPreviousEpisodesWatched: () -> Unit = {},
    showWatchedActions: Boolean = true,
    isCurrentlyPlaying: Boolean = false,
    onNavigateEpisode: ((Int) -> Unit)? = null,
    /** Live TV fork: more lines after Play (Record on your channel, Live TV options). */
    extraActions: List<Pair<String, () -> Unit>> = emptyList()
) {
    val context = LocalContext.current
    val activityDensity = LocalDensity.current
    val displayMetrics = LocalResources.current.displayMetrics
    val windowSize = LocalWindowInfo.current.containerSize
    val windowWidthPx = windowSize.width.takeIf { it > 0 } ?: displayMetrics.widthPixels
    val windowHeightPx = windowSize.height.takeIf { it > 0 } ?: displayMetrics.heightPixels
    val overlayColor = NuvioTheme.colors.Background
    val elevatedColor = NuvioTheme.colors.BackgroundElevated
    val textColor = NuvioTheme.colors.TextPrimary
    val mutedTextColor = textColor.copy(alpha = 0.72f)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val primaryFocusRequester = remember { FocusRequester() }
    val detailsFocusRequester = remember { FocusRequester() }
    val detailsScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val title = episode.title.localizeEpisodeTitle(context)
    val description = episode.overview?.trim().orEmpty()
    val titleStyle = episodeOverlayTitleStyle(title.length)
    val descriptionStyle = episodeOverlayDescriptionStyle(description.length)
    val isNoneStyle = !shouldShowEpisodeOverlayBackdrop(style)
    val isCompactLayout = with(activityDensity) {
        windowWidthPx.toDp() < 1200.dp || windowHeightPx.toDp() < 700.dp
    }
    val horizontalPadding = if (isNoneStyle || !isCompactLayout) 64.dp else 32.dp
    val verticalPadding = if (isNoneStyle || !isCompactLayout) 48.dp else 24.dp
    val contentSpacing = if (isNoneStyle || !isCompactLayout) 72.dp else 40.dp
    val actionsWidth = if (isNoneStyle || !isCompactLayout) 360.dp else 320.dp
    val blurBackdrop = shouldBlurEpisodeOverlayBackdrop(
        style = style,
        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
        isWatched = isWatched
    )
    val thumbnailUrl = remember(episode.thumbnail, style, blurBackdrop) {
        when {
            isNoneStyle -> null
            blurBackdrop -> episode.thumbnail?.takeIf { it.isNotBlank() }
            else -> episodeOverlayBackdropUrl(episode.thumbnail)
        }
    }
    val backdropWidthPx = windowWidthPx
    val backdropHeightPx = windowHeightPx
    val thumbnailRequest = remember(
        context,
        thumbnailUrl,
        backdropWidthPx,
        backdropHeightPx,
        blurBackdrop
    ) {
        thumbnailUrl?.let { url ->
            episodeOverlayBackdropRequest(
                context,
                url,
                backdropWidthPx,
                backdropHeightPx,
                blur = blurBackdrop
            )
        }
    }
    val noneBackgroundBrush = remember(overlayColor, elevatedColor) {
        Brush.horizontalGradient(
            colors = listOf(
                overlayColor,
                lerp(overlayColor, elevatedColor, 0.3f),
                lerp(overlayColor, elevatedColor, 0.6f)
            )
        )
    }
    val ratingLabel = remember(imdbRating) {
        imdbRating?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }
    }
    val episodeLabel = when {
        episode.season != null && episode.episode != null -> {
            stringResource(R.string.season_episode_format, episode.season, episode.episode)
        }
        episode.episode != null -> {
            "${stringResource(R.string.episodes_episode)} ${episode.episode}"
        }
        else -> stringResource(R.string.episodes_dialog_subtitle)
    }
    val actions = episodeOverlayActionKinds(
        hasPreviousEpisodes = hasPreviousEpisodes,
        showOpenEpisodeComments = showOpenEpisodeComments,
        showPlayManually = showPlayManually,
        hasProgress = hasProgress,
        isPlayEnabled = isPlayEnabled,
        showWatchedActions = showWatchedActions
    ).map { kind ->
        EpisodeOverlayAction(
            label = episodeOverlayActionLabel(kind, isWatched, isSeasonFullyWatched, isPlayEnabled, isCurrentlyPlaying),
            enabled = isEpisodeOverlayActionEnabled(kind, isPending, isPlayEnabled, isCurrentlyPlaying),
            onClick = when (kind) {
                EpisodeOverlayActionKind.TOGGLE_WATCHED -> onToggleWatched
                EpisodeOverlayActionKind.TOGGLE_SEASON_WATCHED ->
                    if (isSeasonFullyWatched) onMarkSeasonUnwatched else onMarkSeasonWatched
                EpisodeOverlayActionKind.MARK_PREVIOUS_WATCHED -> onMarkPreviousEpisodesWatched
                EpisodeOverlayActionKind.PLAY -> onPlay
                EpisodeOverlayActionKind.OPEN_COMMENTS -> onOpenEpisodeComments
                EpisodeOverlayActionKind.PLAY_MANUALLY -> onPlayManually
                EpisodeOverlayActionKind.START_FROM_BEGINNING -> onStartFromBeginning
            }
        )
    } + extraActions.map { (label, onClick) -> EpisodeOverlayAction(label = label, onClick = onClick) } // Live TV fork
    val initialActionIndex = actions.indexOfFirst { it.enabled }.coerceAtLeast(0)
    var acceptsSelectKey by remember { mutableStateOf(false) }

    val hasEnabledAction = actions.any { it.enabled }
    var descriptionFocused by remember { mutableStateOf(false) }

    LaunchedEffect(episode.id) {
        detailsScrollState.scrollTo(0)
        if (descriptionFocused) return@LaunchedEffect
        when {
            hasEnabledAction -> primaryFocusRequester.requestFocus()
            !isNoneStyle -> detailsFocusRequester.requestFocus()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        AmbientDialogEffect(onDismiss)
        CompositionLocalProvider(LocalDensity provides activityDensity) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isNoneStyle) {
                            Modifier.background(noneBackgroundBrush)
                        } else {
                            Modifier.background(overlayColor)
                        }
                    )
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                                acceptsSelectKey = true
                            }
                            if (!acceptsSelectKey) {
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
            ) {
                if (thumbnailRequest != null) {
                    AsyncImage(
                        model = thumbnailRequest,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        filterQuality = FilterQuality.High
                    )
                }
                if (!isNoneStyle) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithCache {
                                val brush = Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0.00f to overlayColor,
                                        0.10f to overlayColor.copy(alpha = 0.97f),
                                        0.20f to overlayColor.copy(alpha = 0.93f),
                                        0.30f to overlayColor.copy(alpha = 0.87f),
                                        0.40f to overlayColor.copy(alpha = 0.78f),
                                        0.50f to overlayColor.copy(alpha = 0.67f),
                                        0.60f to overlayColor.copy(alpha = 0.55f),
                                        0.70f to overlayColor.copy(alpha = 0.45f),
                                        0.80f to overlayColor.copy(alpha = 0.38f),
                                        0.90f to overlayColor.copy(alpha = 0.34f),
                                        1.00f to overlayColor.copy(alpha = 0.30f)
                                    ),
                                    startX = if (isRtl) size.width else 0f,
                                    endX = if (isRtl) 0f else size.width
                                )
                                onDrawBehind {
                                    drawRect(brush = brush)
                                }
                            }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(contentSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (isNoneStyle) {
                                    Modifier.padding(end = 24.dp)
                                } else {
                                    Modifier
                                        .fillMaxHeight()
                                        .verticalScroll(detailsScrollState)
                                        .focusRequester(detailsFocusRequester)
                                        .focusProperties {
                                            if (isRtl) {
                                                left = primaryFocusRequester
                                            } else {
                                                right = primaryFocusRequester
                                            }
                                        }
                                        .focusable()
                                        .onFocusChanged { descriptionFocused = it.isFocused }
                                        .semantics { contentDescription = title }
                                        .onPreviewKeyEvent { event ->
                                            val step = episodeStepForKey(event.nativeKeyEvent.keyCode, isRtl)
                                            when {
                                                event.type != KeyEventType.KeyDown -> false
                                                onNavigateEpisode != null && (step == -1 || (step == 1 && !hasEnabledAction)) -> {
                                                    onNavigateEpisode(step)
                                                    true
                                                }
                                                event.key == Key.DirectionDown && detailsScrollState.value < detailsScrollState.maxValue -> {
                                                    coroutineScope.launch {
                                                        detailsScrollState.animateScrollTo(
                                                            (detailsScrollState.value + 260)
                                                                .coerceAtMost(detailsScrollState.maxValue)
                                                        )
                                                    }
                                                    true
                                                }
                                                event.key == Key.DirectionUp && detailsScrollState.value > 0 -> {
                                                    coroutineScope.launch {
                                                        detailsScrollState.animateScrollTo(
                                                            (detailsScrollState.value - 260).coerceAtLeast(0)
                                                        )
                                                    }
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                }
                            ),
                        verticalArrangement = if (isNoneStyle) {
                            Arrangement.spacedBy(NuvioTheme.spacing.lg)
                        } else {
                            Arrangement.spacedBy(NuvioTheme.spacing.lg, Alignment.CenterVertically)
                        }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = episodeLabel,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = NuvioTheme.colors.Primary
                            )

                            ratingLabel?.let { rating ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ImdbRatingSourceLabel(
                                        logoModifier = Modifier.size(30.dp),
                                        textStyle = MaterialTheme.typography.titleMedium,
                                        textColor = mutedTextColor
                                    )
                                    Text(
                                        text = rating,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = mutedTextColor
                                    )
                                }
                            }
                        }

                        Text(
                            text = title,
                            modifier = Modifier.align(title.contentTextDirection().toAbsoluteAlignment()),
                            style = if (isNoneStyle) MaterialTheme.typography.displayLarge else titleStyle,
                            color = textColor,
                            maxLines = if (isNoneStyle) 3 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (description.isNotBlank()) {
                            Text(
                                text = description,
                                style = (if (isNoneStyle) {
                                    MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal)
                                } else {
                                    descriptionStyle
                                }).copy(textDirection = description.contentTextDirection()),
                                color = mutedTextColor,
                                maxLines = if (isNoneStyle) 8 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .width(actionsWidth)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown || onNavigateEpisode == null) {
                                    return@onPreviewKeyEvent false
                                }
                                val step = episodeStepForKey(event.nativeKeyEvent.keyCode, isRtl)
                                if (step == 1 || (step == -1 && isNoneStyle)) {
                                    onNavigateEpisode(step)
                                    true
                                } else {
                                    false
                                }
                            }
                            .focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                    ) {
                        actions.forEachIndexed { index, action ->
                            EpisodeOverlayActionButton(
                                action = action,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isNoneStyle) {
                                            Modifier
                                        } else {
                                            Modifier.focusProperties {
                                                if (isRtl) {
                                                    right = detailsFocusRequester
                                                } else {
                                                    left = detailsFocusRequester
                                                }
                                            }
                                        }
                                    )
                                    .then(
                                        if (index == initialActionIndex) {
                                            Modifier.focusRequester(primaryFocusRequester)
                                        } else {
                                            Modifier
                                        }
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeOverlayActionButton(
    action: EpisodeOverlayAction,
    modifier: Modifier = Modifier
) {
    if (LocalV2Appearance.current != null) {
        NuvioActionPill(
            onClick = action.onClick,
            modifier = modifier,
            enabled = action.enabled,
            neutralBackdrop = true
        ) {
            Text(action.label)
        }
        return
    }
    Button(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            focusedContentColor = NuvioTheme.colors.OnSecondary
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = CircleShape
            )
        )
    ) {
        Text(action.label)
    }
}

@Composable
private fun episodeOverlayActionLabel(
    kind: EpisodeOverlayActionKind,
    isWatched: Boolean,
    isSeasonFullyWatched: Boolean,
    isPlayEnabled: Boolean,
    isCurrentlyPlaying: Boolean
): String = when (kind) {
    EpisodeOverlayActionKind.TOGGLE_WATCHED -> stringResource(
        if (isWatched) R.string.episodes_mark_unwatched else R.string.episodes_mark_watched
    )
    EpisodeOverlayActionKind.TOGGLE_SEASON_WATCHED -> stringResource(
        if (isSeasonFullyWatched) R.string.episodes_mark_season_unwatched else R.string.episodes_mark_season_watched
    )
    EpisodeOverlayActionKind.MARK_PREVIOUS_WATCHED -> stringResource(R.string.episodes_mark_previous_watched)
    EpisodeOverlayActionKind.PLAY -> stringResource(
        when {
            isCurrentlyPlaying -> R.string.sources_playing
            isPlayEnabled -> R.string.episodes_play
            else -> R.string.playback_unavailable
        }
    )
    EpisodeOverlayActionKind.OPEN_COMMENTS -> stringResource(R.string.episodes_open_comments)
    EpisodeOverlayActionKind.PLAY_MANUALLY -> stringResource(R.string.play_manually)
    EpisodeOverlayActionKind.START_FROM_BEGINNING -> stringResource(R.string.cw_action_start_from_beginning)
}

@Composable
private fun episodeOverlayTitleStyle(length: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when {
        length <= 40 -> typography.displayLarge
        length <= 75 -> typography.displayMedium
        length <= 120 -> typography.headlineLarge
        else -> typography.headlineMedium
    }
}

@Composable
private fun episodeOverlayDescriptionStyle(length: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when {
        length <= 240 -> typography.headlineMedium.copy(fontWeight = FontWeight.Normal)
        length <= 420 -> typography.titleLarge.copy(fontWeight = FontWeight.Normal)
        length <= 700 -> typography.titleMedium.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 18.sp,
            lineHeight = 24.sp
        )
        else -> typography.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 22.sp
        )
    }
}

internal fun episodeStepForKey(keyCode: Int, isRtl: Boolean): Int = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (isRtl) -1 else 1
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (isRtl) 1 else -1
    else -> 0
}

internal fun adjacentEpisode(episodes: List<Video>, current: Video, step: Int): Video? {
    val index = episodes.indexOfFirst { it.id == current.id }
    if (index < 0) return null
    return episodes.getOrNull(index + step)
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

internal fun shouldShowEpisodeOverlayBackdrop(style: EpisodeOptionsOverlayStyle): Boolean {
    return style != EpisodeOptionsOverlayStyle.NONE
}

internal fun shouldBlurEpisodeOverlayBackdrop(
    style: EpisodeOptionsOverlayStyle,
    blurUnwatchedEpisodes: Boolean,
    isWatched: Boolean
): Boolean {
    return style == EpisodeOptionsOverlayStyle.BLUR ||
        (style == EpisodeOptionsOverlayStyle.ARTWORK && blurUnwatchedEpisodes && !isWatched)
}

private const val TMDB_IMAGE_SIZE_PREFIX = "/t/p/"
private const val OVERLAY_BLUR_MAX_WIDTH_PX = 480

internal fun episodeOverlayBackdropUrl(thumbnail: String?): String? {
    return thumbnail?.takeIf { it.isNotBlank() }?.let(::upgradeTmdbImageUrl)
}

internal fun episodeOverlayBackdropDecodeSize(
    screenWidthPx: Int,
    screenHeightPx: Int,
    blur: Boolean
): Pair<Int, Int> {
    val width = screenWidthPx.coerceAtLeast(1)
    val height = screenHeightPx.coerceAtLeast(1)
    if (!blur) return width to height
    val blurredWidth = (width / 4).coerceIn(1, OVERLAY_BLUR_MAX_WIDTH_PX)
    val blurredHeight = ((height.toLong() * blurredWidth) / width).toInt().coerceAtLeast(1)
    return blurredWidth to blurredHeight
}

internal fun episodeOverlayBackdropMemoryCacheKey(
    url: String,
    widthPx: Int,
    heightPx: Int,
    blur: Boolean
): String {
    return "${url}_${widthPx}x${heightPx}_blur$blur"
}

internal fun episodeOverlayBackdropRequest(
    context: Context,
    url: String,
    screenWidthPx: Int,
    screenHeightPx: Int,
    blur: Boolean = false
): ImageRequest {
    val (widthPx, heightPx) = episodeOverlayBackdropDecodeSize(screenWidthPx, screenHeightPx, blur)
    val cacheKey = episodeOverlayBackdropMemoryCacheKey(url, widthPx, heightPx, blur)
    return ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(url)
        .crossfade(true)
        .size(width = widthPx, height = heightPx)
        .apply {
            if (blur) transformations(BlurTransformation())
        }
        .build()
}

internal fun upgradeTmdbImageUrl(url: String, size: String = "w1280"): String {
    val sizeStart = url.indexOf(TMDB_IMAGE_SIZE_PREFIX, ignoreCase = true)
        .takeIf { it >= 0 }
        ?.plus(TMDB_IMAGE_SIZE_PREFIX.length)
        ?: return url
    val sizeEnd = url.indexOf('/', sizeStart).takeIf { it > sizeStart } ?: return url
    val currentSize = url.substring(sizeStart, sizeEnd)
    if (currentSize.equals(size, ignoreCase = true)) return url
    return url.substring(0, sizeStart) + size + url.substring(sizeEnd)
}
