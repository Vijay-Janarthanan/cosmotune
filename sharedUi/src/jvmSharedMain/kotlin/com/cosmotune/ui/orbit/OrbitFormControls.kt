package com.cosmotune.ui.orbit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlin.math.cos
import kotlin.math.sin

/*
 * The form pieces of the old tool pages and dialogs brought into Orbit (the listening workspace, the provider packages and their
 * dialogs): a labelled field, a switch row, a check, a quiet card to group a form's rows, and the busy ring that stands in for an
 * indeterminate progress bar. Built from [OrbitTextField], [OrbitSwitch] and [OrbitCheckMark].
 */

/**
 * A field of a form: the mono [label] over it when there is one (a field that opens filled keeps saying what it is), the field
 * itself ([OrbitTextField]: the chip's pill with the [placeholder] while empty) and [supporting] under it in the dim ink.
 * [modifier] is the whole block (a weight in a row); [fieldModifier] is the field (its test tag).
 */
@Composable
fun OrbitFormField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    secret: Boolean = false,
    error: Boolean = false,
    supporting: String? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier) {
        if (label != null) OrbitLabel(label, fonts, look.dim, Modifier.padding(start = 6.dp, bottom = 8.dp))
        OrbitTextField(value, onValueChange, placeholder, fonts, look, fieldModifier.fillMaxWidth(), error = error, secret = secret, enabled = enabled)
        if (!supporting.isNullOrBlank()) {
            Text(supporting, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 16.sp), color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        }
    }
}

/**
 * A setting that is on or off: the [title] (and its [detail] under it in the dim ink) with an [OrbitSwitch] at the end, on a row of
 * the ink at 5 % (radius 22, at least 62 dp) or bare when [plain]. The switch carries [switchModifier] (its test tag) and reads
 * [description] (the title unless said) to a screen reader.
 */
@Composable
fun OrbitSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    plain: Boolean = false,
    switchModifier: Modifier = Modifier,
    description: String = title,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        modifier.fillMaxWidth().heightIn(min = if (plain) 48.dp else 62.dp)
            .then(if (plain) Modifier else Modifier.clip(RoundedCornerShape(22.dp)).background(look.row).padding(horizontal = 18.dp, vertical = 10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.55f)) {
            Text(title, style = fonts.title.copy(fontSize = 14.5.sp), color = look.ink)
            if (!detail.isNullOrBlank()) Text(detail, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = look.dim, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(14.dp))
        OrbitSwitch(checked, onChange, look, description, switchModifier, enabled = enabled)
    }
}

/**
 * A check: [OrbitCheckMark] (a faint disc, solid ink with the check when [checked]) that toggles as a checkbox. [label] is what a
 * screen reader hears.
 */
@Composable
fun OrbitCheckBox(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = orbitLook()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.size(40.dp).alpha(if (enabled) 1f else 0.45f).clip(CircleShape)
            .toggleable(checked, interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { OrbitCheckMark(checked, look) }
}

/** A line that can be ticked: the whole row toggles, the [OrbitCheckMark] at its start, the [title] beside it. */
@Composable
fun OrbitCheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(22.dp))
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .alpha(if (enabled) 1f else 0.45f).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OrbitCheckMark(checked, look)
        Spacer(Modifier.width(12.dp))
        Text(title, style = fonts.body.copy(color = look.ink), modifier = Modifier.weight(1f))
    }
}

/**
 * A quiet card that groups a form's lines (a saved session, an installed provider, a preview to review): radius 22, the ink at 5 %,
 * or at 8 % when [raised] (a preview inside a card), 16 dp in.
 */
@Composable
fun OrbitFormCard(modifier: Modifier = Modifier, raised: Boolean = false, gap: Dp = 6.dp, content: @Composable ColumnScope.() -> Unit) {
    val look = orbitLook()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (raised) look.chip else look.row).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/**
 * Work under way with no end known: a faint orbit with its moon going round (once a second and a bit), in place of a spinner or a
 * running bar. Still under reduced motion: the moon stands at the top.
 */
@Composable
fun OrbitBusyRing(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = orbitLook().accent) {
    val reduced = LocalReducedMotion.current
    val turn = if (reduced) null else rememberInfiniteTransition(label = "busy")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1_300, easing = LinearEasing)), label = "moon")
    Canvas(modifier.size(size).semantics { contentDescription = "Working" }) {
        val moon = this.size.minDimension * 0.14f
        val r = this.size.minDimension / 2 - moon
        drawCircle(color.copy(alpha = 0.24f), r, style = Stroke(this.size.minDimension * 0.08f))
        val a = Math.toRadians(360.0 * (turn?.value ?: 0f) - 90.0)
        drawCircle(color, moon, center + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()))
    }
}

/** A problem said in a form: the words in the theme's error colour, the body face. */
@Composable
fun OrbitErrorText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = orbitFonts().body, color = MaterialTheme.colorScheme.error, modifier = modifier)
}

/** A choice that opens a list: the quiet pill of [text] with a chevron, its [OrbitMenu] of [items] under it. */
@Composable
fun OrbitMenuPill(text: String, open: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit, items: List<OrbitMenuItem>, modifier: Modifier = Modifier, enabled: Boolean = true, header: String? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Box(modifier) {
        Row(
            Modifier.heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.45f).clip(CircleShape).background(look.chip)
                .then(if (enabled) Modifier.clickable(role = Role.DropdownList, onClick = onOpen) else Modifier)
                .padding(start = 20.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = fonts.chip.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(800)), color = look.ink, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Icon(CosmoIcons.ChevronDown, contentDescription = null, tint = look.ink, modifier = Modifier.size(16.dp))
        }
        OrbitMenu(open, onDismiss, items, fonts, look, header = header)
    }
}
