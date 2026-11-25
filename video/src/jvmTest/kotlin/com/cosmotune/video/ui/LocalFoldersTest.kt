package com.cosmotune.video.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.ui.local.FolderEntry
import com.cosmotune.video.ui.local.LocalFolderAccess
import com.cosmotune.video.ui.local.LocalFolderStore
import com.cosmotune.video.ui.local.LocalFolders
import com.cosmotune.video.ui.local.PickedVideo
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Added folders of local videos: the store, the order a folder's page shows, Reels' list, and the routes' text form. */
class LocalFoldersTest {
    /** A folder tree in memory: "root" holds folders a and b, a holds c; the videos have their modified times in their names. */
    private val tree = mapOf(
        "root" to listOf(FolderEntry("b", "Beta", true, 0), FolderEntry("a", "alpha", true, 0), FolderEntry("root/v1.mp4", "v1.mp4", false, 100),
            FolderEntry("root/notes.txt", "notes.txt", false, 900)),
        "a" to listOf(FolderEntry("c", "Inner", true, 0), FolderEntry("a/v3.mp4", "v3.mp4", false, 300)),
        "b" to listOf(FolderEntry("b/v2.mkv", "v2.mkv", false, 200)),
        "c" to listOf(FolderEntry("c/v4.mov", "v4.mov", false, 400)),
    )
    private val access = object : LocalFolderAccess {
        override fun list(folder: String) = tree[folder].orEmpty()
        override fun thumbnail(video: String, widthPx: Int): ImageBitmap? = null
    }

    @Test fun aFolderShowsItsFoldersByNameThenItsVideosNewestFirstAndLeavesOtherFilesOut() {
        val ordered = LocalFolders.order(tree.getValue("root") + FolderEntry("root/v9.mp4", "v9.mp4", false, 999))
        assertEquals(listOf("alpha", "Beta", "v9.mp4", "v1.mp4"), ordered.map { it.name })
    }

    @Test fun reelsTakeEveryVideoUnderTheFolderNewestFirst() = runBlocking<Unit> {
        assertEquals(listOf("v4.mov", "v3.mp4", "v2.mkv", "v1.mp4"), LocalFolders.allVideos(access, "root").map { it.name })
        assertEquals("a/v3.mp4", LocalFolders.cover(access, "a"), "a folder's own newest video stands for it")
        assertEquals("c/v4.mov", LocalFolders.cover(access, "c"))
    }

    @Test fun theFoldersAddedAreKeptAcrossRestartsOnceEach() {
        val file = Files.createTempDirectory("local-folders").resolve("local-folders.txt").toFile()
        val store = LocalFolderStore(file)
        store.add(PickedVideo("content://docs/tree/primary%3ADownload%2Finsta/document/primary%3ADownload%2Finsta", "insta"))
        store.add(PickedVideo("D:\\Videos\\Trips", "Trips"))
        store.add(PickedVideo("D:\\Videos\\Trips", "Trips again"))
        assertEquals(listOf("insta", "Trips"), LocalFolderStore(file).folders.value.map { it.name })
        store.remove("D:\\Videos\\Trips")
        assertEquals(listOf("insta"), LocalFolderStore(file).folders.value.map { it.name })
    }

    @Test fun theFolderAndReelsRoutesComeBackFromTheirText() {
        val folder = VideoRoute.LocalFolder("content://docs/tree/primary:Download/document/primary:Download/insta", "insta: saved")
        assertEquals(folder, VideoRoute.decode(folder.encode()))
        for (reels in listOf(VideoRoute.LocalReels("D:\\Videos\\Trips", "Trips"), VideoRoute.LocalReels("/sdcard/a", "a", "/sdcard/a/b.mp4"))) {
            assertEquals(reels, VideoRoute.decode(reels.encode()))
        }
    }
}
