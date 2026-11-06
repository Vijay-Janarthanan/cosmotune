/*
 * Source: FORK.md companion source record TV-0035
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.ui.tv.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.cosmotune.music.data.settings.TvSettings
import com.cosmotune.music.ui.tv.components.TvFocusIndication
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.PEACH
import com.cosmotune.ui.orbit.orbitFonts

/*
 * The TV in the Orbit language (Canvas repo, docs/superpowers/plans/2026-11-06-cosmotune-orbit-revamp.md; board batch-be section E):
 * the phone's Orbit kit drawn big for the sofa. The TV is dark only, as the platform is; the three themes differ only in the ground.
 */

/** One TV theme: the page's ground, the two ends of its preview canvas, and whether the playing cover washes the top of the page. */
@Immutable
data class TvColorPalette(
    val ground: Color,
    val canvasTop: Color,
    val canvasBottom: Color,
    val tintsCanvas: Boolean = true,
)

/** "Dynamic Artwork", the default: Orbit's warm near-black ground, washed at the top by the playing cover. */
val TvDarkPalette = TvColorPalette(ground = Color(0xFF0F0B0D), canvasTop = Color(0xFF1C1418), canvasBottom = Color(0xFF0F0B0D))

/** Pure black for OLED panels: no wash, every pixel off. */
val TvOledPalette = TvColorPalette(ground = Color.Black, canvasTop = Color.Black, canvasBottom = Color.Black, tintsCanvas = false)

/** Midnight: a deep navy ground. */
val TvMidnightPalette = TvColorPalette(ground = Color(0xFF080B14), canvasTop = Color(0xFF18213A), canvasBottom = Color(0xFF070A12))

val LocalTvColors = compositionLocalOf { TvDarkPalette }

object TvThemeColors {
    val current: TvColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalTvColors.current
}

object TvDimensions {
    val SafeMarginHorizontal = 48.dp
    val SafeMarginVertical = 22.dp
    val CardSpacing = 20.dp
    val ShelfSpacing = 26.dp

    /** Where scrolling content starts, so its first row clears the chips at the top. */
    val ContentTop = 84.dp
}

/**
 * Orbit's colours on the TV. Ink and its dim are the dark Orbit pair; fills are ink at a low alpha so they read on every ground.
 * Focus is the peach ring ([com.cosmotune.music.ui.tv.components.tvLift]); a focused row or button also turns to solid ink with
 * ground-coloured text, as a selected Orbit chip does.
 */
object TvGlass {
    val Ink = Color(0xFFF7ECE4)
    val Dim = Color(0xFFB49C90)

    /** A chip's fill (ink 8 %). */
    val Fill = Ink.copy(alpha = 0.08f)
    /** A primary button's fill, a touch brighter than [Fill]. */
    val FillStrong = Ink.copy(alpha = 0.14f)
    /** A switched-on toggle whose focus is elsewhere. */
    val FillSelected = Ink.copy(alpha = 0.24f)
    /** A row's fill (ink 5 %). */
    val Row = Ink.copy(alpha = 0.05f)
    /** Behind artwork that has not loaded. */
    val Placeholder = Color(0xFF2A2124)
    val Hairline = Ink.copy(alpha = 0.10f)

    /** A focused control: solid ink, ground-coloured text on it. */
    val Platter = Ink
    val OnPlatter = Color(0xFF1B1416)
    val OnPlatterSecondary = Color(0xFF1B1416).copy(alpha = 0.62f)

    val TextPrimary = Ink
    val TextSecondary = Dim
    val TextTertiary = Ink.copy(alpha = 0.38f)

    /** Peach: what is playing now, the play button, the focus ring. */
    val Peach = PEACH
    val OnPeach = Color(0xFF2A100A)
    val Destructive = Color(0xFFFF7A6B)
}

/** The 10-foot type ramp in Orbit's faces (Archivo wide for headings, Hanken Grotesk for text, mono for labels). */
class TvTypeSet(fonts: OrbitFonts) {
    val LargeTitle = TextStyle(fontFamily = fonts.wide, fontWeight = FontWeight(800), fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp)
    val Title = TextStyle(fontFamily = fonts.wide, fontWeight = FontWeight(800), fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp)
    val Section = TextStyle(fontFamily = fonts.wide, fontWeight = FontWeight(800), fontSize = 21.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp)
    val Headline = TextStyle(fontFamily = fonts.text, fontWeight = FontWeight(700), fontSize = 16.sp, lineHeight = 20.sp)
    val Body = TextStyle(fontFamily = fonts.text, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 18.sp)
    val Callout = TextStyle(fontFamily = fonts.text, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 18.sp)
    val CardTitle = TextStyle(fontFamily = fonts.text, fontWeight = FontWeight(700), fontSize = 13.sp, lineHeight = 16.sp)
    val Caption = TextStyle(fontFamily = fonts.text, fontWeight = FontWeight(500), fontSize = 11.5.sp, lineHeight = 14.sp)
    val Tab = TextStyle(fontFamily = fonts.text, fontWeight = FontWeight(700), fontSize = 15.sp, lineHeight = 18.sp)
    val Label = fonts.label
}

private val FallbackFonts = OrbitFonts(FontFamily.SansSerif, FontFamily.SansSerif, FontFamily.SansSerif)

val LocalTvType = staticCompositionLocalOf { TvTypeSet(FallbackFonts) }
val LocalTvFonts = staticCompositionLocalOf { FallbackFonts }

object TvType {
    val LargeTitle: TextStyle @Composable @ReadOnlyComposable get() = LocalTvType.current.LargeTitle
    val Title: TextStyle @Composable @ReadOnlyComposable get() = LocalTvType.current.Title
    val Section: TextStyle @Composable @ReadOnlyComposable get() = LocalTvType.current.Section
    val Headline: TextStyle @Composable @ReadOnlyComposable get() = LocalTvType.current.Headline
    val Body: TextStyle @Composable @ReadOnlyComposable get() = LocalTvType.current.Body
    val Callout: TextStyle @Composable @ReadOnlyComposable get() = LocalTvType.current.Callout
    val CardTitle: TextStyle @Composable @ReadOnlyComposable get() = LocalTvType.current.CardTitle
    val Caption: TextStyle @Composable @ReadOnlyComposable get() = LocalTvType.current.Caption
    val Tab: TextStyle @Composable @ReadOnlyComposable get() = LocalTvType.current.Tab
    val Label: TextStyle @Composable @ReadOnlyComposable get() = LocalTvType.current.Label
}

/** The Orbit fonts as the kit's pieces take them. */
@Composable
@ReadOnlyComposable
fun tvOrbitFonts(): OrbitFonts = LocalTvFonts.current

/** The Orbit look on the TV's ground, for the kit's pieces (cards, discs, buttons). */
@Composable
@ReadOnlyComposable
fun tvOrbitLook(): OrbitLook = OrbitLook(
    dark = true,
    ink = TvGlass.Ink,
    dim = TvGlass.Dim,
    chip = TvGlass.Fill,
    row = TvGlass.Row,
    accent = TvGlass.Peach,
    ground = LocalTvColors.current.ground,
)

// ─────────────────────────────────────────────────────────────────────────────
// Theme wrapper — reacts directly to TvSettings.tvTheme
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CosmotuneTvTheme(
    content: @Composable () -> Unit,
) {
    val tvThemeId by TvSettings.tvTheme.collectAsState()
    val tvUiScale by TvSettings.tvUiScale.collectAsState()

    val palette = when (tvThemeId.lowercase()) {
        "pure_black", "oled", "pure_black_oled" -> TvOledPalette
        "midnight" -> TvMidnightPalette
        else -> TvDarkPalette
    }

    val colorScheme = darkColorScheme(
        primary = TvGlass.Peach,
        onPrimary = TvGlass.OnPeach,
        background = palette.ground,
        onBackground = TvGlass.Ink,
        surface = palette.ground,
        onSurface = TvGlass.Ink,
        onSurfaceVariant = TvGlass.Dim,
    )

    // Apply user DPI scale by overriding LocalDensity for the entire TV UI tree
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, tvUiScale) {
        Density(density = baseDensity.density * tvUiScale, fontScale = baseDensity.fontScale * tvUiScale)
    }
    val fonts = orbitFonts()
    val type = remember(fonts) { TvTypeSet(fonts) }

    CompositionLocalProvider(
        LocalTvColors provides palette,
        LocalTvFonts provides fonts,
        LocalTvType provides type,
        LocalDensity provides scaledDensity,
        // Anything clickable that is not the TV's own lifted piece (the kit's chips and buttons, Watch's pages) shows focus as a ring.
        LocalIndication provides TvFocusIndication,
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
