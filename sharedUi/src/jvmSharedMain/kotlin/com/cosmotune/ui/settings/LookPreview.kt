package com.cosmotune.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.design.ContrastReport
import com.cosmotune.design.LookSamples
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoChip
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.components.trackFocusModality
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.vivid
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_look_preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/*
 * The "Preview the look" style sheet, in Orbit: the palette and the pieces of the Orbit kit, on the six sample covers, in the dark and
 * the light theme. It is a page for the owner to judge the look, not a screen of the app, so its own labels are plain English; only
 * the title uses a string resource (the Settings entry names it the same way).
 *
 * It owns a palette of its own (`CosmoPaletteState`), so it works with the New look switch off and never touches the palette of the
 * song that is playing.
 */

/** A full-screen dialog (the whole window on the desktop). [onDismiss] closes it. */
@Composable
fun CosmoLookPreviewDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LookPreviewScreen(onDismiss)
    }
}

/** The page itself, without the dialog window around it. [startDark] and [startSample] are where it opens. */
@Composable
internal fun LookPreviewScreen(onClose: () -> Unit, startDark: Boolean = isSystemInDarkTheme(), startSample: Int = 0) {
    val reduced = LocalReducedMotion.current
    var dark by remember { mutableStateOf(startDark) }
    var selected by remember { mutableIntStateOf(startSample) }
    val state = remember { CosmoPaletteState(CosmoBrandPalettes.of(dark), dark) }
    val colors = remember(state) { CosmoColors(state) }
    LaunchedEffect(selected, dark) {
        // The engine takes a few milliseconds per cover: not on the main thread.
        val next = withContext(Dispatchers.Default) { LookSamples.palette(LookSamples.all[selected], dark) }
        state.show(next, dark, reduced, animate = true)
    }
    CompositionLocalProvider(LocalCosmoColors provides colors) {
        val fonts = orbitFonts()
        val look = orbitLook()
        Box(Modifier.trackFocusModality().fillMaxSize().background(look.ground), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.cosmo_look_preview), Modifier.weight(1f), color = look.ink, style = fonts.heroTitle)
                    OrbitRoundButton(CosmoIcons.Close, "Close", look.ink, onClose, size = 44.dp)
                }

                Section("Cover", fonts, look) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LookSamples.all.forEachIndexed { i, sample ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .selectable(selected = i == selected, role = Role.RadioButton, onClick = { selected = i })
                                    .semantics { contentDescription = "${sample.name} cover" },
                                contentAlignment = Alignment.Center,
                            ) {
                                // Every cover is a disc: the two colours of the sample, ringed in the ink when it is the one shown.
                                Canvas(
                                    Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .then(if (i == selected) Modifier.border(BorderStroke(2.dp, look.ink), CircleShape) else Modifier),
                                ) {
                                    drawRect(Color(sample.a), size = Size(size.width / 2f, size.height))
                                    drawRect(Color(sample.b), Offset(size.width / 2f, 0f), Size(size.width / 2f, size.height))
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OrbitSelectChip("Dark", dark, fonts, look, onClick = { dark = true })
                        OrbitSelectChip("Light", !dark, fonts, look, onClick = { dark = false })
                    }
                }

                Section("Colours", fonts, look) { Swatches(colors, look) }

                Section("Shapes", fonts, look) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("chip" to 20.dp, "row" to 22.dp, "tile" to 26.dp, "card" to 30.dp, "hero" to 34.dp).forEach { (name, radius) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(radius)).background(look.chip))
                                Text(name, color = look.dim, style = fonts.label, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }

                Section("Type", fonts, look) {
                    Text("Wild Hearts", color = look.ink, style = fonts.greeting)
                    Text("The cover sets the colour", color = look.ink, style = fonts.section)
                    Text("01", color = look.accent, style = fonts.number)
                    Text("Songs you keep coming back to", color = look.ink, style = fonts.title)
                    Text("Body text in Hanken Grotesk, with a line height that reads easily over many lines of text.", color = look.ink, style = fonts.body)
                    OrbitLabel("Label · 3:42", fonts, look.dim)
                }

                Section("Colour card", fonts, look) { CoverCard(selected, fonts, look) }

                Section("Progress, seek and level", fonts, look) { Lines(fonts, look) }

                Section("Buttons, chips and switches", fonts, look) { Controls(fonts, look) }

                Section("Contrast", fonts, look) { ContrastList(colors, fonts, look) }
            }
        }
    }
}

@Composable
private fun Section(title: String, fonts: OrbitFonts, look: OrbitLook, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OrbitLabel(title, fonts, look.dim)
        content()
    }
}

private val fieldNames = listOf(
    "seed", "ambient", "bgTop", "bgMid", "bgBottom", "surface", "surfaceHigh", "surfaceVariant", "text", "text2", "text3",
    "accent", "accentStrong", "onAccent", "btnStart", "btnEnd", "container", "onContainer", "glow", "shadow", "scrim",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(colors: CosmoColors, look: OrbitLook) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fieldNames.forEachIndexed { field, name ->
            Column(Modifier.width(64.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, look.ink.copy(alpha = 0.14f)), RoundedCornerShape(12.dp))
                        // Read while drawing: the swatches fade with the palette.
                        .drawBehind { drawRect(colors.live(field)) },
                )
                Text(name, color = look.dim, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/** The sample cover as a disc: [LookSamples] stripes, scaled to fill, cut round. */
@Composable
private fun StripeDisc(selected: Int, size: Dp, modifier: Modifier = Modifier) {
    val sample = LookSamples.all[selected]
    Canvas(modifier.size(size).clip(CircleShape)) {
        val stripe = this.size.width / 8f
        drawRect(Color(sample.b))
        for (i in 0 until 8 step 2) drawRect(Color(sample.a), Offset(i * stripe, 0f), Size(stripe, this.size.height))
    }
}

/** Home's colour card on the sample: the flat colour of the cover, the cover as a disc, the play button in the bite. */
@Composable
private fun CoverCard(selected: Int, fonts: OrbitFonts, look: OrbitLook) {
    var playing by remember { mutableStateOf(false) }
    val tint = vivid(LookSamples.all[selected].a.toInt())
    OrbitBiteCard(tint.fill, Modifier.fillMaxWidth().height(200.dp), bite = { OrbitPlayButton(playing, { playing = !playing }, look) }) {
        Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                StripeDisc(selected, 124.dp)
                OrbitRing(140.dp, tint.ink, progress = null, spinning = playing)
            }
            Column(Modifier.weight(1f).padding(start = 18.dp, end = 64.dp)) {
                OrbitLabel("Album · 12 songs", fonts, tint.ink.copy(alpha = 0.75f))
                Text(LookSamples.all[selected].name, style = fonts.heroTitle, color = tint.ink, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
                Text("One bite, one play button, nothing on the cover.", style = fonts.body, color = tint.ink.copy(alpha = 0.85f), modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    OrbitSongRow(
        "Golden Hour", "Mira and the Coast", null, fonts, look, onClick = { playing = !playing }, now = true, playing = playing,
        trailingText = "3:42", discContent = { StripeDisc(selected, 44.dp) },
    )
    OrbitSongRow("Low Tide Letters", "Harbour Lights", null, fonts, look, onClick = {}, trailingText = "4:07", discContent = { StripeDisc((selected + 1) % LookSamples.all.size, 44.dp) })
}

@Composable
private fun Lines(fonts: OrbitFonts, look: OrbitLook) {
    var volume by remember { mutableFloatStateOf(0.6f) }
    var position by remember { mutableFloatStateOf(0.35f) }
    var playing by remember { mutableStateOf(true) }
    LaunchedEffect(playing) {
        while (playing) { delay(250); position = (position + 0.0125f) % 1f }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OrbitRing(56.dp, look.accent, progress = { position }, spinning = playing)
        OrbitRing(56.dp, look.ink, progress = null, spinning = true)
        Text("The orbit: the arc is how far the song is, the moon is now. Without a position the moon circles.", Modifier.weight(1f), color = look.dim, style = fonts.body)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Seek, ${if (playing) "playing" else "paused"}", Modifier.weight(1f), color = look.dim, style = fonts.body)
        OrbitRoundButton(if (playing) CosmoIcons.Pause else CosmoIcons.Play, if (playing) "Pause" else "Play", look.ink, { playing = !playing })
    }
    OrbitProgressLine(progress = { position }, ink = look.ink, onSeek = { position = it }, modifier = Modifier.semantics { contentDescription = "Seek sample" })
    Text("Level (volume and equaliser)", color = look.dim, style = fonts.body)
    OrbitProgressLine(progress = { volume }, ink = look.ink, onSeek = { volume = it }, live = true, moon = 8.dp, modifier = Modifier.semantics { contentDescription = "Volume sample" })
}

/** Every control of the kit, at rest, on and disabled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(fonts: OrbitFonts, look: OrbitLook) {
    var chillOn by remember { mutableStateOf(true) }
    var focusOn by remember { mutableStateOf(false) }
    var segment by remember { mutableIntStateOf(0) }
    var switchA by remember { mutableStateOf(true) }
    var switchB by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(true) }
    var liked by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }

    Text("Buttons: the one peach action, the quiet chip, the words alone", color = look.dim, style = fonts.body)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CosmoButton("Play all", {}, kind = CosmoButtonKind.PRIMARY, icon = CosmoIcons.Play)
        CosmoButton("Shuffle", {}, kind = CosmoButtonKind.TONAL, icon = CosmoIcons.Shuffle)
        CosmoButton("Add to queue", {}, kind = CosmoButtonKind.QUIET)
        CosmoButton("Not now", {}, kind = CosmoButtonKind.TEXT)
    }
    Text("Sizes, and disabled", color = look.dim, style = fonts.body)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CosmoButton("Small", {}, size = CosmoButtonSize.SMALL)
        CosmoButton("Medium", {}, size = CosmoButtonSize.MEDIUM)
        CosmoButton("Large", {}, size = CosmoButtonSize.LARGE)
        CosmoButton("Disabled", {}, enabled = false)
        CosmoButton("Disabled", {}, kind = CosmoButtonKind.TONAL, enabled = false)
    }

    Text("Round buttons: at rest, on, the liked heart, disabled; the play button", color = look.dim, style = fonts.body)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CosmoIconButton(CosmoIcons.Shuffle, "Shuffle sample", { shuffle = !shuffle }, on = shuffle)
        CosmoIconButton(CosmoIcons.Previous, "Previous sample", {}, size = 56.dp)
        CosmoIconButton(if (liked) CosmoIcons.HeartFill else CosmoIcons.Heart, "Like sample", { liked = !liked }, size = 40.dp, on = liked, accentWhenOn = true)
        CosmoIconButton(CosmoIcons.Queue, "Disabled sample", {}, enabled = false)
        OrbitPlayButton(playing, { playing = !playing }, look, size = 56.dp)
    }

    Text("Chips", color = look.dim, style = fonts.body)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CosmoChip("Chill", chillOn, { chillOn = !chillOn })
        CosmoChip("Focus", focusOn, { focusOn = !focusOn })
        CosmoChip("Workout", false, {})
    }

    Text("Choices: one of a few, the chosen one solid ink", color = look.dim, style = fonts.body)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Songs", "Albums", "Artists").forEachIndexed { i, name -> OrbitSelectChip(name, i == segment, fonts, look, onClick = { segment = i }) }
    }

    Text("Switches: on, off, disabled", color = look.dim, style = fonts.body)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CosmoSwitch(switchA, { switchA = it })
        CosmoSwitch(switchB, { switchB = it })
        CosmoSwitch(true, {}, enabled = false)
    }
}

@Composable
private fun ContrastList(colors: CosmoColors, fonts: OrbitFonts, look: OrbitLook) {
    val palette = colors.palette
    val lines = remember(palette) { ContrastReport.lines(palette) }
    lines.forEach { line ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.label, Modifier.weight(1f), color = look.ink, style = fonts.body)
            Text("${decimals(line.ratio)} : 1 (rail ${decimals(line.rail)})", color = look.dim, style = fonts.body)
            Box(Modifier.width(8.dp))
            Text(if (line.passes) "pass" else "FAIL", color = look.ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

/** Two decimals without a locale: the list reads the same in every language. */
private fun decimals(x: Double): String {
    val hundredths = (x * 100).roundToInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
