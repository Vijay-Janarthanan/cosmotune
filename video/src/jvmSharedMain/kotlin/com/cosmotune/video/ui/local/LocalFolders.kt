package com.cosmotune.video.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.cosmotune.video.local.LocalVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Folders of videos (Video mode's local files): the person adds a folder (a downloads folder of saved clips, say), it stands among the
 * local files with its first video as its picture, and inside it are its folders and its videos, each played in the local player,
 * or all of them one at a time full screen in Reels.
 */

/** A folder or a video directly inside a folder: where it is ([source]: a path, or a document address on the phone), its name, and when it last changed. */
class FolderEntry(val source: String, val name: String, val isFolder: Boolean, val modifiedMs: Long)

/** What the folder pages ask of the platform. */
interface LocalFolderAccess {
    /** The folders and videos directly in [folder] (other files are left out); empty when it cannot be read. */
    fun list(folder: String): List<FolderEntry>

    /** A small picture of [video] (a frame near its start), [widthPx] wide at most, kept on the device once made; null when none can be made. */
    fun thumbnail(video: String, widthPx: Int): ImageBitmap?
}

/** The folders for the screen being drawn. */
@Composable
expect fun rememberLocalFolderAccess(): LocalFolderAccess

/**
 * The platform's folder picker: [onFolder] is given the folder chosen (its source and name) on the main thread, with the right to
 * read it kept for later (the phone's tree grant). Cancelling calls nothing.
 */
@Composable
expect fun rememberFolderPicker(onFolder: (PickedVideo) -> Unit): () -> Unit

/** The one store of the folders added, in the video folder (made once per process). */
@Composable
expect fun rememberLocalFolderStore(): LocalFolderStore

/** The folders the person added, in the order added, kept in [file] as one `source<TAB>name` line each. */
class LocalFolderStore(private val file: File) {
    private val state = MutableStateFlow(read())

    /** The folders added: a [PickedVideo] carries a folder's source and name. */
    val folders: StateFlow<List<PickedVideo>> = state.asStateFlow()

    /** Adds [folder] at the end; a folder already there stays where it is. */
    fun add(folder: PickedVideo) {
        if (state.value.any { it.source == folder.source }) return
        state.value = state.value + folder
        write()
    }

    /** Takes the folder at [source] off the list (its files are not touched). */
    fun remove(source: String) {
        state.value = state.value.filterNot { it.source == source }
        write()
    }

    private fun read(): List<PickedVideo> = runCatching {
        if (!file.isFile) emptyList() else file.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else PickedVideo(line.substring(0, tab), line.substring(tab + 1))
        }
    }.getOrDefault(emptyList())

    private fun write() {
        runCatching {
            file.parentFile?.mkdirs()
            val text = state.value.joinToString("\n") { "${it.source.replace('\t', ' ')}\t${it.name.replace('\t', ' ').replace('\n', ' ')}" }
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
    }
}

/** The process-wide folder store: the desktop and the phone each give the video folder, once. */
internal object LocalFolderStores {
    private var store: LocalFolderStore? = null

    @Synchronized
    fun of(folder: File): LocalFolderStore = store ?: LocalFolderStore(File(folder, "local-folders.txt")).also { store = it }
}

/**
 * Reading folders: what one holds, in the pages' order (its folders by name, then its videos newest first, as a phone's downloads
 * are seen), every video under it for Reels, and the video that stands for it. Listings are kept for the session, so going back and
 * forth is instant; a fresh visit reads the folder again behind the kept answer.
 */
object LocalFolders {
    private val listings = object : LinkedHashMap<String, List<FolderEntry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FolderEntry>>?) = size > 400
    }
    private val covers = HashMap<String, String?>()

    /** The listing of [folder] kept from before, or null. */
    @Synchronized
    fun kept(folder: String): List<FolderEntry>? = listings[folder]

    /** What [folder] holds, read now (off the main thread): its folders by name, then its videos newest first. */
    suspend fun list(access: LocalFolderAccess, folder: String): List<FolderEntry> = withContext(Dispatchers.IO) {
        val read = order(access.list(folder))
        synchronized(this@LocalFolders) { listings[folder] = read }
        read
    }

    /** Folders by name (as a person sorts them), then videos newest first. */
    fun order(entries: List<FolderEntry>): List<FolderEntry> =
        entries.filter { it.isFolder }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) +
            entries.filter { !it.isFolder && LocalVideo.isVideoName(it.name) }.sortedByDescending { it.modifiedMs }

    /**
     * Every video in [folder] and in the folders inside it (at most [limit], [depth] folders down), newest first: Reels' list.
     */
    suspend fun allVideos(access: LocalFolderAccess, folder: String, limit: Int = 5_000, depth: Int = 8): List<FolderEntry> = withContext(Dispatchers.IO) {
        val found = ArrayList<FolderEntry>()
        var level = listOf(folder)
        var down = 0
        while (level.isNotEmpty() && down <= depth && found.size < limit) {
            val next = ArrayList<String>()
            for (each in level) {
                val entries = kept(each) ?: list(access, each)
                found += entries.filter { !it.isFolder }
                next += entries.filter { it.isFolder }.map { it.source }
                if (found.size >= limit) break
            }
            level = next
            down++
        }
        found.sortedByDescending { it.modifiedMs }.take(limit)
    }

    /** The video that stands for [folder]: its newest video, else the first found in the folders inside it (three down at most). */
    suspend fun cover(access: LocalFolderAccess, folder: String): String? {
        synchronized(this) { if (covers.containsKey(folder)) return covers[folder] }
        val found = withContext(Dispatchers.IO) { coverIn(access, folder, 3) }
        synchronized(this) { covers[folder] = found }
        return found
    }

    private suspend fun coverIn(access: LocalFolderAccess, folder: String, depth: Int): String? {
        val entries = kept(folder) ?: list(access, folder)
        entries.firstOrNull { !it.isFolder }?.let { return it.source }
        if (depth <= 0) return null
        for (sub in entries.filter { it.isFolder }) coverIn(access, sub.source, depth - 1)?.let { return it }
        return null
    }

    /** The cover kept from before (null when there is none, or it was not looked for yet). */
    @Synchronized
    fun keptCover(folder: String): String? = covers[folder]
}

/** Video pictures for the folder pages: made two at a time (decoding is heavy), and the last few hundred kept in memory. */
object LocalThumbs {
    private val images = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    }
    private val permits = Semaphore(2)

    @Synchronized
    fun kept(video: String): ImageBitmap? = images[video]

    suspend fun load(access: LocalFolderAccess, video: String, widthPx: Int = THUMB_WIDTH): ImageBitmap? {
        kept(video)?.let { return it }
        val made = permits.withPermit { withContext(Dispatchers.IO) { kept(video) ?: runCatching { access.thumbnail(video, widthPx) }.getOrNull() } }
        if (made != null) synchronized(this) { images[video] = made }
        return made
    }

    /** The width the pictures are made at: sharp in a phone's three-across grid and a desktop's tiles. */
    const val THUMB_WIDTH = 360
}

/** The name of a thumbnail file for [source] as it was last changed: a new version of the file gets a new picture. */
internal fun thumbFileName(source: String, modifiedMs: Long): String =
    LocalVideo.idFor("$source@$modifiedMs").removePrefix(LocalVideo.ID_PREFIX) + ".jpg"
