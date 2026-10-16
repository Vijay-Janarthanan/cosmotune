package com.cosmotune.video.library

import com.cosmotune.video.source.FakeVideoSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real files (plan task Y-7, step 2): atomic writes in a temporary folder, the folder rule, and a store over them. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileLibraryFilesTest {

    private lateinit var dir: File

    @BeforeTest
    fun makeFolder() {
        dir = Files.createTempDirectory("cosmotune-video-test").toFile()
    }

    @AfterTest
    fun removeFolder() {
        dir.deleteRecursively()
    }

    private fun names(folder: File = dir): List<String> = folder.list().orEmpty().sorted()

    @Test
    fun aFileThatIsNotThereReadsAsNull() {
        assertNull(FileLibraryFiles(dir).read("history.json"))
        assertNull(FileLibraryFiles(File(dir, "no-such-folder")).read("history.json"))
    }

    @Test
    fun whatIsWrittenIsRead() {
        val files = FileLibraryFiles(dir)
        files.write("searches.json", """{"version":1,"searches":["rain"]}""")
        assertEquals("""{"version":1,"searches":["rain"]}""", files.read("searches.json"))
    }

    @Test
    fun textIsKeptAsUtf8() {
        val files = FileLibraryFiles(dir)
        val text = """{"title":"தமிழ் ✓ 日本語 émoji 😀"}"""
        files.write("liked.json", text)
        assertEquals(text, files.read("liked.json"))
        assertEquals(text, String(File(dir, "liked.json").readBytes(), Charsets.UTF_8))
    }

    @Test
    fun theFolderIsMadeOnTheFirstWrite() {
        val nested = File(dir, "video/deeper")
        FileLibraryFiles(nested).write("history.json", "{}")
        assertEquals("{}", File(nested, "history.json").readText())
    }

    @Test
    fun aSecondWriteReplacesTheFirstAndNoTemporaryFileIsLeft() {
        val files = FileLibraryFiles(dir)
        files.write("history.json", "first")
        files.write("history.json", "second, and longer than the first")
        assertEquals("second, and longer than the first", files.read("history.json"))
        files.write("history.json", "3")
        assertEquals("3", files.read("history.json"))
        assertEquals(listOf("history.json"), names())
    }

    @Test
    fun aLeftOverTemporaryFileFromACrashIsHarmless() {
        File(dir, "history.json").writeText("the good text")
        File(dir, "history.json.tmp").writeText("half of a wri")
        val files = FileLibraryFiles(dir)
        assertEquals("the good text", files.read("history.json"))
        files.write("history.json", "the next text")
        assertEquals("the next text", files.read("history.json"))
        assertEquals(listOf("history.json"), names())
    }

    @Test
    fun theTextIsMovedOverWithAnAtomicMoveThatReplaces() {
        val moves = ArrayList<Pair<String, String>>()
        val files = FileLibraryFiles(dir) { from, to ->
            moves += from.fileName.toString() to to.fileName.toString()
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        files.write("liked.json", "a")
        files.write("liked.json", "b")
        assertEquals(listOf("liked.json.tmp" to "liked.json", "liked.json.tmp" to "liked.json"), moves)
        assertEquals("b", files.read("liked.json"))
    }

    @Test
    fun whereAtomicMovesAreNotSupportedTheFileIsReplacedWithAPlainMove() {
        val files = FileLibraryFiles(dir) { _, _ -> throw AtomicMoveNotSupportedException("a", "b", "not on this file system") }
        files.write("liked.json", "old")
        files.write("liked.json", "new")
        assertEquals("new", files.read("liked.json"))
        assertEquals(listOf("liked.json"), names())
    }

    @Test
    fun aFailedWriteKeepsTheOldTextAndLeavesNoTemporaryFile() {
        FileLibraryFiles(dir).write("liked.json", "old")
        val files = FileLibraryFiles(dir) { _, _ -> throw IOException("the move failed") }
        assertFailsWith<IOException> { files.write("liked.json", "new") }
        assertEquals("old", files.read("liked.json"))
        assertEquals(listOf("liked.json"), names())
    }

    @Test
    fun renameMovesTheFileAndReplacesATarget() {
        val files = FileLibraryFiles(dir)
        files.write("history.json", "broken text")
        files.rename("history.json", "history.json.broken-1")
        assertNull(files.read("history.json"))
        assertEquals("broken text", files.read("history.json.broken-1"))
        files.write("history.json", "newer broken text")
        files.rename("history.json", "history.json.broken-1")
        assertEquals("newer broken text", files.read("history.json.broken-1"))
        files.rename("not-there.json", "anywhere.json")
        assertEquals(listOf("history.json.broken-1"), names())
    }

    @Test
    fun onlyPlainFileNamesAreAccepted() {
        val files = FileLibraryFiles(dir)
        for (bad in listOf("", ".", "..", "../outside.json", "a/b.json", "a\\b.json", "c:evil.json")) {
            assertFailsWith<IllegalArgumentException>("name: '$bad'") { files.write(bad, "x") }
            assertFailsWith<IllegalArgumentException>("name: '$bad'") { files.read(bad) }
        }
        assertEquals(emptyList(), names())
    }

    // ------------------------------------------------------------------ the folder

    @Test
    fun onAndroidTheFolderIsVideoUnderFilesDir() {
        val filesDir = File(dir, "files")
        assertEquals(File(filesDir, "video"), videoLibraryDir(LibraryPlatform.Android(filesDir)))
    }

    @Test
    fun onTheDesktopTheFolderFollowsXdgDataHome() {
        val folder = videoLibraryDir(LibraryPlatform.Desktop, environment = { if (it == "XDG_DATA_HOME") "/data/xdg" else null }, userHome = "/home/zebu")
        assertEquals(File(File(File("/data/xdg"), "cosmotune"), "video"), folder)
    }

    @Test
    fun onTheDesktopTheFolderFallsBackToTheLocalShareFolderOfTheHome() {
        val expected = File(File(File("/home/zebu/.local/share"), "cosmotune"), "video")
        assertEquals(expected, videoLibraryDir(LibraryPlatform.Desktop, environment = { null }, userHome = "/home/zebu"))
        assertEquals(expected, videoLibraryDir(LibraryPlatform.Desktop, environment = { "   " }, userHome = "/home/zebu"))
        assertEquals(expected, videoLibraryDir(LibraryPlatform.Desktop, environment = { "" }, userHome = "/home/zebu"))
    }

    @Test
    fun theDefaultFolderRuleDoesNotCreateAnything() {
        val before = names()
        videoLibraryDir(LibraryPlatform.Android(dir))
        assertEquals(before, names())
    }

    // ------------------------------------------------------------------ a store over real files

    @Test
    fun aStoreOverRealFilesSurvivesARestart() = runTest {
        val folder = File(dir, "video")
        val video = FakeVideoSource.videos
        val first = VideoLibraryStore(FileLibraryFiles(folder), backgroundScope, { FakeVideoSource.NOW_MS }, StandardTestDispatcher(testScheduler), {})
        first.subscribe(FakeVideoSource.channels[0])
        first.recordWatch(video[0], 100_000, 754_000)
        first.addToWatchLater(video[1])
        first.setLiked(video[2], true)
        val playlist = first.createPlaylist("For the drive")
        first.addToPlaylist(playlist, video[3])
        first.savePlaylist(FakeVideoSource.playlist)
        first.addSearch("tape echo")
        first.setHistoryPaused(true)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(
            listOf("history.json", "liked.json", "playlists.json", "saved-playlists.json", "searches.json", "subscriptions.json", "watch-later.json"),
            names(folder),
        )

        val second = VideoLibraryStore(FileLibraryFiles(folder), backgroundScope, { FakeVideoSource.NOW_MS }, StandardTestDispatcher(testScheduler), {})
        assertEquals(first.subscriptions.value, second.subscriptions.value)
        assertEquals(first.history.value, second.history.value)
        assertTrue(second.historyPaused.value)
        assertEquals(first.watchLater.value, second.watchLater.value)
        assertEquals(first.liked.value, second.liked.value)
        assertEquals(first.playlists.value, second.playlists.value)
        assertEquals(first.savedPlaylists.value, second.savedPlaylists.value)
        assertEquals(first.searches.value, second.searches.value)
    }

    @Test
    fun aBrokenFileOnDiskIsRenamedAndTheStoreStartsEmpty() = runTest {
        File(dir, "searches.json").writeText("{ oops")
        val logs = ArrayList<String>()
        val store = VideoLibraryStore(FileLibraryFiles(dir), backgroundScope, { 1234L }, StandardTestDispatcher(testScheduler)) { logs += it }
        assertEquals(emptyList(), store.searches.value)
        assertEquals(listOf("searches.json.broken-1234"), names())
        assertEquals("{ oops", File(dir, "searches.json.broken-1234").readText())
        assertEquals(1, logs.size)
        assertFalse(File(dir, "searches.json").exists())
    }
}
