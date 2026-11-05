package com.cosmotune.video.player.desktop

import com.cosmotune.video.ui.VideoSourceContract
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Where the natives come from (plan task Y-12, step 3): on this machine the mediamp runtime jar of the build's platform is on
 * the test classpath (`video/build.gradle.kts`), holds a libmpv for the platform with its listing, and JNA loads it from the
 * extracted folder. The tests extract into a scratch folder of the system's temporary directory, never the user's cache.
 */
class MpvNativesTest {

    @Test
    fun thePlatformOfThisMachineHasARuntimeJarWithALibmpv() {
        val platform = MpvNatives.platform()
        assumeTrue("an x64 Windows, Linux or a Mac", platform != null)
        val listing = assertNotNull(MpvNatives.listingResource(platform!!), "mpv-natives-$platform.txt is on the classpath")
        assertTrue(listing.toString().contains("mediamp-mpv-runtime-$platform"), "it comes from the runtime jar: $listing")
        val names = MpvNatives.libraries(listing)
        val libmpv = assertNotNull(MpvNatives.libmpvName(names), "the listing names libmpv: $names")
        assertTrue(names.none { "mediampv" in it }, "mediamp's JNI glue is left out")
        for (name in names) assertNotNull(javaClass.classLoader.getResource(name), "$name is in the jar")
        if (platform == "windows-x64") {
            assertEquals("libmpv-2.dll", libmpv)
            assertTrue("avcodec-62.dll" in names && names.indexOf("avcodec-62.dll") < names.indexOf(libmpv), "dependencies come first")
            assertEquals(36, names.size, "37 listed, mediampv.dll left out")
        }
        assertEquals(MpvNatives.RUNTIME_VERSION, MpvNatives.runtimeVersion(listing, platform), "the version is read from the jar's name")
    }

    @Test
    fun libmpvLoadsFromTheExtractedFolderAndSpeaksClientApi2() {
        val mpv = testMpv()
        assumeTrue("libmpv loads on this machine", mpv != null)
        val version = mpv!!.mpv_client_api_version().toLong()
        assertEquals(2L, version ushr 16, "client API major 2 (got ${version ushr 16}.${version and 0xffff})")
        val dir = MpvNatives.directory(testMpvCache, MpvNatives.RUNTIME_VERSION)
        assertTrue(dir.endsWith(Path.of("cosmotune", "mpv", MpvNatives.RUNTIME_VERSION)), "$dir")
        val libmpv = MpvNatives.libmpvName(MpvNatives.libraries(MpvNatives.listingResource(MpvNatives.platform()!!)!!))!!
        assertTrue(Files.isRegularFile(dir.resolve(libmpv)), "libmpv was extracted to $dir")
        assertSame(mpv, testMpv(), "loaded once per process")
    }

    @Test
    fun extractingAgainLeavesCompleteFilesAlone() {
        val platform = MpvNatives.platform()
        assumeTrue(platform != null && MpvNatives.listingResource(platform) != null)
        val names = MpvNatives.libraries(MpvNatives.listingResource(platform!!)!!)
        val dir = MpvNatives.directory(testMpvCache, MpvNatives.RUNTIME_VERSION)
        MpvNatives.extract(dir, names)
        val before = names.associateWith { Files.getLastModifiedTime(dir.resolve(it)) }
        MpvNatives.extract(dir, names)
        for (name in names) assertEquals(before[name], Files.getLastModifiedTime(dir.resolve(name)), "$name was not copied again")
        assertTrue(Files.list(dir).use { files -> files.noneMatch { it.fileName.toString().endsWith(".part") } }, "no partial file is left")
    }

    @Test
    fun theRuntimeVersionIsTheOneTheBuildDeclares() {
        val build = VideoSourceContract.read("video/build.gradle.kts")
        assertTrue("mediamp-mpv-runtime-\$mpvPlatform:${MpvNatives.RUNTIME_VERSION}\")" in build, "video/build.gradle.kts declares ${MpvNatives.RUNTIME_VERSION}")
    }

    @Test
    fun theCacheIsTheMediaCachesRoot() {
        val root = MpvNatives.cacheRoot()
        val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        assertEquals(Path.of(xdg ?: "${System.getProperty("user.home")}/.cache"), root)
        assertEquals(root.resolve("cosmotune").resolve("mpv").resolve("0.5.1"), MpvNatives.directory(root, "0.5.1"))
    }
}

/** The scratch cache the desktop engine's tests extract libmpv into (the system's temporary folder, kept between runs). */
internal val testMpvCache: Path = Path.of(System.getProperty("java.io.tmpdir"), "cosmotune-video-test-cache")

/** libmpv loaded from [testMpvCache], or null when this machine has none. */
internal fun testMpv(): MpvLibrary? = MpvNatives.load(testMpvCache)
