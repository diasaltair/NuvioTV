package com.nuvio.tv

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import kotlin.math.floor

/**
 * Live TV fork: one text size for every label in the side menu.
 *
 * Each label works out the largest size at which it fits its space; every label then uses the
 * smallest of those, so they all match and none is cut off ("Live…", "On Dem…") or shrunk on
 * its own (which left labels in all different sizes). Labels from both the main menu and the
 * Live TV list count, so the two lists match each other too.
 */
internal object SidebarLabelSizes {
    /** Label -> the largest size (sp) at which it fits. */
    private val fits = mutableStateMapOf<String, Float>()

    fun report(key: String, sp: Float) {
        if (fits[key] != sp) fits[key] = sp
    }

    /** The size every label uses: no bigger than [max], and small enough for all of them. */
    fun shared(max: TextUnit): TextUnit {
        val smallest = fits.values.minOrNull() ?: return max
        return minOf(smallest, max.value).sp
    }
}

private const val MIN_LABEL_SP = 11f

@Composable
internal fun SidebarLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    /** Tells apart labels with the same text in different places (e.g. with an arrow beside it). */
    key: String = text,
    textAlign: TextAlign? = null
) {
    val style = LocalTextStyle.current
    val maxSize = if (style.fontSize.type == TextUnitType.Sp) style.fontSize else 16.sp
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val avail = constraints.maxWidth
        val fit = remember(text, avail, maxSize, style) {
            if (avail <= 0 || avail == Constraints.Infinity) maxSize.value
            else {
                val width = measurer.measure(
                    text = text,
                    style = style.copy(fontSize = maxSize),
                    maxLines = 1,
                    softWrap = false
                ).size.width
                // Rounded down to half sizes, so tiny width changes don't keep resizing everything.
                if (width <= avail) maxSize.value
                else (floor(maxSize.value * avail / width.toFloat() * 2f) / 2f).coerceAtLeast(MIN_LABEL_SP)
            }
        }
        DisposableEffect(key, fit) {
            SidebarLabelSizes.report(key, fit)
            onDispose { }
        }
        Text(
            text = text,
            color = color,
            style = style.copy(fontSize = SidebarLabelSizes.shared(maxSize)),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign
        )
    }
}
