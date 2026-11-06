package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitPageSection
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing

/*
 * The headings of Video's pages in the Orbit language (board B): a page opens as Home does, with a mono label over its name in the
 * wide face and the peach full stop ("FOR YOU" over "Video."), and a section is the wide section title with its "Show all".
 */

/**
 * A page's heading: [OrbitPageHeading] with [label] over [title], in the page's 16 dp margin; [actions] stand at its end (the
 * round buttons of a page: sort, add).
 */
@Composable
fun VideoPageHeading(title: String, label: String, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    OrbitPageHeading(
        title = title,
        label = label,
        fonts = orbitFonts(),
        look = orbitLook(),
        modifier = modifier.padding(start = CosmoSpacing.margin + 4.dp, end = CosmoSpacing.margin, top = 12.dp, bottom = 18.dp).semantics { heading() },
        trailing = actions?.let { a -> { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = a) } },
    )
}

/** A section of a page: the wide title and, with [onSeeAll], its "Show all" ([seeAllLabel]) in the accent, in the page's margin. */
@Composable
fun VideoSectionHeading(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null, seeAllLabel: String? = null, subtitle: String = "") {
    OrbitPageSection(
        title = title,
        subtitle = subtitle,
        action = seeAllLabel,
        onAction = onSeeAll,
        modifier = modifier.padding(start = CosmoSpacing.margin + 6.dp, end = CosmoSpacing.margin - 4.dp, top = 24.dp, bottom = 12.dp),
    )
}
