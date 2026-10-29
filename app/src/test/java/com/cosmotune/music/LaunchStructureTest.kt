package com.cosmotune.music

import com.cosmotune.design.AppMode
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The launch is one movement: the system splash, then the app coming up through it. These pin the parts of that which a device is
 * needed to see, from the source: the splash looks the same in light and dark, the activity takes over the splash's exit before
 * anything else, everything the first frame shows is read before it is composed (so there is no frame of a default that is then
 * corrected), and the slow opening of the audio cache is off the main thread without being used before it is open.
 */
class LaunchStructureTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun text(path: String) = File(root, path).readText().replace("\r\n", "\n")

    private val activity = text("app/src/main/java/com/cosmotune/music/MainActivity.kt")
    private val application = text("app/src/main/java/com/cosmotune/music/CosmotuneApplication.kt")

    private fun items(theme: String): Map<String, String> =
        Regex("""<item name="([^"]+)">([^<]+)</item>""").findAll(theme).associate { it.groupValues[1] to it.groupValues[2].trim() }

    @Test fun theSplashIsTheSameInLightAndDark() {
        val day = items(text("app/src/main/res/values-v31/themes.xml"))
        val night = items(text("app/src/main/res/values-night-v31/themes.xml"))
        assertEquals("a night-mode phone gets the splash of a day-mode one", day, night)
        // Black tile, as every icon of the app has (docs/MAINTAINING.md, Icons), and the window behind it black too, so nothing
        // shows between the splash and the first frame in either mode.
        assertEquals("@android:color/black", day["android:windowSplashScreenBackground"])
        assertEquals("@android:color/black", day["android:windowSplashScreenIconBackgroundColor"])
        assertEquals("@android:color/black", day["android:windowBackground"])
        for (folder in listOf("values", "values-night")) {
            assertEquals("@android:color/black", items(text("app/src/main/res/$folder/themes.xml"))["android:windowBackground"])
        }
    }

    @Test fun theActivityTakesOverTheSplashExitBeforeAnythingElse() {
        val create = activity.substringAfter("class MainActivity : AppCompatActivity() {")
        val handoff = create.indexOf("LaunchHandoff.install(this)")
        assertTrue("MainActivity.onCreate installs the handoff", handoff >= 0)
        assertTrue("before super.onCreate", handoff < create.indexOf("super.onCreate(savedInstanceState)"))
        assertTrue("before the composition", handoff < create.indexOf("setContent {"))
    }

    @Test fun theSplashDissolvesQuicklyAndIsAlwaysRemoved() {
        assertTrue("a handover, not a wait", LaunchHandoff.FADE_MS in 100L..250L)
        val source = text("app/src/main/java/com/cosmotune/music/LaunchHandoff.kt")
        assertTrue("the exit is Android 12+ only", "Build.VERSION_CODES.S" in source)
        assertTrue("the view is removed when the dissolve ends", "withEndAction" in source && "splash.remove()" in source)
        assertTrue("and removed anyway if the dissolve could not end", "postDelayed" in source)
    }

    @Test fun theFirstFrameShowsTheStoredStateNotADefaultCorrectedLater() {
        val stored = mutableMapOf<String, Int>(CosmoLookSettings.KEY_MODE to AppMode.MANGA.ordinal)
        val flags = mutableMapOf<String, Boolean>(CosmoLookSettings.KEY_NEW_LOOK to false)
        val settings = CosmoLookSettings(object : KeyValueStore {
            override fun getBoolean(key: String, default: Boolean) = flags[key] ?: default
            override fun putBoolean(key: String, value: Boolean) { flags[key] = value }
            override fun getInt(key: String, default: Int) = stored[key] ?: default
            override fun putInt(key: String, value: Int) { stored[key] = value }
        })
        // Read when the settings object is made, with nothing to wait for: the mode and the look the first composition reads are
        // the stored ones.
        assertEquals(AppMode.MANGA, settings.mode.value)
        assertFalse(settings.newLook.value)

        // The theme and the other settings are opened by the application before any activity exists...
        assertTrue("AppSettings.init(this, authStore)" in application)
        assertTrue(
            "the settings are open before the activity",
            application.indexOf("AppSettings.init(this, authStore)") < application.indexOf("backgroundInit.join()"),
        )
        // ...and the activity reads the theme and the look as state, never from an effect that lands a frame after the first.
        val composition = activity.substringAfter("setContent {").substringBefore("private fun applyPerformanceMode")
        assertTrue("themeMode" in composition)
        assertFalse("the theme is not loaded from a LaunchedEffect", Regex("""LaunchedEffect[^{]*\{[^}]*themeMode\.value\s*=""").containsMatchIn(composition))
    }

    @Test fun theAudioCacheOpensBesideTheOtherStoresAndIsOpenBeforeItIsUsed() {
        val body = application.substringAfter("override fun onCreate()").substringBefore("override fun newImageLoader")
        assertEquals("one call opens it", 1, Regex("""AudioCache\.init\(this\)""").findAll(body).count())
        val open = body.indexOf("openInBackground(\"startup-audio-cache\") { AudioCache.init(this) }")
        assertTrue("it opens on its own thread", open >= 0)
        assertTrue("after the settings it reads for its size limit", body.indexOf("AppSettings.init(this, authStore)") < open)
        val join = body.indexOf("audioCacheOpen.join()")
        assertTrue("and is joined", join > open)
        assertTrue("before the version-update wipe that uses it", join < body.indexOf("AudioCache.clear()"))
        assertTrue("before onCreate returns", join < body.indexOf("backgroundInit.join()"))
        assertTrue("a failure while opening is thrown on the main thread, as it was", "failure?.let { throw it }" in application)
    }

    @Test fun theBaselineProfileCoversTheCodeThatRunsBeforeTheFirstFrame() {
        val profile = text("app/src/main/baseline-prof.txt")
        for (pkg in listOf("music", "ui", "design", "theme", "settings", "video/host")) {
            assertTrue("$pkg is compiled ahead of time", "HSPLcom/cosmotune/$pkg/**->**(**)**" in profile)
            assertTrue("$pkg classes are preloaded", "Lcom/cosmotune/$pkg/**;" in profile)
        }
    }
}
