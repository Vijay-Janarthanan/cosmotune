package com.cosmotune.ui.whatsnew

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.HighlightIcon
import com.cosmotune.ReleaseHighlight
import com.cosmotune.design.InputModality
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.LocalFocusModality
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitOverline
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitSheetGrab
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitSheetGround
import com.cosmotune.ui.sheets.CosmoDialog
import com.cosmotune.ui.sheets.CosmoDialogCard
import com.cosmotune.ui.sheets.CosmoSheetScrim
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt

/*
 * "What's new" in Orbit: the card a new release is offered in (Update now, Later) and the card that greets the first launch after
 * an update (Got it). It shows a release's Highlights only (com.cosmotune.ReleaseHighlights), never its whole notes, with a quiet
 * link to the full notes on the release page. One body, three grounds: a sheet on the phone, a dialog card on the desktop, and the
 * same card with bigger words and D-pad focus rings on the TV.
 */

/** Where the card is drawn: the type, the actions' layout and the focus rings follow it. */
enum class WhatsNewSurface { PHONE, DESKTOP, TV }

/** One of the card's buttons: its [label] and what it does. */
class WhatsNewAction(val label: String, val onClick: () -> Unit)

/**
 * What the card says. [version] is the release in the overline; [highlights] its rows (at most five). [primary] is the one peach
 * action, [secondary] the quiet one beside it (none for the welcome's single Got it). [meta] is the size or version line over the
 * actions ("1.0.20 → 1.0.21 · 38 MB"); [progress] replaces it with a download's orbit while one runs (a fraction, or a negative
 * number while the size is not known); [problem] is a failed download's reason, in place of both. [onFullNotes] is the quiet link
 * to the release page; null leaves it out.
 */
class WhatsNew(
    val version: String,
    val highlights: List<ReleaseHighlight>,
    val primary: WhatsNewAction,
    val secondary: WhatsNewAction? = null,
    val onFullNotes: (() -> Unit)? = null,
    val meta: String? = null,
    val progress: Float? = null,
    val problem: String? = null,
    val title: String = "Fresh in Cosmotune",
    val overline: String = "What's new · $version",
    val fullNotesLabel: String = "Full release notes",
)

/** The card's padding, the space between its rows and the space before its actions (the spacing scale). */
private val CARD_PADDING = 16.dp
private val ROW_GAP = 12.dp
private val ACTIONS_GAP = 24.dp

/** A highlight row's corners, as every Orbit row's. */
private val ROW_SHAPE = RoundedCornerShape(22.dp)

/** The widest the phone's sheet gets (a tablet), and the dialog card on the desktop and the TV. */
private val SHEET_MAX_WIDTH = 560.dp
private val DESKTOP_MAX_WIDTH = 460.dp
private val TV_MAX_WIDTH = 760.dp

/**
 * "1.0.20 → 1.0.21 · 38 MB": the version line of an update, the [bytes] of its download when the release says (rounded to whole
 * megabytes, or one decimal under ten).
 */
fun whatsNewMeta(current: String, next: String, bytes: Long?): String {
    val size = bytes?.takeIf { it > 0 }?.let { b ->
        val mb = b / 1_048_576.0
        if (mb < 10) "${(mb * 10).roundToInt() / 10.0} MB" else "${mb.roundToInt()} MB"
    }
    return listOfNotNull("$current → $next", size).joinToString(" · ")
}

/** The icon drawn for a highlight's keyword. */
fun highlightIcon(icon: HighlightIcon): ImageVector = when (icon) {
    HighlightIcon.LOOK -> CosmoIcons.Canvas
    HighlightIcon.WATCH -> CosmoIcons.Video
    HighlightIcon.LIVE -> CosmoIcons.Bell
    HighlightIcon.CHANNELS -> CosmoIcons.Grid
    HighlightIcon.TIMER -> CosmoIcons.Timer
    HighlightIcon.PLAYBACK -> CosmoIcons.Play
    HighlightIcon.LYRICS -> CosmoIcons.Lyrics
    HighlightIcon.AUDIO -> CosmoIcons.Waveform
    HighlightIcon.PLAYLIST -> CosmoIcons.Queue
    HighlightIcon.SYNC -> CosmoIcons.Repeat
    HighlightIcon.CHECK -> CosmoIcons.Check
    HighlightIcon.READ -> CosmoIcons.Library
    HighlightIcon.PODCAST -> CosmoIcons.Radio
    HighlightIcon.TV -> CosmoIcons.Devices
    HighlightIcon.WEAR -> CosmoIcons.Clock
    HighlightIcon.DOWNLOAD -> CosmoIcons.Download
    HighlightIcon.SEARCH -> CosmoIcons.Search
    HighlightIcon.TOGETHER -> CosmoIcons.Together
    HighlightIcon.SETTINGS -> CosmoIcons.Settings
    HighlightIcon.NEW -> CosmoIcons.Explore
}

/**
 * The tints of the rows' discs, one after another down the card so neighbours differ: lavender, green, sky, gold, rose and teal.
 * Peach is not among them, as peach is for what plays. Each is a light tone (the icon in dark, over the tone at 16 %) and a deep
 * one (the icon in light, over the deep tone at 12 %).
 */
private val TINTS = listOf(
    Color(0xFFB9B0FF) to Color(0xFF5A50B8),
    Color(0xFF7CE3A1) to Color(0xFF237A47),
    Color(0xFF8FC3FF) to Color(0xFF2D62A8),
    Color(0xFFF2CF6B) to Color(0xFF80600A),
    Color(0xFFF5A3C0) to Color(0xFFA8335F),
    Color(0xFF7FD6D9) to Color(0xFF1C7378),
)

/** The fill and the icon colour of the disc of row [index]. */
internal fun highlightTint(index: Int, dark: Boolean): Pair<Color, Color> {
    val (light, deep) = TINTS[index.mod(TINTS.size)]
    return if (dark) light.copy(alpha = 0.16f) to light else deep.copy(alpha = 0.12f) to deep
}

/**
 * The phone's card: an Orbit sheet standing on the bottom of the window (the sheet's ground, 32 dp top corners, the grabber) over
 * the 40 % scrim, which closes it when tapped. Drawn in the page's own tree, over everything, and sliding up unless motion is
 * reduced. Back is the caller's (a BackHandler calling [onDismiss]).
 */
@Composable
fun WhatsNewSheet(whatsNew: WhatsNew, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val look = orbitLook()
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val hidden by animateFloatAsState(
        if (shown || reduced) 0f else 1f,
        if (reduced) snap() else tween(280, easing = FastOutSlowInEasing),
        label = "whatsNewSheet",
    )
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - hidden }
                .background(CosmoSheetScrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onDismiss),
        )
        // Clear of the status bar on a short window: the rows scroll before the sheet reaches the top.
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 24.dp), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .widthIn(max = SHEET_MAX_WIDTH)
                    .fillMaxWidth()
                    .graphicsLayer { translationY = size.height * hidden }
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(orbitSheetGround(look))
                    // A tap on the sheet's own ground goes nowhere, so it does not reach the scrim under it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .navigationBarsPadding()
                    .padding(start = CARD_PADDING, end = CARD_PADDING, bottom = CARD_PADDING),
            ) {
                OrbitSheetGrab(look, Modifier.align(Alignment.CenterHorizontally))
                WhatsNewBody(whatsNew, WhatsNewSurface.PHONE, Modifier.weight(1f, fill = false))
            }
        }
    }
}

/**
 * The desktop's and the TV's card in a window of its own ([CosmoDialog]): the floating Orbit card, at most 460 dp wide on the
 * desktop and 760 dp on the TV, which dims the window and closes on Back (and, unless [properties] say otherwise, on a click
 * outside). [extra] goes under the rows (the TV's code to scan).
 */
@Composable
fun WhatsNewDialog(
    whatsNew: WhatsNew,
    onDismiss: () -> Unit,
    surface: WhatsNewSurface = WhatsNewSurface.DESKTOP,
    properties: DialogProperties? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    CosmoDialog(onDismiss = onDismiss, maxWidth = maxWidthOf(surface), properties = properties) {
        WhatsNewBody(whatsNew, surface, Modifier.padding(paddingOf(surface)), extra)
    }
}

/** [WhatsNewDialog]'s card drawn in the page's own tree, over its own 55 % scrim (the render tests, a host with no dialog layer). */
@Composable
fun WhatsNewCard(
    whatsNew: WhatsNew,
    onDismiss: () -> Unit,
    surface: WhatsNewSurface = WhatsNewSurface.DESKTOP,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    CosmoDialogCard(onDismiss = onDismiss, maxWidth = maxWidthOf(surface)) {
        WhatsNewBody(whatsNew, surface, Modifier.padding(paddingOf(surface)), extra)
    }
}

/** The card's padding: the scale's 16 dp, and 24 on the TV so the focus ring round a button clears the card's edge. */
private fun paddingOf(surface: WhatsNewSurface): Dp = if (surface == WhatsNewSurface.TV) 24.dp else CARD_PADDING

private fun maxWidthOf(surface: WhatsNewSurface): Dp = when (surface) {
    WhatsNewSurface.PHONE -> SHEET_MAX_WIDTH
    WhatsNewSurface.DESKTOP -> DESKTOP_MAX_WIDTH
    WhatsNewSurface.TV -> TV_MAX_WIDTH
}

/**
 * The inside of every card: the mono overline ("WHAT'S NEW · 1.0.21") over the wide title, the highlight rows 12 dp apart
 * (two by two on the TV; scrolling when a short window cannot hold them), [extra], then 24 dp on the version line (or the download's orbit, or the
 * problem) and the actions. On the phone the two buttons share the width and the link sits under them; on the desktop and the TV
 * the link leads the row and the buttons end it. On the TV the peach action takes focus and every button shows the TV's ring.
 */
@Composable
fun WhatsNewBody(
    whatsNew: WhatsNew,
    surface: WhatsNewSurface,
    modifier: Modifier = Modifier,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val tv = surface == WhatsNewSurface.TV
    Column(modifier.fillMaxWidth()) {
        OrbitOverline(whatsNew.overline, fonts, look, Modifier.padding(top = if (surface == WhatsNewSurface.PHONE) 4.dp else 8.dp))
        Text(
            whatsNew.title,
            style = fonts.heroTitle.let { if (tv) it.copy(fontSize = 30.sp, lineHeight = 32.sp) else it },
            color = look.ink,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        Column(
            Modifier
                .weight(1f, fill = false)
                .padding(top = CARD_PADDING)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            if (tv) {
                // Two by two on the TV, so five highlights, the version line and the buttons fit a 540 dp screen together.
                whatsNew.highlights.chunked(2).forEachIndexed { pair, items ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                        items.forEachIndexed { i, item -> HighlightRow(item, pair * 2 + i, fonts, look, tv, Modifier.weight(1f).fillMaxHeight()) }
                        if (items.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                whatsNew.highlights.forEachIndexed { index, item -> HighlightRow(item, index, fonts, look, tv, Modifier.fillMaxWidth()) }
            }
            extra?.invoke(this)
        }
        Spacer(Modifier.height(ACTIONS_GAP))
        StatusLine(whatsNew, fonts, look, tv)
        if (surface == WhatsNewSurface.PHONE) PhoneActions(whatsNew, fonts, look) else WideActions(whatsNew, fonts, look, tv)
    }
}

/** One highlight as an Orbit row: the tinted disc with its icon, the bold title and the dim sentence. */
@Composable
private fun HighlightRow(item: ReleaseHighlight, index: Int, fonts: OrbitFonts, look: OrbitLook, tv: Boolean, modifier: Modifier) {
    val (fill, ink) = highlightTint(index, look.dark)
    val disc = if (tv) 48.dp else 40.dp
    Row(
        modifier
            .heightIn(min = if (tv) 68.dp else 60.dp)
            .clip(ROW_SHAPE)
            .background(look.row)
            .padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(disc).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Icon(highlightIcon(item.icon), contentDescription = null, tint = ink, modifier = Modifier.size(if (tv) 24.dp else 20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val title = fonts.title.let { if (tv) it.copy(fontSize = 18.sp, lineHeight = 22.sp) else it }
            val body = fonts.body.let { if (tv) it.copy(fontSize = 15.sp, lineHeight = 20.sp) else it }
            val heading = item.title
            if (heading != null) {
                Text(heading, style = title, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.text, style = body, color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                // An older release's line: no title, so the line itself takes the ink, on one line.
                Text(item.text, style = body.copy(fontWeight = FontWeight(600)), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The line over the actions: a failed download's reason, a running download's orbit and percentage, or the version line. */
@Composable
private fun StatusLine(whatsNew: WhatsNew, fonts: OrbitFonts, look: OrbitLook, tv: Boolean) {
    val label = fonts.label.let { if (tv) it.copy(fontSize = 13.sp) else it }
    when {
        whatsNew.problem != null -> Text(
            whatsNew.problem,
            style = fonts.body,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        whatsNew.progress != null -> Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val fraction = whatsNew.progress
            OrbitRing(20.dp, look.accent, progress = { fraction.takeIf { it >= 0f } }, spinning = fraction < 0f, stroke = 2.dp, moon = 3.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (fraction >= 0f) "Downloading · ${(fraction * 100).roundToInt()} %" else "Downloading",
                style = label,
                color = look.dim,
            )
        }
        whatsNew.meta != null -> Text(whatsNew.meta, style = label, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
    }
}

/** The phone's actions: the quiet and the peach pill sharing the width (the peach one alone when it is all), the link under them. */
@Composable
private fun ColumnScope.PhoneActions(whatsNew: WhatsNew, fonts: OrbitFonts, look: OrbitLook) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        whatsNew.secondary?.let { WhatsNewPill(it, primary = false, fonts, look, tv = false, Modifier.weight(1f), height = 48.dp) }
        WhatsNewPill(whatsNew.primary, primary = true, fonts, look, tv = false, Modifier.weight(1f), height = 48.dp)
    }
    whatsNew.onFullNotes?.let { open ->
        FullNotesLink(whatsNew.fullNotesLabel, open, fonts, look, tv = false, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
    }
}

/** The desktop's and the TV's actions: the link at the start, the quiet then the peach pill at the end. */
@Composable
private fun WideActions(whatsNew: WhatsNew, fonts: OrbitFonts, look: OrbitLook, tv: Boolean) {
    val height = if (tv) 52.dp else 44.dp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        whatsNew.onFullNotes?.let { FullNotesLink(whatsNew.fullNotesLabel, it, fonts, look, tv) }
        Spacer(Modifier.weight(1f))
        whatsNew.secondary?.let {
            WhatsNewPill(it, primary = false, fonts, look, tv, height = height)
            Spacer(Modifier.width(if (tv) 14.dp else 10.dp))
        }
        WhatsNewPill(whatsNew.primary, primary = true, fonts, look, tv, height = height, takesFocus = tv)
    }
}

/**
 * A card's button: the kit's pill ([OrbitPillButton]) with a focus ring a little outside it, the TV's (4 dp, the accent, 4 dp
 * out) while it has D-pad focus, or on a computer while it has keyboard focus. [takesFocus] moves focus to it when it appears and
 * again when its label changes (a download's buttons replacing the offer's).
 */
@Composable
private fun WhatsNewPill(
    action: WhatsNewAction,
    primary: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    tv: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    takesFocus: Boolean = false,
) {
    val requester = remember { FocusRequester() }
    if (takesFocus) LaunchedEffect(action.label) { runCatching { requester.requestFocus() } }
    OrbitPillButton(
        action.label,
        action.onClick,
        fonts,
        look,
        modifier.focusRing(look, tv).focusRequester(requester),
        primary = primary,
        height = height,
    )
}

/** The quiet link to the release page: the dim words and a chevron, as tall as a button so it is easy to hit. */
@Composable
private fun FullNotesLink(label: String, onClick: () -> Unit, fonts: OrbitFonts, look: OrbitLook, tv: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .focusRing(look, tv)
            .heightIn(min = if (tv) 52.dp else 40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = fonts.chip.copy(fontSize = if (tv) 16.sp else 13.5.sp, fontWeight = FontWeight(700)),
            color = look.dim,
            textAlign = TextAlign.Start,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = look.dim, modifier = Modifier.size(if (tv) 18.dp else 16.dp))
    }
}

/**
 * Draws the focus ring outside a pill (every piece that takes focus here is one) while it has focus: always on the TV (the D-pad's only answer), and on a computer or a phone
 * only while the keyboard is in use ([InputModality]), as the rest of the app's rings do.
 */
@Composable
private fun Modifier.focusRing(look: OrbitLook, tv: Boolean): Modifier {
    var focused by remember { mutableStateOf(false) }
    val modality = LocalFocusModality.current
    val alpha by animateFloatAsState(if (focused) 1f else 0f, tween(120), label = "whatsNewRing")
    return onFocusChanged { focused = it.hasFocus }.drawBehind {
        val shown = if (tv) alpha > 0f else InputModality.ringShown(focused, modality.keyboard)
        if (!shown) return@drawBehind
        val width = (if (tv) 4.dp else 3.dp).toPx()
        val gap = (if (tv) 4.dp else 2.dp).toPx()
        val grow = gap + width / 2f
        val ringSize = Size(size.width + grow * 2, size.height + grow * 2)
        drawRoundRect(
            look.accent.copy(alpha = if (tv) alpha else 1f),
            topLeft = Offset(-grow, -grow),
            size = ringSize,
            cornerRadius = CornerRadius(ringSize.height / 2f),
            style = Stroke(width),
        )
    }
}
