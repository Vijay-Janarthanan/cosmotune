package com.cosmotune.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BC-22, revised for Look and colours > App icon: the upstream icon alternatives are still not adopted (their artwork is
 * upstream's brand). The launcher entries are activity-aliases of MainActivity, one per [com.cosmotune.settings.AppIcon], each
 * a recolour of the Cosmotune mark; only the usual one is enabled on a new install, and the application ids are unchanged.
 * Moving the launcher entry off MainActivity can drop an existing home-screen shortcut on some launchers once, on the update
 * that brings the aliases (the app drawer entry stays); that trade-off came with the request for the icon choice.
 */
class LauncherIdentityTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val manifest = File(root, "app/src/main/AndroidManifest.xml").readText().replace("\r\n", "\n")
    private val aliases = Regex("""<activity-alias\s[\s\S]*?</activity-alias>""").findAll(manifest).map { it.value }.toList()

    @Test fun everyLauncherEntryIsAnIconAliasOfMainActivity() {
        val activity = Regex("""<activity\s[^>]*android:name="\.MainActivity"[\s\S]*?</activity>""").find(manifest)?.value.orEmpty()
        assertTrue("MainActivity still answers MAIN", "android.intent.action.MAIN" in activity)
        assertFalse("the launcher category moved to the aliases", "android.intent.category.LAUNCHER" in activity)
        assertEquals("one alias per app icon", com.cosmotune.settings.AppIcon.entries.map { ".${it.alias}" }.toSet(),
            aliases.map { Regex("""android:name="([^"]+)"""").find(it)!!.groupValues[1] }.toSet())
        assertTrue("each alias opens MainActivity", aliases.all { "android:targetActivity=\".MainActivity\"" in it && "android.intent.category.LAUNCHER" in it })
        assertEquals("one launcher category per alias", aliases.size, Regex("android.intent.category.LAUNCHER").findAll(manifest).count())
        val enabled = aliases.filter { "android:enabled=\"true\"" in it }
        assertEquals("only the usual icon is on for a new install", 1, enabled.size)
        assertTrue(".IconCosmotune" in enabled.single())
        assertTrue("the usual adaptive icon on the application", Regex("""<application[\s\S]*?android:icon="@mipmap/ic_launcher"""").containsMatchIn(manifest))
    }

    @Test fun theUpdateIdentityIsUnchanged() {
        val build = File(root, "app/build.gradle.kts").readText()
        assertTrue("applicationId = \"com.cosmotune.app\"" in build)
        assertTrue("applicationId = \"com.cosmotune.dev\"" in build)
    }

    @Test fun onlyRecolouredCosmotuneIconsAreShipped() {
        val res = File(root, "app/src/main/res")
        val mipmaps = res.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap") }.orEmpty()
        val names = mipmaps.flatMap { it.listFiles().orEmpty().map { f -> f.nameWithoutExtension } }.toSet()
        assertEquals(setOf("ic_launcher", "ic_launcher_round", "ic_launcher_moonlight", "ic_launcher_dawn", "ic_launcher_midnight"), names)
        // The variants draw the same mark: the foreground paths are the usual icon's, only the colours differ.
        val usual = File(res, "drawable/ic_launcher_foreground.xml").readText()
        val paths = Regex("""android:pathData="([^"]+)"""").findAll(usual).map { it.groupValues[1] }.toList()
        for (variant in listOf("moonlight", "dawn")) {
            val drawn = File(res, "drawable/ic_launcher_foreground_$variant.xml").readText()
            assertEquals(paths, Regex("""android:pathData="([^"]+)"""").findAll(drawn).map { it.groupValues[1] }.toList())
        }
        assertFalse(File(root, "app/src/main/java/com/cosmotune/music/AppIcon.kt").exists())
    }
}
