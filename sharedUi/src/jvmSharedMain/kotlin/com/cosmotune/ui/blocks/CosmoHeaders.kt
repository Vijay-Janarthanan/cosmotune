package com.cosmotune.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitPageSection
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.show_all
import org.jetbrains.compose.resources.stringResource

/*
 * The headings of a page in Orbit: the heading of a shelf ([OrbitPageSection]: the wide face, the strap line under it, "Show all" in
 * the accent), the mono label of a block ([OrbitLabel]) and the heading at the top of a page ([OrbitPageHeading]: Home's greeting,
 * with its peach full stop).
 */

/** A text action at the end of a heading has 12 dp of padding inside its label, so the row gives that much of its margin back. */
private val END_BUTTON_MARGIN = CosmoSpacing.margin - 12.dp

/**
 * The heading of a shelf: [OrbitPageSection], the [title] in the wide face over [subtitle] (the feed's strap line) in the dim ink, and
 * "Show all" in the accent at the end when [onShowAll] is set. 12 dp under it to the shelf.
 */
@Composable
fun CosmoSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onShowAll: (() -> Unit)? = null,
) {
    OrbitPageSection(
        title = title,
        modifier = modifier.padding(start = CosmoSpacing.margin, end = if (onShowAll != null) END_BUTTON_MARGIN else CosmoSpacing.margin, bottom = 12.dp),
        subtitle = subtitle.orEmpty(),
        action = if (onShowAll != null) stringResource(Res.string.show_all) else null,
        onAction = onShowAll,
    )
}

/**
 * The small heading of a block: Orbit's mono label ([OrbitLabel], all caps, tracked, the dim ink), with room above it, and a
 * [trailing] slot at the end (a text button such as "Clear").
 */
@Composable
fun CosmoOverline(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val look = orbitLook()
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = CosmoSpacing.margin, end = if (trailing != null) END_BUTTON_MARGIN else CosmoSpacing.margin, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OrbitLabel(text, orbitFonts(), look.dim, Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/**
 * The heading of a page: [OrbitPageHeading], the mono [overline] over the [title] in the wide face with its peach full stop, and a
 * [subtitle] under it in the dim ink, at most 300 dp wide. [actions] stand at the end of the title's row (round buttons), 8 dp apart.
 * [large] is kept for its callers.
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
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = 14.dp)) {
        OrbitPageHeading(
            title = title,
            label = overline.orEmpty(),
            fonts = fonts,
            look = look,
            modifier = Modifier.semantics { heading() },
            trailing = {
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            },
            maxLines = 2,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 8.dp).widthIn(max = 300.dp))
        }
    }
}
