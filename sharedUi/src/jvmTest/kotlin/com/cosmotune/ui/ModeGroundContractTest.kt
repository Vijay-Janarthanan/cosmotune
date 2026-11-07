package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Watch, Read and Manga paint no ground of their own, and the phone's window is black in both themes (android:windowBackground),
 * so in the light theme their dark ink stood on black. The phone's host lays [com.cosmotune.ui.theme.ModeGround] (the palette's
 * ambient ground, as Music's root and the desktop's frame paint it) under each of the three; the TV's dark-only theme stays the
 * TV's. ModeGroundRenderTest (uiTests) draws the modes and checks the pixels; this pins the wiring that a window is needed to see.
 */
class ModeGroundContractTest {
    private val host = "app/src/main/java/com/cosmotune/video/host/PhoneModeHost.kt"

    private fun flat(path: String) = SourceContract.code(SourceContract.read(path)).replace(Regex("\\s+"), " ")

    /** The body of the phone host's slot for [key] (`holder.SaveableStateProvider("key") { ... }`), up to the next slot. */
    private fun slot(src: String, key: String): String {
        val start = src.indexOf("holder.SaveableStateProvider(\"$key\") {")
        assertTrue(start >= 0, "the phone host has a $key slot")
        val next = src.indexOf("holder.SaveableStateProvider(", start + 1).let { if (it < 0) src.length else it }
        return src.substring(start, next)
    }

    @Test fun thePhoneLaysTheThemesGroundUnderWatchReadAndManga() {
        val src = flat(host)
        for ((key, app) in listOf("manga" to "MangaApp(", "read" to "ReadApp(", "watch" to "WatchApp(")) {
            val body = slot(src, key)
            val ground = body.indexOf("ModeGround {")
            assertTrue(ground >= 0, "the $key slot lays ModeGround")
            assertTrue(body.indexOf(app) > ground, "$app is drawn inside the ground")
        }
    }

    @Test fun theModeGroundIsTheAmbientGroundOfThePalette() {
        val src = flat("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/theme/AmbientBackground.kt")
        assertTrue("fun ModeGround(" in src)
        val body = src.substringAfter("fun ModeGround(").substringBefore("fun ")
        assertTrue("fillMaxSize().cosmoAmbientGround()" in body, "the whole page, from LocalCosmoColors: the palette the ink is read from")
    }

    @Test fun theTvsDarkOnlyThemeStaysOnTheTv() {
        // Watch on the TV is dark whatever the theme (CosmoLookTheme(dark = true) in TvWatchScreen); a dark theme forced in the
        // shared screens would give the phone and the desktop a dark ground under the light theme's chrome.
        val roots = listOf("sharedUi/src/jvmSharedMain", "manga/src/jvmSharedMain", "app/src/main/java/com/cosmotune/video/host")
        val offenders = roots.flatMap { root ->
            File(SourceContract.root, root).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
                .filter { Regex("""CosmoLookTheme\(\s*dark\s*=\s*true""").containsMatchIn(SourceContract.code(it.readText())) }
                .map { it.relativeTo(SourceContract.root).invariantSeparatorsPath }.toList()
        }
        assertEquals(emptyList(), offenders)
    }
}
