package com.cosmotune.music.data.webdav

import com.cosmotune.listening.AutoBackup

/**
 * A copy of each automatic backup on the listener's own WebDAV server (Nextcloud, ownCloud, a NAS; the idea from Mochy, see
 * NOTICE.md), in a "Cosmotune backups" folder beside their music, keeping the newest [AutoBackup.KEEP] there as in the folder.
 * The server and sign-in are the ones already set for WebDAV in Settings.
 */
object WebDavBackup {
    const val FOLDER = "Cosmotune backups"

    /** The backup folder's address on the server at [root]. */
    fun folderUrl(root: String): String = WebDavClient.joinUrl(WebDavConfig.normalizeUrl(root), FOLDER)

    /** Uploads [bytes] as [name], replacing one of the same name, then removes this job's own older copies past the newest few. */
    suspend fun upload(root: String, username: String, password: String, name: String, bytes: ByteArray): Result<Unit> = runCatching {
        require(WebDavConfig.isConfigured(root)) { "Set up your WebDAV server in Settings first" }
        val folder = folderUrl(root)
        WebDavClient.ensureCollection(folder, username, password).getOrThrow()
        WebDavClient.putFile(WebDavClient.joinUrl(folder, name), bytes.inputStream(), bytes.size.toLong(), "application/json",
            username, password, overwrite = true).getOrThrow()
        val names = WebDavClient.listFiles(folder, username, password).getOrDefault(emptyList()).associateBy { it.displayName }
        for (old in AutoBackup.toRemove(names.keys.toList())) {
            names[old]?.let { WebDavClient.delete(it.url, username, password) }
        }
    }
}
