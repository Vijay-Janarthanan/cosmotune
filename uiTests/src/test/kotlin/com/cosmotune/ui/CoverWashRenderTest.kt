package com.cosmotune.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.theme.CoverColors
import com.cosmotune.theme.CoverPalette
import com.cosmotune.theme.CoverPaletteSpec
import com.cosmotune.theme.CoverWash
import com.cosmotune.theme.layered
import com.cosmotune.ui.orbit.HeroBite
import com.cosmotune.ui.orbit.coverCardTint
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.cosmoAmbientGround
import com.cosmotune.ui.theme.cosmoTopGround
import com.cosmotune.ui.theme.rememberCosmoType
import com.cosmotune.ui.theme.toColorScheme
import com.cosmotune.ui.theme.toMaterialTypography
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The cover's wash as the app draws it: for each synthetic cover (the owner's mostly white cover with red blood at the foot, a warm
 * brown photograph, blue neon, a black and white photograph), in the dark and the light theme, the Now Playing ground with the song
 * card on it (phone and desktop: `cosmoAmbientGround(artwork = true)`), and a page with the desktop frame's ground and the top bar's
 * band (`cosmoAmbientGround()`, `cosmoTopGround`). Written to build/wash/ for a look, and checked on the pixels: the top of each ground
 * keeps the card's hue and the text reads on it. The palette's old accent-container ground is drawn beside it ("before") for comparison.
 */
class CoverWashRenderTest {
    @get:Rule val compose = createComposeRule()

    private class Cover(val name: String, val pixels: IntArray) {
        fun palette(dark: Boolean): CoverColors = CoverPalette.fromPixels(pixels, SIDE, SIDE, dark = dark, spec = CoverPaletteSpec().layered())
        fun bitmap(): ImageBitmap = BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, SIDE, SIDE, pixels, 0, SIDE) }.toComposeImageBitmap()
    }

    private companion object {
        const val SIDE = 64
        fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        fun cover(name: String, paint: (x: Int, y: Int, noise: Random) -> Int): Cover {
            val noise = Random(name.hashCode())
            return Cover(name, IntArray(SIDE * SIDE) { paint(it % SIDE, it / SIDE, noise) })
        }

        // The same covers as CoverWashTest (sharedUi).
        val covers = listOf(
            cover("white with a red patch") { x, y, n ->
                val j = n.nextInt(-6, 7)
                when {
                    y >= 53 && x in 6..57 -> rgb(178 + j, 14, 22)
                    (x in 15..23 || x in 39..47) && y in 12..16 -> rgb(214 + j, 166, 138)
                    (x in 13..25 || x in 37..49) && y in 17..46 -> rgb(28 + j, 26, 30)
                    else -> rgb(244 + j / 2, 242 + j / 2, 238)
                }
            },
            cover("warm brown photo") { x, y, n ->
                val lift = (60 - (abs(x - 32) + abs(y - 30))).coerceAtLeast(0)
                val g = n.nextInt(-10, 11)
                rgb(112 + lift + g, 76 + lift * 3 / 4 + g, 48 + lift / 2 + g)
            },
            cover("blue neon") { x, y, n ->
                val tube = (y in 18..21 || y in 40..43) && x in 8..55 || (x in 30..33 && y in 18..43)
                val glow = (y in 16..23 || y in 38..45) && x in 6..57
                when {
                    tube -> rgb(40 + n.nextInt(0, 20), 150 + n.nextInt(0, 30), 255)
                    glow -> rgb(14, 40, 120 + n.nextInt(0, 20))
                    else -> rgb(8 + n.nextInt(0, 6), 8 + n.nextInt(0, 6), 18 + n.nextInt(0, 8))
                }
            },
            cover("grey black and white") { x, y, n ->
                val v = (40 + (x + y) * 2 + n.nextInt(-12, 13)).coerceIn(0, 255)
                rgb(v, v, v)
            },
        )

        fun hueDistance(a: Double, b: Double) = abs(((b - a + 540.0) % 360.0) - 180.0)

        /** The old artwork ground: the accent container, mixed toward black or white (Compose's lerp) until the text read on it. */
        fun before(palette: CoverColors): Color {
            val ground = Color(palette.accentContainer)
            val ink = palette.textPrimary
            if (CoverWash.contrast(ground.toArgb(), ink) >= 4.5) return ground
            val toward = if (CoverWash.luminance(ink) > 0.18) Color.Black else Color.White
            var lo = 0f; var hi = 1f
            repeat(20) { val mid = (lo + hi) / 2; if (CoverWash.contrast(androidx.compose.ui.graphics.lerp(ground, toward, mid).toArgb(), ink) >= 4.5) hi = mid else lo = mid }
            return androidx.compose.ui.graphics.lerp(ground, toward, hi)
        }
    }

    private val width = 1100
    private val height = 560

    @Composable
    private fun Scene(cover: Cover, palette: CoverColors, dark: Boolean) {
        val bitmap = remember(cover) { cover.bitmap() }
        val tint = remember(cover) { coverCardTint(cover.palette(dark = true)) }
        val ink = Color(palette.textPrimary)
        val dim = Color(palette.textSecondary)
        Row(Modifier.size(width.dp, height.dp).testTag("wash:scene")) {
            // Now Playing: the ground wearing the cover, the disc and the song card on it.
            Box(Modifier.width(480.dp).fillMaxHeight().testTag("wash:player").cosmoAmbientGround(artwork = true)) {
                Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", color = dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(18.dp))
                    Image(bitmap, null, Modifier.size(220.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.height(22.dp))
                    Box(Modifier.fillMaxWidth().height(170.dp).clip(HeroBite).background(tint.fill).padding(24.dp)) {
                        Column {
                            Text("Vizhigale", color = tint.ink, fontSize = 30.sp, fontWeight = FontWeight.Black)
                            Text(cover.name, color = tint.ink.copy(alpha = 0.8f), fontSize = 15.sp)
                        }
                    }
                }
            }
            // A page on the desktop frame's ground, under the top bar's band.
            Box(Modifier.width(440.dp).fillMaxHeight().testTag("wash:page").cosmoAmbientGround()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(64.dp).cosmoTopGround(shown = { 1f }).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
                        Text("Home", color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Good evening", color = ink, fontSize = 34.sp, fontWeight = FontWeight.Black)
                        Text("Picked from what you play", color = dim, fontSize = 16.sp)
                        Text("TERTIARY LABEL", color = Color(palette.textTertiary), fontSize = 12.sp)
                    }
                }
            }
            // Before: the old artwork ground (the accent container) beside the card, for comparison.
            Column(Modifier.width(180.dp).fillMaxHeight().background(Color(palette.backgroundBottom)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("before", color = dim, fontSize = 12.sp)
                Box(Modifier.fillMaxWidth().height(120.dp).background(before(palette)))
                Text("card", color = dim, fontSize = 12.sp)
                Box(Modifier.fillMaxWidth().height(120.dp).background(tint.fill))
            }
        }
    }

    private fun draw(cover: Cover, dark: Boolean): CoverColors {
        val palette = cover.palette(dark)
        val state = CosmoPaletteState(palette, dark)
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(state) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()),
                typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Scene(cover, palette, dark)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        return palette
    }

    private fun shot(tag: String): BufferedImage = compose.onNodeWithTag(tag).captureToImage().toAwtImage().let { image ->
        BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
    }

    private fun describe(argb: Int) = CoverWash.oklch(argb).let { "#%06X (L %.3f C %.3f h %.0f)".format(argb and 0xFFFFFF, it[0], it[1], it[2]) }

    @Test fun theWashMatchesTheCardInBothThemes() {
        val sheet = BufferedImage(width, height * covers.size * 2, BufferedImage.TYPE_INT_ARGB)
        val g = sheet.createGraphics()
        var row = 0
        for (cover in covers) for (dark in listOf(true, false)) {
            val palette = draw(cover, dark)
            val theme = if (dark) "dark" else "light"
            val scene = shot("wash:scene")
            val file = File("../build/wash/${cover.name.replace(' ', '-')}-$theme.png").apply { parentFile.mkdirs() }
            ImageIO.write(scene, "png", file)
            g.drawImage(scene, 0, row++ * height, null)

            val card = coverCardTint(cover.palette(dark = true)).fill.toArgb()
            val player = shot("wash:player")
            val page = shot("wash:page")
            // The top corners: ground only (the content is padded in from them).
            val playerTop = player.getRGB(4, 4) or (0xFF shl 24)
            val pageTop = page.getRGB(page.width - 4, 4) or (0xFF shl 24)
            println("${cover.name}, $theme: card ${describe(card)}, player top ${describe(playerTop)}, page top ${describe(pageTop)}, before ${describe(before(palette).toArgb())}")
            for ((what, top) in listOf("player" to playerTop, "page" to pageTop)) {
                for (ink in listOf(palette.textPrimary, palette.textSecondary, palette.textTertiary)) {
                    assertTrue(CoverWash.contrast(top, ink) >= 4.45, "${cover.name}, $theme $what: ${describe(ink)} on ${describe(top)} is ${CoverWash.contrast(top, ink)}")
                }
                val c = CoverWash.oklch(card)
                val t = CoverWash.oklch(top)
                if (c[1] >= 0.03 && t[1] >= 0.02) assertTrue(hueDistance(c[2], t[2]) <= 15.0, "${cover.name}, $theme $what: ${describe(top)} has the hue of the card ${describe(card)}")
                if (palette.isFallback) assertTrue(t[1] < 0.02, "${cover.name}, $theme $what: a grey cover's ground is neutral: ${describe(top)}")
            }
        }
        g.dispose()
        ImageIO.write(sheet, "png", File("../build/wash/all.png"))
    }
}
