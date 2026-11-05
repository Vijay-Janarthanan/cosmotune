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
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.kit.sectionActionSlot
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
 * The heading of a shelf (DESIGN_SYSTEM.md 4.5): [subtitle], the feed's strap line, as an all-caps overline over the title in
 * `titleLarge`, and "Show all" as a quiet text button at the end when [onShowAll] is set. 12 dp under it to the shelf.
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
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = CosmoSpacing.margin, end = if (onShowAll != null) END_BUTTON_MARGIN else CosmoSpacing.margin, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle.uppercase(),
                    style = type.forText(type.overline, subtitle),
                    color = Color(palette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                style = type.forText(type.titleLarge, title),
                color = Color(palette.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (onShowAll != null) KitTextButton(stringResource(Res.string.show_all), onShowAll, Modifier.sectionActionSlot())
    }
}

/**
 * The small heading of a block (DESIGN_SYSTEM.md 3.2 `overline`): 11 sp, all caps, `textSecondary`, with room above it, and a
 * [trailing] slot at the end (a text button such as "Clear").
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
            .padding(start = CosmoSpacing.margin, end = if (trailing != null) END_BUTTON_MARGIN else CosmoSpacing.margin, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = type.forText(type.overline, text),
            color = Color(palette.textSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/**
 * The heading of a page (DESIGN_SYSTEM.md 4.1, P5): an optional [overline], the title in the light `displayLarge`, which names the
 * place, and a [subtitle] under it, at most 300 dp wide. [actions] stand at the end of the title's row (40 dp tonal icon buttons),
 * 8 dp apart. [large] is kept for its callers: `displayLarge` already grows with the window's size class.
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
                text = overline.uppercase(),
                style = type.forText(type.overline, overline),
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
                style = type.forText(type.displayLarge, title),
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
                    type.bodyText,
                    subtitle,
                ),
                color = Color(palette.textSecondary),
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 300.dp),
            )
        }
    }
}
