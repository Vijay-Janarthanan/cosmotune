package com.cosmotune.video.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What no render can show, read from the source (plan task Y-1, step 8): the build wiring of the hook table (the four upstream
 * build files), the module's manifest and its two services, the seams the other tasks build on (their names and parameter lists),
 * and the one rule of the adapter: nothing in this module imports the extractor.
 */
class VideoModuleStructureTest {
    private val root = VideoSourceContract.root
    private fun read(path: String) = VideoSourceContract.read(path)
    private fun lines(path: String) = read(path).lines().map { it.trimEnd() }

    // ---- The hook table, rows 1 to 4 ------------------------------------------------------------------------------------

    @Test fun settingsIncludesTheModuleRightAfterTheDesktopApp() {
        val l = lines("settings.gradle.kts")
        val at = l.indexOf("include(\":desktopApp\")")
        assertTrue(at >= 0, "include(\":desktopApp\") is there")
        assertEquals("include(\":video\")", l[at + 1], "include(\":video\") follows it")
        assertEquals(1, l.count { it == "include(\":video\")" })
    }

    @Test fun theAppDependsOnTheModuleRightAfterTheFirstSharedUi() {
        val l = lines("app/build.gradle.kts")
        val at = l.indexOf("    implementation(project(\":sharedUi\"))")
        assertTrue(at >= 0, "the app depends on :sharedUi")
        assertEquals("    implementation(project(\":video\"))", l[at + 1])
        assertEquals(1, l.count { it == "    implementation(project(\":video\"))" })
    }

    @Test fun theDesktopDependsOnTheModuleRightAfterSharedUi() {
        val l = lines("desktopApp/build.gradle.kts")
        val at = l.indexOf("    implementation(project(\":sharedUi\"))")
        assertTrue(at >= 0, "the desktop depends on :sharedUi")
        assertEquals("    implementation(project(\":video\"))", l[at + 1])
        assertEquals(1, l.count { it == "    implementation(project(\":video\"))" })
    }

    @Test fun theSharedTestClasspathHasTheThreeLibrariesAndTheMainCodeKeepsNewPipeCompileOnly() {
        val text = read("shared/build.gradle.kts")
        val tests = text.substringAfter("jvmTest.dependencies {").substringBefore("\n        }")
        for (dep in listOf(
            "implementation(\"com.github.TeamNewPipe:NewPipeExtractor:v0.26.3\")",
            "implementation(\"org.xerial:sqlite-jdbc:3.47.1.0\")",
            "implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2\")",
        )) assertTrue(dep in tests, "jvmTest.dependencies holds $dep")
        assertTrue("compileOnly(\"com.github.TeamNewPipe:NewPipeExtractor:v0.26.3\")" in text, "the main code still compiles against NewPipe only")
        assertEquals(1, Regex("""implementation\("com\.github\.TeamNewPipe:NewPipeExtractor""").findAll(text).count(), "NewPipe is an implementation dependency of the tests only")
    }

    // ---- The module's own build file ------------------------------------------------------------------------------------

    @Test fun media3IsTheVersionOfTheApp() {
        val module = read("video/build.gradle.kts")
        val app = read("app/build.gradle.kts")
        val wanted = listOf(
            "media3-exoplayer", "media3-session", "media3-common", "media3-datasource-okhttp", "media3-exoplayer-dash",
            "media3-exoplayer-hls", "media3-ui-compose", "media3-transformer",
        )
        for (artifact in wanted) assertTrue("implementation(\"androidx.media3:$artifact:1.11.0\")" in module, "$artifact at 1.11.0")
        // one Media3 per APK: what the app pins, the module pins too
        for (artifact in listOf("media3-exoplayer", "media3-session", "media3-common", "media3-datasource-okhttp", "media3-exoplayer-dash", "media3-exoplayer-hls")) {
            assertTrue("implementation(\"androidx.media3:$artifact:1.11.0\")" in app, "the app is on 1.11.0 for $artifact: a bump there is a bump here")
        }
        assertTrue("androidx.activity:activity-compose:1.9.3" in module && "androidx.core:core-ktx:1.15.0" in module)
    }

    @Test fun media3UiComposeDoesNotBringItsComposeBomIntoTheApp() {
        // Its POM imports compose-bom 2026.05.00; without this exclusion the app's androidx.compose libraries go from 1.10.5 to
        // 1.11.1 and Music's MainActivity no longer compiles (AnchoredDraggableState lost its `density` parameter).
        val module = read("video/build.gradle.kts")
        val declaration = module.substringAfter("implementation(\"androidx.media3:media3-ui-compose:1.11.0\")").substringBefore("\n            //").substringBefore("implementation(")
        assertTrue("exclude(group = \"androidx.compose\", module = \"compose-bom\")" in declaration, "media3-ui-compose is declared with compose-bom excluded")
        assertEquals(1, Regex("""compose-bom""").findAll(module.lineSequence().filterNot { it.trim().startsWith("//") }.joinToString("\n")).count(), "the exclusion is the only mention of the BOM in code")
    }

    @Test fun theDesktopLibrariesAreThePlansAndTheNativesFollowThePlatform() {
        val module = read("video/build.gradle.kts")
        assertTrue("implementation(\"net.java.dev.jna:jna:5.19.1\")" in module)
        assertTrue("org.openani.mediamp:mediamp-mpv-runtime-\$mpvPlatform:0.5.1\") { isTransitive = false }" in module, "the natives come without their transitive dependencies")
        for (platform in listOf("windows-x64", "linux-x64", "macos-arm64", "macos-x64")) assertTrue("\"$platform\"" in module, "the natives of $platform")
        assertTrue("\"bitchord.target\"" in module, "the platform is chosen as the desktop chooses its classifier")
        assertTrue("compileOnly(\"org.bytedeco:ffmpeg:7.1.1-1.5.12\")" in module && "compileOnly(\"org.bytedeco:javacpp:1.5.12\")" in module, "the remux libraries are compile-only: the desktop app supplies the natives")
        assertTrue("implementation(\"org.xerial:sqlite-jdbc:3.47.1.0\")" in module)
        assertTrue("jna-platform:5.19.1" in read("desktopApp/build.gradle.kts"), "the desktop ships the same JNA")
    }

    @Test fun theModuleIsALibraryOfTheNamespaceAndItsResourcesArePublic() {
        val module = read("video/build.gradle.kts")
        assertTrue("namespace = \"com.cosmotune.video\"" in module && "compileSdk = 36" in module && "minSdk = 26" in module)
        assertTrue("packageOfResClass = \"com.cosmotune.video.resources\"" in module && "publicResClass = true" in module && "generateResClass = always" in module)
        assertTrue("api(project(\":sharedUi\"))" in module)
    }

    // ---- The manifest ---------------------------------------------------------------------------------------------------

    private fun manifest(): Element {
        val file = File(root, "video/src/androidMain/AndroidManifest.xml")
        assertTrue(file.isFile, "the module's manifest exists")
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun elements(parent: Element, tag: String): List<Element> =
        parent.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    @Test fun theManifestDeclaresTheTwoForegroundServicePermissions() {
        val permissions = elements(manifest(), "uses-permission").map { it.getAttribute("android:name") }.toSet()
        assertEquals(
            setOf("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK", "android.permission.FOREGROUND_SERVICE_DATA_SYNC"),
            permissions,
        )
    }

    @Test fun thePlaybackServiceIsExportedAsMediaPlaybackWithTheMediaSessionAction() {
        val service = elements(manifest(), "service").single { it.getAttribute("android:name") == "com.cosmotune.video.player.android.VideoPlaybackService" }
        assertEquals("true", service.getAttribute("android:exported"), "system controllers and headsets can bind")
        assertEquals("mediaPlayback", service.getAttribute("android:foregroundServiceType"))
        val actions = elements(service, "action").map { it.getAttribute("android:name") }
        assertEquals(listOf("androidx.media3.session.MediaSessionService"), actions)
    }

    @Test fun theDownloadServiceIsNotExportedAndIsDataSync() {
        val service = elements(manifest(), "service").single { it.getAttribute("android:name") == "com.cosmotune.video.downloads.android.VideoDownloadService" }
        assertEquals("false", service.getAttribute("android:exported"))
        assertEquals("dataSync", service.getAttribute("android:foregroundServiceType"))
        assertTrue(elements(service, "intent-filter").isEmpty(), "nothing outside the app can start it")
    }

    @Test fun theManifestHoldsExactlyTheTwoServicesAndTheirClassesExist() {
        val services = elements(manifest(), "service").map { it.getAttribute("android:name") }
        assertEquals(2, services.size)
        for (name in services) {
            val path = "video/src/androidMain/kotlin/" + name.replace('.', '/') + ".kt"
            assertTrue(File(root, path).isFile, "$name is a class at $path")
        }
        assertTrue("MediaSessionService()" in read("video/src/androidMain/kotlin/com/cosmotune/video/player/android/VideoPlaybackService.kt"))
        assertTrue(": Service()" in read("video/src/androidMain/kotlin/com/cosmotune/video/downloads/android/VideoDownloadService.kt"))
    }

    // ---- Nothing here imports the extractor -----------------------------------------------------------------------------

    @Test fun noFileUnderVideoSrcImportsTheExtractor() {
        // the package name is built from two pieces so that this file does not name it in one piece itself
        val extractor = "org.schabi" + ".newpipe"
        val offenders = File(root, "video/src").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { f -> f.readLines().any { extractor in it } }
            .map { it.relativeTo(root).path }.toList()
        assertTrue(offenders.isEmpty(), "the extractor is the adapter's alone (:shared, com.cosmotune.video.source.newpipe): $offenders")
    }

    // ---- The seams other tasks build on ---------------------------------------------------------------------------------

    private val ui = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui"
    private val player = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/player"

    @Test fun theEntryPointOfThePhoneTakesTheWindowAndTheHost() {
        val params = VideoSourceContract.parameters(read("$ui/CosmoVideoApp.kt"), "CosmoVideoApp")
        assertEquals(
            listOf("windowWidth: Dp", "windowHeight: Dp", "host: VideoHostLinks", "modifier: Modifier = Modifier"),
            params,
        )
    }

    @Test fun theHostLinksAreTheFiveOfTheSpecInOrder() {
        val src = VideoSourceContract.code(read("$ui/VideoHostLinks.kt")).replace(Regex("\\s+"), " ")
        val declared = listOf(
            "val switchToMusic: () -> Unit,",
            "val playAsSong: (videoId: String, title: String, channel: String, thumbnailUrl: String?) -> Unit,",
            "val pauseMusic: () -> Unit,",
            "val pendingVideo: () -> PendingVideo?,",
            "val onPendingHandled: () -> Unit,",
        )
        val at = declared.map { src.indexOf(it) }
        assertTrue(at.all { it >= 0 } && at == at.sorted(), "the five links, in order: $at")
        assertTrue("@Stable class VideoHostLinks(" in src && "@Immutable data class PendingVideo(val videoId: String, val startMs: Long?)" in src)
    }

    @Test fun theDesktopEntryPointsHaveTheSignaturesOfThePlan() {
        val src = read("$ui/desktop/VideoDesktopEntry.kt")
        assertEquals(listOf("contentPadding: PaddingValues", "host: VideoHostLinks"), VideoSourceContract.parameters(src, "VideoDesktopPage"))
        // Y-14 added one optional parameter at the end (the window caption a host that replaces its whole sidebar hands over); the two of the plan stay first
        assertEquals(listOf("focusSearch: Boolean", "onSearchFocused: () -> Unit", "caption: (@Composable () -> Unit)? = null"), VideoSourceContract.parameters(src, "VideoDesktopSidebar"))
        assertEquals(emptyList(), VideoSourceContract.parameters(src, "videoDesktopDrawerEntries"))
        val flat = VideoSourceContract.code(src).replace(Regex("\\s+"), " ")
        assertTrue("fun videoDesktopDrawerEntries(): List<CosmoDrawerEntry>" in flat)
        assertTrue("object VideoDesktopBack { val canGoBack: Boolean" in flat && "fun goBack()" in flat)
    }

    @Test fun theSurfaceAndPictureInPictureSeamsAreDeclaredWithAnActualOnEachTarget() {
        val surface = VideoSourceContract.code(read("$player/VideoSurfaceProvider.kt")).replace(Regex("\\s+"), " ")
        assertTrue("interface VideoSurfaceProvider { @Composable fun Surface(modifier: Modifier, scale: VideoScale) }" in surface)
        assertTrue("enum class VideoScale { FIT, FILL }" in surface)
        val pip = VideoSourceContract.code(read("$player/Pip.kt")).replace(Regex("\\s+"), " ")
        assertTrue("@Composable expect fun rememberPictureInPicture(): PictureInPictureState" in pip)
        assertTrue("val supported: Boolean" in pip && "val active: Boolean" in pip)
        assertTrue("fun update(enabled: Boolean, aspectWidth: Int, aspectHeight: Int, sourceRect: Rect?)" in pip)
        for (actual in listOf("video/src/androidMain/kotlin/com/cosmotune/video/player/Pip.android.kt", "video/src/jvmMain/kotlin/com/cosmotune/video/player/Pip.jvm.kt")) {
            val text = VideoSourceContract.code(read(actual)).replace(Regex("\\s+"), " ")
            assertTrue("actual fun rememberPictureInPicture(): PictureInPictureState" in text, "$actual has the actual")
            assertTrue("override val supported: Boolean get() = false" in text && "override val active: Boolean get() = false" in text, "$actual reports not supported")
        }
    }
}
