package com.cosmotune.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.show_all
import org.jetbrains.compose.resources.stringResource

/*
 * The headings of the B+C pages (`screens-v2.css` `.sec2`, `.t-overline`, `.h2-head`): the heading of a shelf, the small heading
 * of a block, and the big heading at the top of a page.
 */

/** A text button at the end of a heading has 12 dp of padding inside its label, so the row gives that much of its margin back. */
private val END_BUTTON_MARGIN = CosmoSpacing.margin - 12.dp

/**
 * The heading of a shelf (`.sec2`): the title in `title(TITLE_M)` with [subtitle] after it on the same baseline, in 12.5 sp
 * `textTertiary`, and "Show all" as a small text button at the end when [onShowAll] is set. A long pair wraps onto two lines
 * and then cuts.
 */
@Composable
fun CosmoSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onShowAll: (() -> Unit)? = null,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val titleStyle = type.forText(type.title(TypeScale.TITLE_M), title)
    val text = buildAnnotatedString {
        append(title)
        if (!subtitle.isNullOrBlank()) {
            // The gap and the strapline share one baseline with the title (the board: a flex row with `align-items: baseline`, gap 10).
            withStyle(
                SpanStyle(
                    fontFamily = type.textFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.5.sp,
                    letterSpacing = 0.sp,
                    color = Color(palette.textTertiary),
                ),
            ) { append("   "); append(subtitle) }
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = CosmoSpacing.margin, end = if (onShowAll != null) END_BUTTON_MARGIN else CosmoSpacing.margin, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = titleStyle,
            color = Color(palette.textPrimary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onShowAll != null) {
            CosmoButton(stringResource(Res.string.show_all), onShowAll, kind = CosmoButtonKind.TEXT, size = CosmoButtonSize.SMALL)
        }
    }
}

/**
 * The small heading of a block (`.t-overline`): 12 sp, weight 700, `textSecondary`, with room above it, and a [trailing] slot at the
 * end (a text button such as "Clear").
 */
@Composable
fun CosmoOverline(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = CosmoSpacing.margin, end = if (trailing != null) END_BUTTON_MARGIN else CosmoSpacing.margin, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = type.forText(type.overlineStyle(), text),
            color = Color(palette.textSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** 12 sp, weight 700, tracking 0.01 em (`--tr-label`). */
private fun com.cosmotune.ui.theme.CosmoType.overlineStyle(): TextStyle =
    TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 12.sp * 1.3f, letterSpacing = 0.01.em)

/**
 * The heading of a page (`.h2-head`, `.pg-title`, `.h2-sub`): an optional [overline], the title in `display(DISPLAY_M)` ([large]:
 * `DISPLAY_L`, for the desktop), and a [subtitle] under it, at most 300 dp wide. [actions] stand at the end of the title's row
 * (40 dp tonal icon buttons), 8 dp apart.
 */
@Composable
fun CosmoPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    subtitle: String? = null,
    large: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin)) {
        if (!overline.isNullOrBlank()) {
            Text(
                text = overline,
                style = type.forText(type.overlineStyle(), overline),
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = if (overline.isNullOrBlank()) 14.dp else 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = type.forText(type.display(if (large) TypeScale.DISPLAY_L else TypeScale.DISPLAY_M), title),
                color = Color(palette.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            actions()
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = type.forText(
                    TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, lineHeight = 14.5.sp * 1.4f),
                    subtitle,
                ),
                color = Color(palette.textSecondary),
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 300.dp),
            )
        }
    }
}
