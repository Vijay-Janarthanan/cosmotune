package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.remote.RemoteArtwork
import com.cosmotune.music.data.smb.SmbAuth
import com.cosmotune.music.data.smb.SmbClient
import com.cosmotune.music.data.smb.SmbConfig
import com.cosmotune.music.data.webdav.WebDavAuth
import com.cosmotune.music.data.webdav.WebDavClient
import com.cosmotune.music.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Music on a WebDAV server or an SMB (Windows, Samba, NAS) share, as the phone has it: the folders are listed, every song in
 * them joins Local music, the album is the folder a song is in, and a picture filed beside the songs is their cover. WebDAV
 * songs stream straight from the server; an SMB song is copied into the cache when it starts (FFmpeg cannot read SMB itself).
 * The clients are the phone's ([WebDavClient], [SmbClient]); the listener's own address and sign-in are kept with the other
 * settings on this computer.
 */
internal object DesktopNetworkShares {

    data class WebDav(val url: String = "", val username: String = "", val password: String = "") {
        val configured: Boolean get() = WebDavConfig.isConfigured(url)
    }

    data class Smb(val host: String = "", val share: String = "", val folder: String = "", val username: String = "", val password: String = "") {
        val configured: Boolean get() = SmbConfig.isConfigured(host, share)
    }

    private const val WEBDAV_URL = "share_webdav_url"
    private const val WEBDAV_USER = "share_webdav_user"
    private const val WEBDAV_PASSWORD = "share_webdav_password"
    private const val SMB_HOST = "share_smb_host"
    private const val SMB_SHARE = "share_smb_share"
    private const val SMB_FOLDER = "share_smb_folder"
    private const val SMB_USER = "share_smb_user"
    private const val SMB_PASSWORD = "share_smb_password"

    fun webDav(persistence: DesktopPersistence = DesktopPersistence()) =
        WebDav(persistence.string(WEBDAV_URL), persistence.string(WEBDAV_USER), persistence.string(WEBDAV_PASSWORD))

    fun smb(persistence: DesktopPersistence = DesktopPersistence()) = Smb(
        persistence.string(SMB_HOST), persistence.string(SMB_SHARE), persistence.string(SMB_FOLDER),
        persistence.string(SMB_USER), persistence.string(SMB_PASSWORD),
    )

    fun saveWebDav(value: WebDav, persistence: DesktopPersistence = DesktopPersistence()) {
        persistence.saveString(WEBDAV_URL, value.url.trim())
        persistence.saveString(WEBDAV_USER, value.username.trim())
        persistence.saveString(WEBDAV_PASSWORD, value.password)
        publish(persistence)
    }

    fun saveSmb(value: Smb, persistence: DesktopPersistence = DesktopPersistence()) {
        persistence.saveString(SMB_HOST, value.host.trim())
        persistence.saveString(SMB_SHARE, value.share.trim())
        persistence.saveString(SMB_FOLDER, value.folder.trim())
        persistence.saveString(SMB_USER, value.username.trim())
        persistence.saveString(SMB_PASSWORD, value.password)
        publish(persistence)
    }

    /** Hands the saved sign-ins to the network layer (covers over WebDAV, the SMB connection). Called at start and on every save. */
    fun publish(persistence: DesktopPersistence = DesktopPersistence()) {
        val dav = webDav(persistence)
        val header = if (dav.configured) WebDavConfig.basicAuthHeader(dav.username, dav.password) else null
        WebDavAuth.update(if (dav.configured) WebDavConfig.hostOf(dav.url) else null, header)
        val smb = smb(persistence)
        SmbAuth.update(smb.host, smb.share, smb.folder, smb.username, smb.password)
    }

    suspend fun testWebDav(value: WebDav): Result<Unit> = WebDavClient.testConnection(WebDavConfig.normalizeUrl(value.url), value.username, value.password)

    suspend fun testSmb(value: Smb): Result<Unit> = SmbClient.testConnection(value.host, value.share, value.folder, value.username, value.password)

    /** The songs the last [refresh] found, empty before the first. */
    @Volatile var cached: List<Song> = emptyList()
        private set

    /** Lists every configured share again; a share that cannot be reached adds nothing. */
    suspend fun refresh(): List<Song> = withContext(Dispatchers.IO) {
        publish()
        (webDavSongs() + smbSongs()).also { cached = it }
    }

    private suspend fun webDavSongs(): List<Song> {
        val dav = webDav()
        if (!dav.configured) return emptyList()
        val listing = WebDavClient.listLibrary(WebDavConfig.normalizeUrl(dav.url), dav.username, dav.password).getOrNull() ?: return emptyList()
        val art = listing.images.groupBy { folderOf(it.url) }
        return listing.audio.map { entry ->
            WebDavConfig.songFor(entry.url, albumName = albumOf(entry.url))
                .copy(thumbnailUrl = RemoteArtwork.pick(art[folderOf(entry.url)].orEmpty().map { it.url }))
        }
    }

    private suspend fun smbSongs(): List<Song> {
        val smb = smb()
        if (!smb.configured) return emptyList()
        if (!SmbAuth.isConfigured()) publish()
        val listing = SmbClient.listLibrary().getOrNull() ?: return emptyList()
        return listing.audio.map { entry ->
            SmbConfig.songFor(SmbAuth.host, SmbAuth.share, entry.path, albumName = albumOf(entry.path, decode = false))
        }
    }

    fun owns(song: Song): Boolean = song.videoId.startsWith("webdav:") || SmbConfig.isSmbId(song.videoId)

    /** The stream for a share's song: WebDAV with its sign-in header, SMB as a copy in the cache. */
    suspend fun stream(song: Song): DesktopStream = withContext(Dispatchers.IO) {
        val address = requireNotNull(song.localUri) { "This song has no address on its share" }
        if (song.videoId.startsWith("webdav:")) {
            val dav = webDav()
            val header = WebDavConfig.basicAuthHeader(dav.username, dav.password)
            DesktopStream(address, headers = header?.let { mapOf("Authorization" to it) }.orEmpty(), sourceId = "webdav")
        } else {
            DesktopStream(cachedSmbCopy(address).absolutePath, sourceId = "smb")
        }
    }

    /** Copies an SMB song into the cache (one file per song, reused while it is there). */
    private fun cachedSmbCopy(address: String): File {
        if (!SmbAuth.isConfigured()) publish()
        val path = requireNotNull(SmbConfig.relativePath(address, SmbAuth.share)) { "Not a song on your SMB share" }
        val folder = File(System.getProperty("java.io.tmpdir"), "cosmotune-smb").apply { mkdirs() }
        val name = Integer.toHexString(address.hashCode()) + "." + address.substringAfterLast('.', "audio").take(5)
        val target = File(folder, name)
        if (target.isFile && target.length() > 0) return target
        val partial = File(folder, "$name.part")
        val file = SmbClient.openFile(path)
        try {
            partial.outputStream().use { out ->
                val chunk = ByteArray(256 * 1024)
                var offset = 0L
                val length = runCatching { file.fileInformation.standardInformation.endOfFile }.getOrDefault(-1L)
                while (length < 0 || offset < length) {
                    val read = file.read(chunk, offset, 0, chunk.size)
                    if (read <= 0) break
                    out.write(chunk, 0, read)
                    offset += read
                }
            }
        } finally {
            runCatching { file.close() }
        }
        check(partial.renameTo(target)) { "The song could not be kept in the cache" }
        return target
    }

    /** The folder a file sits in, as its album. */
    internal fun albumOf(path: String, decode: Boolean = true): String? {
        val folder = path.trimEnd('/').substringBeforeLast('/', "").substringAfterLast('/')
        val name = if (decode) runCatching { java.net.URLDecoder.decode(folder, "UTF-8") }.getOrDefault(folder) else folder
        return name.trim().takeIf { it.isNotBlank() }
    }

    private fun folderOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").lowercase(java.util.Locale.ROOT)
}
