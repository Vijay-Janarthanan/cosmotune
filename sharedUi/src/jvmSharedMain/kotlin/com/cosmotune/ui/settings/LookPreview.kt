package com.cosmotune.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import com.cosmotune.design.ContrastReport
import com.cosmotune.design.GlassMode
import com.cosmotune.design.LookSamples
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.components.CoverFrame
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.WaveScrubber
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.components.rememberGlassMode
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.cosmoAmbientGround
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.cosmo_look_preview
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/*
 * The "Preview the look" style sheet (design 07, step 1): the tokens and the controls of the new look, on the six sample
 * covers, in the dark and the light theme. It is a page for the owner to judge the look, not a screen of the app, so its own
 * labels are plain English; only the title uses a string resource (the Settings entry names it the same way).
 *
 * It owns a palette of its own (`CosmoPaletteState`), so it works with the New look switch off and never touches the palette
 * of the song that is playing.
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
        val palette = colors.palette
        val ink = Color(palette.textPrimary)
        val ink2 = Color(palette.textSecondary)
        val type = LocalCosmoType.current
        Box(Modifier.fillMaxSize().cosmoAmbientGround(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.cosmo_look_preview), Modifier.weight(1f), color = ink, style = type.title(TypeScale.TITLE_L))
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Close", onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = ink) }
                }

                Section("Cover", ink2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LookSamples.all.forEachIndexed { i, sample ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .selectable(selected = i == selected, role = Role.RadioButton, onClick = { selected = i })
                                    .semantics { contentDescription = "${sample.name} cover" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Canvas(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(3.dp)
                                        .clip(SuperellipseShape(12.dp))
                                        .then(if (i == selected) Modifier.border(BorderStroke(2.dp, ink), SuperellipseShape(12.dp)) else Modifier),
                                ) {
                                    drawRect(Color(sample.a), size = Size(size.width / 2f, size.height))
                                    drawRect(Color(sample.b), Offset(size.width / 2f, 0f), Size(size.width / 2f, size.height))
                                }
                            }
                        }
                    }
                    // Replace with CosmoSegmented (Dark | Light) after the merge of task P3-6.
                    ThemeToggle(dark, ink, ink2) { dark = it }
                }

                Section("Colours", ink2) { Swatches(colors, ink, ink2) }

                Section("Corner radii", ink2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("xs" to CosmoRadii.xs, "sm" to CosmoRadii.sm, "md" to CosmoRadii.md, "lg" to CosmoRadii.lg, "xl" to CosmoRadii.xl, "2xl" to CosmoRadii.xxl)
                            .forEach { (name, radius) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(48.dp).clip(SuperellipseShape(radius)).drawBehind { drawRect(colors.live(CoverField.SURFACE_HIGH)) })
                                    Text(name, color = ink2, style = type.body(TypeScale.CAPTION))
                                }
                            }
                    }
                }

                Section("Type", ink2) {
                    Text("Wild Hearts", color = ink, style = type.display(TypeScale.DISPLAY_M))
                    Text("The cover sets the colour", color = ink, style = type.title(TypeScale.TITLE_L))
                    Text("Songs you keep coming back to", color = ink, style = type.title(TypeScale.TITLE_S))
                    Text("Body text in Hanken Grotesk, with a line height that reads easily over many lines of text.", color = ink, style = type.body())
                    Text("Label and metadata", color = ink2, style = type.label)
                    Text("Caption 3:42", color = ink2, style = type.body(TypeScale.CAPTION))
                }

                Section("Cover and glass", ink2) { GlassSample(colors, selected, ink, ink2) }

                Section("Slider and wavy scrubber", ink2) { Bars(ink, ink2) }

                // P3-6 controls go here after the merge. The section must show, at rest, pressed (an always-pressed target) and
                // disabled, on this ground: buttons of every kind, icon buttons, the play button, chips, the segmented
                // control and the switch. Until then it only lists them.
                Section("Buttons, chips and switches", ink2) { ControlsStub(ink2) }

                Section("Contrast", ink2) { ContrastList(colors, ink, ink2) }
            }
        }
    }
}

@Composable
private fun Section(title: String, color: Color, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), color = color, style = LocalCosmoType.current.label.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
        content()
    }
}

/** Dark | Light. A stand-in for `CosmoSegmented`, which task P3-6 builds. */
@Composable
private fun ThemeToggle(dark: Boolean, ink: Color, ink2: Color, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).border(BorderStroke(1.dp, ink2.copy(alpha = 0.35f)), CircleShape)) {
        listOf(true to "Dark", false to "Light").forEach { (isDark, label) ->
            val on = dark == isDark
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .then(if (on) Modifier.drawBehind { drawRect(ink.copy(alpha = 0.16f)) } else Modifier)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onChange(isDark) }),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) ink else ink2, fontWeight = FontWeight.SemiBold) }
        }
    }
}

private val fieldNames = listOf(
    "seed", "ambient", "bgTop", "bgMid", "bgBottom", "surface", "surfaceHigh", "surfaceVariant", "text", "text2", "text3",
    "accent", "accentStrong", "onAccent", "btnStart", "btnEnd", "container", "onContainer", "glow", "shadow", "scrim",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(colors: CosmoColors, ink: Color, ink2: Color) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fieldNames.forEachIndexed { field, name ->
            Column(Modifier.width(64.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(SuperellipseShape(10.dp))
                        .border(BorderStroke(1.dp, ink.copy(alpha = 0.14f)), SuperellipseShape(10.dp))
                        // Read while drawing: the swatches fade with the palette.
                        .drawBehind { drawRect(colors.live(field)) },
                )
                Text(name, color = ink2, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/** The sample cover as a stripe picture: [LookSamples] stripes, scaled to fill. */
@Composable
private fun StripeCover(selected: Int, modifier: Modifier = Modifier) {
    val sample = LookSamples.all[selected]
    Canvas(modifier) {
        val stripe = size.width / 8f
        drawRect(Color(sample.b))
        for (i in 0 until 8 step 2) drawRect(Color(sample.a), Offset(i * stripe, 0f), Size(stripe, size.height))
    }
}

@Composable
private fun GlassSample(colors: CosmoColors, selected: Int, ink: Color, ink2: Color) {
    val haze = remember { HazeState() }
    val type = LocalCosmoType.current
    val mode = rememberGlassMode(hasBackdrop = true, overVideo = false)
    Box(Modifier.fillMaxWidth().height(200.dp).clip(SuperellipseShape(CosmoRadii.xl))) {
        StripeCover(selected, Modifier.fillMaxSize().hazeSource(haze))
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .fillMaxWidth()
                .cosmoGlass(haze, SuperellipseShape(CosmoRadii.lg))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverFrame(size = 52.dp, radius = CosmoRadii.coverMini) { StripeCover(selected, Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f)) {
                Text("Glass over the cover", color = ink, style = type.title(TypeScale.TITLE_S), maxLines = 1)
                Text(
                    if (mode == GlassMode.BLUR) "Blurred backdrop" else "Solid tint (no blur on this device or setting)",
                    color = ink2, style = type.body(TypeScale.CAPTION), maxLines = 2,
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverFrame(size = 120.dp, radius = CosmoRadii.coverNowPlaying, shadow = true) { StripeCover(selected, Modifier.fillMaxSize()) }
        Text("The cover keeps its own coloured shadow, an inner rim and superellipse corners.", Modifier.weight(1f), color = ink2, style = type.body(TypeScale.CAPTION))
    }
}

@Composable
private fun Bars(ink: Color, ink2: Color) {
    val type = LocalCosmoType.current
    var volume by remember { mutableFloatStateOf(0.6f) }
    var position by remember { mutableFloatStateOf(0.35f) }
    var playing by remember { mutableStateOf(true) }
    LaunchedEffect(playing) {
        while (playing) { delay(250); position = (position + 0.0125f) % 1f }
    }
    Text("Slider (volume and equaliser)", color = ink2, style = type.body(TypeScale.CAPTION))
    CosmoSlider(volume, { volume = it }, contentDescription = "Volume sample")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Wave, ${if (playing) "playing" else "paused"}", Modifier.weight(1f), color = ink2, style = type.body(TypeScale.CAPTION))
        Box(
            Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button) { playing = !playing }.padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (playing) "Pause" else "Play", color = ink, fontWeight = FontWeight.SemiBold) }
    }
    WaveScrubber(fraction = { position }, playing = playing, onScrub = { position = it }, onRelease = {})
    Text("Wave, paused (flat)", color = ink2, style = type.body(TypeScale.CAPTION))
    WaveScrubber(fraction = { 0.5f }, playing = false, onScrub = {}, onRelease = {})
}

// The controls task P3-6 builds, as the section must show them after the merge.
private val controlsToCome = listOf(
    "Buttons: primary, secondary, tertiary and destructive, at rest, pressed and disabled",
    "Icon buttons, at rest and on",
    "The play button, paused and playing (a tap toggles)",
    "Chips, the segmented control and switches",
)

@Composable
private fun ControlsStub(ink2: Color) {
    val type = LocalCosmoType.current
    controlsToCome.forEach { Text("Arrives with the controls task: $it", color = ink2, style = type.body(TypeScale.CAPTION)) }
}

@Composable
private fun ContrastList(colors: CosmoColors, ink: Color, ink2: Color) {
    val type = LocalCosmoType.current
    val palette = colors.palette
    val lines = remember(palette) { ContrastReport.lines(palette) }
    lines.forEach { line ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.label, Modifier.weight(1f), color = ink, style = type.body(TypeScale.CAPTION))
            Text("${decimals(line.ratio)} : 1 (rail ${decimals(line.rail)})", color = ink2, style = type.body(TypeScale.CAPTION))
            Spacer8()
            Text(if (line.passes) "pass" else "FAIL", color = ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Spacer8() = Box(Modifier.width(8.dp))

/** Two decimals without a locale: the list reads the same in every language. */
private fun decimals(x: Double): String {
    val hundredths = (x * 100).roundToInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
