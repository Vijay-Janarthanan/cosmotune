package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.replay.OrbitReplayHeading
import com.cosmotune.ui.replay.OrbitReplayHero
import com.cosmotune.ui.replay.OrbitReplayRow
import com.cosmotune.ui.replay.OrbitReplayShareBody
import com.cosmotune.ui.replay.OrbitReplayShareButton
import com.cosmotune.ui.replay.OrbitReplayStat
import com.cosmotune.ui.replay.OrbitReplayStats
import com.cosmotune.ui.replay.OrbitReplayTile
import com.cosmotune.ui.replay.OrbitReplayTiles
import com.cosmotune.ui.replay.OrbitStoryCard
import com.cosmotune.ui.replay.OrbitStoryChrome
import com.cosmotune.ui.replay.OrbitStoryLeader
import com.cosmotune.ui.replay.OrbitStoryOrbit
import com.cosmotune.ui.replay.orbitReplayChart
import com.cosmotune.ui.replay.orbitStoryColors
import com.cosmotune.ui.sheets.CosmoSheetSurface
import com.cosmotune.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Not an assertion: Replay in Orbit (the page at phone and desktop widths, a story card, the share sheet) drawn offscreen in the
 * dark and the light palettes and written under build/old-screens/replay for a person to look at.
 */
class ReplayOrbitVisualReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun cover(name: String, r: Int, g: Int, b: Int): String {
        val image = BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let {
            it.color = java.awt.Color(r, g, b); it.fillRect(0, 0, 320, 320)
            it.color = java.awt.Color(minOf(r + 70, 255), minOf(g + 70, 255), minOf(b + 70, 255)); it.fillOval(50, 50, 220, 220)
            it.dispose()
        }
        return temporary.newFile("$name.png").also { ImageIO.write(image, "png", it) }.path
    }

    private fun hosts(block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = playerField.get(null)
        PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                "getSettings" -> java.lang.reflect.Proxy.newProxyInstance(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java.classLoader, arrayOf(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java)) { _, _, _ -> MutableStateFlow(false) }
                else -> error("Unexpected ${method.name}")
            }
        } as PlayerHost)
        try { block() } finally { playerField.set(null, previous) }
    }

    private val palettes = listOf(
        "dark" to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(true, false), true),
        "light" to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(false, false), false),
    )

    private fun page(palette: CosmoPaletteState, width: Dp, height: Dp, content: @Composable () -> Unit) {
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(width, height).testTag("replay:review").background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        val file = File("../build/old-screens/replay/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("replay:review").captureToImage().toAwtImage(), "png", file)
    }

    private class Art(val songs: List<String>, val faces: List<String>)

    private fun art() = Art(
        songs = listOf(cover("s1", 168, 92, 40), cover("s2", 30, 120, 116), cover("s3", 120, 60, 140), cover("s4", 40, 90, 160), cover("s5", 150, 40, 70)),
        faces = listOf(cover("f1", 90, 130, 50), cover("f2", 160, 110, 30), cover("f3", 60, 60, 120)),
    )

    private fun songRows(art: Art) = listOf(
        OrbitReplayRow("v1", 1, "Golden Hour", "Mira and the Coast · 6 hr 12 min", art.songs[0], 84),
        OrbitReplayRow("v2", 2, "Low Tide Letters", "Harbour Lights · 4 hr 40 min", art.songs[1], 61),
        OrbitReplayRow("v3", 3, "Lantern Season", "Mira and the Coast · 3 hr 5 min", art.songs[2], 40),
        OrbitReplayRow("v4", 4, "Paper Planets", "The Quiet Orbit · 2 hr 51 min", art.songs[3], 37),
        OrbitReplayRow("v5", 5, "Saltwater Radio", "Harbour Lights · 2 hr 2 min", art.songs[4], 26),
    )

    private fun artistRows(art: Art) = listOf(
        OrbitReplayRow("a1", 1, "Mira and the Coast", "14 hr 3 min", art.faces[0], 190),
        OrbitReplayRow("a2", 2, "Harbour Lights", "9 hr 48 min", art.faces[1], 131),
        OrbitReplayRow("a3", 3, "The Quiet Orbit", "5 hr 20 min", art.faces[2], 70),
    )

    @Composable
    private fun ReplayPage(art: Art, side: Dp, first: Int) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(first), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("heading") { OrbitReplayHeading("Replay", "2026", listOf("This week", "This month", "This year", "All time"), 2, {}, side) }
            item("hero") {
                OrbitReplayHero("Minutes listened", "12,480", null, "1,284 plays · 2026", art.songs[0], "Play your Replay", {}, {}, side)
            }
            item("cards") {
                OrbitReplayTiles(
                    listOf(
                        OrbitReplayTile("song", "Top song", "Golden Hour", "Mira and the Coast · 84 plays", art.songs[0]),
                        OrbitReplayTile("artist", "Top artist", "Mira and the Coast", "14 hr 3 min · 190 plays", art.faces[0]),
                        OrbitReplayTile("album", "Top album", "Tidelines", "Mira and the Coast · 8 hr 1 min", art.songs[2]),
                    ),
                    side,
                ) {}
            }
            orbitReplayChart("songs", "Top songs", songRows(art), side, onClick = {})
            orbitReplayChart("artists", "Top artists", artistRows(art), side, onClick = {})
            orbitReplayChart(
                "genres", "Top genres",
                listOf(OrbitReplayRow("g1", 1, "Indie pop", "11 hr 2 min", null, 0), OrbitReplayRow("g2", 2, "Dream pop", "6 hr 40 min", null, 0)),
                side, onClick = {}, icon = CosmoIcons.Waveform,
            )
            item("habits") {
                OrbitReplayStats(
                    "Your listening pattern",
                    listOf(OrbitReplayStat("Songs", "412"), OrbitReplayStat("Artists", "138"), OrbitReplayStat("Albums", "96")),
                    listOf("You listen most around 9 pm.", "Your biggest day was 14 August with 4 hr 2 min of listening."),
                    dailyMs = mapOf(java.time.LocalDate.now().toString() to 3_600_000L, java.time.LocalDate.now().minusDays(2).toString() to 1_800_000L),
                    hourly = List(24) { if (it in 18..22) 3_600_000L else 600_000L },
                    side = side,
                )
            }
            item("share") { OrbitReplayShareButton("Share my Replay", {}, side) }
        }
    }

    @Test fun thePage() = hosts {
        val art = art()
        for ((name, palette) in palettes) {
            // The test window is 1024 by 768, so the page is drawn in sections: the top, the charts, the listening's shape.
            for ((section, first) in listOf("top" to 0, "songs" to 3, "artists" to 10, "shape" to 19)) {
                page(palette, 390.dp, 760.dp) { ReplayPage(art, 20.dp, first) }
                capture("page-phone-$section-$name")
            }
            for ((section, first) in listOf("top" to 0, "shape" to 19)) {
                page(palette, 1000.dp, 760.dp) { ReplayPage(art, 16.dp, first) }
                capture("page-desktop-$section-$name")
            }
        }
    }

    @Test fun aStoryAndTheShareSheet() = hosts {
        val art = art()
        for ((name, palette) in palettes) {
            // 9:16, as the story frame holds it.
            page(palette, 390.dp, 693.dp) {
                val colors = orbitStoryColors(art.songs[0], "SONGS")
                Box(Modifier.fillMaxSize()) {
                    OrbitStoryCard(colors, listOf("You played" to false, " 1,284 songs" to true, " this year." to false)) {
                        val rows = songRows(art)
                        OrbitStoryLeader(rows[0].copy(subtitle = "Mira and the Coast"), "6 hr 12 min · 84 plays", rows.drop(1).map { it.copy(plays = 0) }, colors, ring = { 0.4f })
                    }
                    OrbitStoryChrome("Replay 26", 8, 3, 0.4f, colors, "Share my Replay", {}, "Close Replay", {})
                }
            }
            capture("story-songs-$name")
            page(palette, 390.dp, 693.dp) {
                val colors = orbitStoryColors(art.songs[1], "INTRO")
                Box(Modifier.fillMaxSize()) {
                    OrbitStoryCard(colors, listOf("This is your" to false, " Replay" to true, ", the year in music." to false)) {
                        OrbitStoryOrbit(art.songs.take(3) + art.faces, colors, ring = { 0.15f }, footnote = "Built privately from your listening history on this device.")
                    }
                    OrbitStoryChrome("Replay 26", 8, 0, 0.15f, colors, "Share my Replay", {}, "Close Replay", {})
                }
            }
            capture("story-intro-$name")
            page(palette, 390.dp, 844.dp) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                    CosmoSheetSurface(Modifier.align(Alignment.BottomCenter)) {
                        OrbitReplayShareBody(
                            "Replay", "Share my Replay", "A full picture with songs, artists, albums, genres, and habits.",
                            "Save", saved = false, onSave = {}, shareLabel = "Share", onShare = {}, ready = true,
                        ) { Box(Modifier.fillMaxSize().background(orbitStoryColors(art.songs[0], "poster").fill)) }
                    }
                }
            }
            capture("share-sheet-$name")
        }
    }
}
