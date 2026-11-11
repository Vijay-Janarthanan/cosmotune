package com.cosmotune.ui.listening

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitFormCard
import com.cosmotune.ui.orbit.OrbitFormField
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitSpace
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/*
 * The Your listening workspace's pieces in Orbit, so its pages read alike: a page's heading, the titles and notes of its lines, the
 * buttons (one peach action where a page leads with one, the quiet chip's pill for the rest), the chips, the fields, the switch rows
 * and the quiet card a saved item stands on.
 */

/** A button's weight: [MAIN] is the peach action a page or a preview leads with, [QUIET] the chip's pill of the others. */
internal enum class ToolKind { MAIN, QUIET }

@Composable
internal fun ToolButton(text: String, onClick: () -> Unit, enabled: Boolean = true, kind: ToolKind = ToolKind.MAIN, tag: String = "") {
    // 6 dp above, so a note or a card line over it stands a text-to-action gap (OrbitSpace.textToAction) away with the card's own 6.
    val modifier = Modifier.padding(top = 6.dp, bottom = 4.dp).then(if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier)
    OrbitPillButton(text, onClick, orbitFonts(), orbitLook(), modifier, primary = kind == ToolKind.MAIN, enabled = enabled)
}

/**
 * A page's heading: the [title] in the wide face and the [detail] under it in the dim ink, a group's gap
 * ([OrbitSpace.betweenGroups]) under the row of page chips above it.
 */
@Composable
internal fun Heading(title: String, detail: String) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(OrbitSpace.labelToControl)) {
        Text(title, style = fonts.section, color = look.ink, modifier = Modifier.semantics { heading() })
        Text(detail, style = fonts.body, color = look.dim)
    }
}

/** A heading inside a page ("From your library", "Shared catalogue"): the wide face, a size down. */
@Composable
internal fun ToolSection(text: String, modifier: Modifier = Modifier) {
    Text(text, style = orbitFonts().section.copy(fontSize = 17.sp, lineHeight = 21.sp), color = orbitLook().ink, modifier = modifier.padding(top = 6.dp).semantics { heading() })
}

/** The name of a line (a session, a playlist, an artist). [color] overrides the ink (a check that needs attention). */
@Composable
internal fun ToolTitle(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(text, style = orbitFonts().title, color = color ?: orbitLook().ink, modifier = modifier)
}

/** A note under a line, or a page's small print: the body face a size down, in the dim ink, or the error colour when [error]. */
@Composable
internal fun ToolNote(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(text, style = orbitFonts().body.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = if (error) MaterialTheme.colorScheme.error else orbitLook().dim, modifier = modifier)
}

/** Words that report a problem, in the body face and the theme's error colour. */
@Composable
internal fun ToolError(text: String, modifier: Modifier = Modifier) {
    Text(text, style = orbitFonts().body, color = MaterialTheme.colorScheme.error, modifier = modifier)
}

/** A chip that is a choice: solid ink when [selected]. [enabled] false fades it and takes no tap. */
@Composable
internal fun ToolChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OrbitChip(
        text, orbitFonts(), orbitLook(), onClick = { if (enabled) onClick() },
        modifier = modifier.alpha(if (enabled) 1f else 0.45f).semantics { this.selected = selected; if (!enabled) disabled() },
        selected = selected, leading = null,
    )
}

/** A row of chips that scrolls sideways when it runs out of room. */
@Composable
internal fun ToolChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

/**
 * A field: [label] is its placeholder, and over it too when [labelled] (a field that opens filled). [tag] goes on the field. It keeps
 * a text-to-action gap ([OrbitSpace.textToAction], 12 dp with the button's own 4) from the button or field under it.
 */
@Composable
internal fun ToolField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    tag: String = "",
    enabled: Boolean = true,
    secret: Boolean = false,
    labelled: Boolean = false,
) {
    OrbitFormField(
        value, onValueChange, placeholder = label, modifier = modifier.padding(top = 4.dp, bottom = 8.dp),
        fieldModifier = if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier,
        label = label.takeIf { labelled }, enabled = enabled, secret = secret,
    )
}

/** A setting that is on or off, on the quiet row with its switch at the end. [tag] goes on the switch. */
@Composable
internal fun ToggleRow(label: String, checked: Boolean, tag: String = "", description: String = label, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    OrbitSwitchRow(
        label, checked, onChange, Modifier.padding(vertical = 3.dp), enabled = enabled,
        switchModifier = if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier, description = description,
    )
}

/** The quiet card a saved item stands on; [raised] for a preview inside a page. */
@Composable
internal fun ToolCard(modifier: Modifier = Modifier, raised: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    OrbitFormCard(modifier, raised = raised, content = content)
}
