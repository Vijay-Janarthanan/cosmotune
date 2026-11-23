package com.cosmotune.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BC-22: the upstream icon alternatives are not adopted (their artwork is upstream's brand), so the phone keeps one launcher
 * entry on MainActivity under the same application ids. An update must not move that entry: an alias-based icon switcher
 * would replace the launcher component and drop existing home-screen icons, so adding one needs owner-supplied artwork and a
 * deliberate change to this test.
 */
class LauncherIdentityTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val manifest = File(root, "app/src/main/AndroidManifest.xml").readText().replace("\r\n", "\n")

    @Test fun mainActivityIsTheOnlyLauncherEntry() {
        assertEquals("one launcher category", 1, Regex("android.intent.category.LAUNCHER").findAll(manifest).count())
        val activity = Regex("""<activity\s[^>]*android:name="\.MainActivity"[\s\S]*?</activity>""").find(manifest)?.value.orEmpty()
        assertTrue("MainActivity holds MAIN/LAUNCHER", "android.intent.action.MAIN" in activity && "android.intent.category.LAUNCHER" in activity)
        assertFalse("no activity-alias in the phone manifest", "<activity-alias" in manifest)
        assertTrue("the single adaptive icon", Regex("""<application[\s\S]*?android:icon="@mipmap/ic_launcher"""").containsMatchIn(manifest))
    }

    @Test fun theUpdateIdentityIsUnchanged() {
        val build = File(root, "app/build.gradle.kts").readText()
        assertTrue("applicationId = \"com.cosmotune.app\"" in build)
        assertTrue("applicationId = \"com.cosmotune.dev\"" in build)
    }

    @Test fun noUpstreamIconArtworkIsShipped() {
        val res = File(root, "app/src/main/res")
        val mipmaps = res.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap") }.orEmpty()
        val names = mipmaps.flatMap { it.listFiles().orEmpty().map { f -> f.nameWithoutExtension } }.toSet()
        assertEquals(setOf("ic_launcher", "ic_launcher_round"), names)
        assertFalse(File(root, "app/src/main/java/com/cosmotune/music/AppIcon.kt").exists())
    }
}
