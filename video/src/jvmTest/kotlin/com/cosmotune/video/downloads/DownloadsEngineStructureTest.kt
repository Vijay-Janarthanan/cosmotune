package com.cosmotune.video.downloads

import com.cosmotune.video.downloads.desktop.DesktopDownloadPlatform
import com.cosmotune.video.downloads.desktop.DeviceCapsDesktop
import com.cosmotune.video.downloads.desktop.FfmpegRemux
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.VideoSourceContract
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the device runs and no unit test can, pinned from the source (plan task Y-24, step 7): both transmux flags, the kept
 * pair, no permission asked on API 29 and above, the sidecar beside the file, no network in the tests, the controllers
 * implementing `DownloadController`; and the desktop's folders, which are plain files.
 */
class DownloadsEngineStructureTest {
    private val root = VideoSourceContract.root
    private fun code(path: String) = VideoSourceContract.code(VideoSourceContract.read(path))
    private fun flat(path: String) = code(path).replace(Regex("\\s+"), " ")

    private val android = "video/src/androidMain/kotlin/com/cosmotune/video/downloads/android"
    private val desktop = "video/src/jvmMain/kotlin/com/cosmotune/video/downloads/desktop"
    private val engine = "shared/src/jvmSharedMain/kotlin/com/cosmotune/video/downloads"
    private val pure = "shared/src/commonMain/kotlin/com/cosmotune/video/downloads"

    @Test fun theTransmuxJoinsTwoSequencesWithBothFlagsOn() {
        val src = flat("$android/TransmuxJob.kt")
        assertTrue(".setTransmuxAudio(true)" in src && ".setTransmuxVideo(true)" in src, "both transmux flags")
        assertTrue("EditedMediaItemSequence.withVideoFrom(listOf(videoItem))" in src && "EditedMediaItemSequence.withAudioFrom(listOf(audioItem))" in src, "one video-only and one audio-only sequence")
        assertTrue("Composition.Builder(" in src && "Transformer.Builder(context)" in src && "it.start(composition, output.absolutePath)" in src)
        assertTrue("override fun onError(" in src && "continuation.resume(false)" in src, "a failed export answers false")
        assertTrue("transformer?.cancel()" in src, "a cancelled download cancels the export")
        assertFalse("setVideoMimeType" in src || "setAudioMimeType" in src, "nothing is re-encoded")
    }

    @Test fun aFailedJoinKeepsBothFiles() {
        val finish = VideoSourceContract.functionBody(VideoSourceContract.read("$engine/DownloadRunner.kt"), "finish").replace(Regex("\\s+"), " ")
        assertTrue("DownloadPlanner.pairNames(entry.item, choice)" in finish)
        assertTrue("move(videoPart, video)" in finish && "move(audioPart, audio)" in finish, "the video and the audio both move beside the downloads")
        assertTrue("platform.join(videoPart, audioPart, joined, choice.container) && joined.length() > 0" in finish)
        val remux = flat("$desktop/FfmpegRemux.kt")
        assertTrue("catch (_: Throwable)" in remux && "false" in remux, "the desktop answers false on any failure")
    }

    @Test fun theDesktopRemuxCopiesStreamsWithoutDecoding() {
        val src = flat("$desktop/FfmpegRemux.kt")
        for (call in listOf("avformat_open_input(", "avformat_new_stream(", "avcodec_parameters_copy(", "av_interleaved_write_frame(", "av_write_trailer(")) {
            assertTrue(call in src, "$call is used")
        }
        for (call in listOf("avcodec_open2", "avcodec_send_packet", "avcodec_receive_frame", "avcodec_find_encoder")) assertFalse(call in src, "$call would decode or encode")
        assertTrue("if (container == DownloadContainer.WEBM) \"webm\" else \"mp4\"" in src)
        assertTrue("\"movflags\", \"+faststart\"" in src)
    }

    @Test fun noPermissionIsAskedForOnApi29AndAbove() {
        val save = flat("$android/SaveCopyToMovies.kt")
        assertTrue("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) throughMediaStore(source, mime) else intoPublicFolder(source)" in save)
        assertTrue("MediaStore.Video.Media.RELATIVE_PATH, \"\${Environment.DIRECTORY_MOVIES}/\$FOLDER\"" in save && "MediaStore.Video.Media.IS_PENDING" in save)
        val throughMediaStore = VideoSourceContract.functionBody(VideoSourceContract.read("$android/SaveCopyToMovies.kt"), "throughMediaStore")
        assertFalse("permission" in throughMediaStore.lowercase(), "the media store path checks no permission")
        val everything = File(root, android).listFiles()!!.filter { it.extension == "kt" }.joinToString("\n") { VideoSourceContract.code(it.readText()) }
        assertFalse("requestPermissions" in everything || "RequestPermission" in everything, "nothing under downloads asks for a permission")
        // the module's manifest adds no storage permission; the app's own stops at API 28
        val manifest = File(root, "video/src/androidMain/AndroidManifest.xml").readText()
        assertFalse("WRITE_EXTERNAL_STORAGE" in manifest)
        val app = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "app/src/main/AndroidManifest.xml")).documentElement
        val permissions = app.getElementsByTagName("uses-permission").let { list -> (0 until list.length).map { list.item(it) as Element } }
        val storage = permissions.single { it.getAttribute("android:name") == "android.permission.WRITE_EXTERNAL_STORAGE" }
        assertEquals("28", storage.getAttribute("android:maxSdkVersion"))
    }

    @Test fun theSubtitlesAndTheSidecarAreWrittenBesideTheFile() {
        val finish = VideoSourceContract.functionBody(VideoSourceContract.read("$engine/DownloadRunner.kt"), "finish").replace(Regex("\\s+"), " ")
        val outDir = "val outDir = platform.outputDir(entry.kind, now).apply { mkdirs() }"
        assertTrue(outDir in finish)
        assertTrue("File(outDir, DownloadPlanner.fileName(entry.item, entry.request, choice))" in finish, "the video goes into outDir")
        assertTrue("File(outDir, SubtitleFiles.fileName(base, track.language))" in finish, "the subtitles beside it")
        assertTrue("File(outDir, DownloadSidecar.fileName(base))" in finish, "the sidecar beside it")
        assertEquals("<name>.cosmotune.json".replace("<name>", "x"), DownloadSidecar.fileName("x"))
    }

    @Test fun theControllersAreTheEngineWithTheirPlatform() {
        val a = flat("$android/AndroidDownloadController.kt")
        assertTrue(
            "class AndroidDownloadController( context: Context, source: VideoSource, settings: VideoSettingsRepository, scope: CoroutineScope, segments: SegmentProvider? = null, ) : DownloadController by DownloadRunner(source, settings, scope, AndroidDownloadPlatform(context.applicationContext), segments), SavesCopyToMovies" in a,
            "AndroidDownloadController(context, source, settings, scope) implements DownloadController",
        )
        val d = flat("$desktop/DesktopDownloadController.kt")
        assertTrue(
            "class DesktopDownloadController( source: VideoSource, settings: VideoSettingsRepository, scope: CoroutineScope, segments: SegmentProvider? = null, ) : DownloadController by DownloadRunner(source, settings, scope, DesktopDownloadPlatform(), segments)" in d,
            "DesktopDownloadController(source, settings, scope) implements DownloadController",
        )
        assertTrue("class DownloadRunner(" in flat("$engine/DownloadRunner.kt") && ") : DownloadController {" in flat("$engine/DownloadRunner.kt"))
        assertFalse("SavesCopyToMovies" in d, "the desktop has no Movies copy")
    }

    @Test fun theServiceIsAForegroundDataSyncServiceWithItsNotification() {
        val service = flat("$android/VideoDownloadService.kt")
        assertTrue("class VideoDownloadService : Service()" in service)
        assertTrue("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC" in service && "ServiceCompat.startForeground(" in service)
        assertTrue("ContextCompat.startForegroundService(" in service && ".addAction(" in service && ".setProgress(" in service)
        // its own channel and id: not the player's (Y-11: "video_playback", 0x7669), not Media3's default 1001 (Music's
        // playback), not Music's downloads (0x8175) or WebDAV uploads (0x8176)
        assertTrue("const val CHANNEL_ID = \"cosmotune_video_downloads\"" in service)
        val id = Regex("""const val NOTIFICATION_ID = (0x[0-9A-Fa-f]+)""").find(service)!!.groupValues[1].removePrefix("0x").toInt(16)
        assertTrue(id !in setOf(0x7669, 1001, 0x8175, 0x8176), "the download notification has an id of its own: $id")
        val controller = flat("$android/AndroidDownloadController.kt")
        for (key in listOf("video_dl_notification", "video_dl_channel", "video_dl_pause")) {
            assertTrue("Res.string.$key" in controller, "the notification uses $key")
        }
        // the holds and the folders of the plan
        assertTrue("isActiveNetworkMetered" in controller && "Intent.ACTION_BATTERY_LOW" in controller && "Intent.ACTION_BATTERY_OKAY" in controller)
        assertTrue("BatteryManager.EXTRA_LEVEL" in controller, "a battery check at every start")
        assertTrue("Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES" in controller && "File(base, \"Cosmotune\")" in controller)
        val runner = flat("$engine/DownloadRunner.kt")
        assertTrue("wifiOnly = s.downloadWifiOnly" in runner && "pauseOnLowBattery = s.pauseOnLowBattery" in runner, "the holds follow the settings")
    }

    @Test fun noNetworkInTheUnitTestsAndNoCookiesOrSourceNameInTheEngine() {
        val tests = listOf("shared/src/jvmTest/kotlin/com/cosmotune/video/downloads", "video/src/jvmTest/kotlin/com/cosmotune/video/downloads")
            .flatMap { File(root, it).listFiles().orEmpty().filter { f -> f.extension == "kt" } }
        assertTrue(tests.size >= 9, "${tests.map { it.name }}")
        val address = Regex("""https?://([A-Za-z0-9.\-]+)""")
        for (file in tests) {
            val text = file.readText()
            for (host in address.findAll(text).map { it.groupValues[1] }) {
                assertTrue(host.endsWith(".invalid") || host == "localhost" || host == "127.0.0.1" || host == "example.com" || host == "www.w3.org", "${file.name} names $host")
            }
            assertFalse(("video" + ".live") in text, "${file.name}: no live test here")
        }
        for (name in listOf("RangeDownloaderTest.kt", "SubtitleFetcherTest.kt", "DownloadRunnerTest.kt")) {
            assertTrue("MockWebServer()" in tests.single { it.name == name }.readText(), "$name talks to a local server")
        }
        val sources = listOf(engine, pure, android, desktop).flatMap { File(root, it).listFiles().orEmpty().filter { f -> f.extension == "kt" } }
        for (file in sources) {
            val text = VideoSourceContract.code(file.readText())
            assertFalse("cookieJar" in text, "${file.name}: no cookie jar")
            assertFalse(Regex("\"[^\"]*(YouTube|Youtube|youtube)[^\"]*\"").containsMatchIn(text), "${file.name}: no source name in text")
            assertFalse("org.schabi" + ".newpipe" in text)
        }
    }

    @Test fun theDesktopFoldersAreTheSpecs() {
        val home = Files.createTempDirectory("y24-home").toFile()
        try {
            val plain = DesktopDownloadPlatform(home.path) { null }
            assertEquals(File(home, ".cache/cosmotune/video-downloads"), plain.cacheDir)
            assertEquals(File(home, ".local/share/cosmotune/video"), plain.stateDir)
            assertEquals(File(home, "Videos/Cosmotune"), plain.outputDir(DownloadKind.VIDEO, VideoSettings()))
            assertEquals(File(home, "Videos/Cosmotune"), plain.outputDir(DownloadKind.AUDIO, VideoSettings()))
            assertEquals(File("D:/Films"), plain.outputDir(DownloadKind.VIDEO, VideoSettings(downloadFolder = "D:/Films")))
            assertFalse(plain.isMetered() || plain.isBatteryLow(), "Wi-Fi only and the battery hold do not apply on the desktop")
            assertTrue(plain.freeBytes(VideoSettings()) > 0, "the free space of the nearest existing folder")
            val xdg = DesktopDownloadPlatform(home.path) { mapOf("XDG_CACHE_HOME" to "/c", "XDG_DATA_HOME" to "/d")[it] }
            assertEquals(File("/c/cosmotune/video-downloads"), xdg.cacheDir)
            assertEquals(File("/d/cosmotune/video"), xdg.stateDir)
        } finally {
            home.deleteRecursively()
        }
        assertEquals(DeviceCaps(2160, 60, setOf(VideoCodec.H264, VideoCodec.VP9, VideoCodec.AV1), autoCeilingHeight = 1440, joinsToWebm = true), DeviceCapsDesktop.DEFAULT)
    }

    @Test fun withoutTheNativesTheRemuxAnswersFalseAndLeavesNothing() {
        // this module only compiles against FFmpeg: on the test classpath the natives are absent, as when they fail to load
        val dir = Files.createTempDirectory("y24-remux").toFile()
        try {
            val out = File(dir, "out.mp4")
            assertFalse(FfmpegRemux.remux(File(dir, "v.part"), File(dir, "a.part"), out, DownloadContainer.MP4))
            assertFalse(out.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
