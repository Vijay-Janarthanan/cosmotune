package com.cosmotune.watch.torrent

/** One file inside a torrent. */
data class TorrentFile(val index: Int, val path: String, val length: Long, val video: Boolean) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
}

/** What a torrent holds, read from its metadata. */
data class TorrentListing(val hash: String, val name: String, val files: List<TorrentFile>) {
    /** The videos in the order a season or a course is meant to be watched: by folder and name, numbers compared as numbers. */
    val videos: List<TorrentFile> get() = files.filter { it.video && it.length > 0 }.sortedWith(compareBy(NaturalOrder) { it.path })
}

/** One line of a running download or share: how far, how fast, how many peers, and whether it is done or failed. */
data class TorrentEvent(
    val done: Long = 0,
    val total: Long = 0,
    val rateBytesPerSecond: Long = 0,
    val peers: Int = 0,
    val uploaded: Long = 0,
    val seeding: Boolean = false,
    val complete: Boolean = false,
    val listing: TorrentListing? = null,
    val magnet: String? = null,
    val error: String? = null,
)

/** The engine's settings, kept on this device: sharing after a download, and the one network connection torrents may use. */
@kotlinx.serialization.Serializable
data class TorrentSettings(
    /** Keep sharing (seeding) a finished download. Off unless the listener turns it on. */
    val seedAfterDownload: Boolean = false,
    /** For how long; 0 keeps sharing until it is stopped in Downloads. */
    val seedMinutes: Int = 60,
    /** Share while on mobile data too (off: downloads still work, nothing is uploaded on a metered network). */
    val seedOnMobileData: Boolean = false,
    /** The network connection torrents must use (a VPN's name); null lets them use any. Torrents stop when it drops. */
    val bindInterface: String? = null,
)

/** Names compared the way people count: "E2" before "E10". */
object NaturalOrder : Comparator<String> {
    private val parts = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val x = parts.findAll(a.lowercase()).map { it.value }.toList()
        val y = parts.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
                else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}

/** A torrent's file as something Watch plays: an item of the "torrent" source, its copy the magnet link and the file's index. */
object TorrentItems {
    const val PLUGIN = "torrent"

    fun magnet(hash: String) = "magnet:?xt=urn:btih:$hash"

    fun target(listing: TorrentListing, file: TorrentFile): com.cosmotune.watch.model.PlayTarget.Item =
        com.cosmotune.watch.model.PlayTarget.Item(com.cosmotune.watch.model.WatchKey.item(PLUGIN, "${listing.hash}:${file.index}"), PLUGIN, "${listing.hash}:${file.index}", title(file))

    fun summary(listing: TorrentListing, file: TorrentFile) = com.cosmotune.watch.model.TitleSummary(
        target(listing, file).key, com.cosmotune.watch.model.TitleType.FILM, title(file), overview = listing.name, origin = PLUGIN, badge = "TORRENT")

    fun stream(listing: TorrentListing, file: TorrentFile, trackers: List<String> = emptyList()) = com.cosmotune.watch.model.Stream(
        com.cosmotune.watch.model.StreamSource(PLUGIN, "Torrent", "P2P"), file.name, listing.name, magnet(listing.hash),
        com.cosmotune.watch.model.StreamProtocol.PROGRESSIVE, containerOf(file.name), sizeBytes = file.length, filename = file.name,
        torrentFileIndex = file.index, torrentTrackers = trackers)

    /** The hash and file index of a torrent item's key, or null for anything else. */
    fun parts(target: com.cosmotune.watch.model.PlayTarget): Pair<String, Int>? {
        val item = target as? com.cosmotune.watch.model.PlayTarget.Item ?: return null
        if (item.pluginId != PLUGIN) return null
        val hash = item.itemId.substringBefore(':')
        val index = item.itemId.substringAfter(':', "").toIntOrNull() ?: return null
        return if (hash.length == 40) hash to index else null
    }

    private fun title(file: TorrentFile) = file.name.substringBeforeLast('.').replace('.', ' ').replace('_', ' ').trim().ifBlank { file.name }

    private fun containerOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mkv" -> com.cosmotune.watch.model.Container.MKV
        "mp4", "m4v" -> com.cosmotune.watch.model.Container.MP4
        "webm" -> com.cosmotune.watch.model.Container.WEBM
        "ts", "m2ts" -> com.cosmotune.watch.model.Container.TS
        "avi" -> com.cosmotune.watch.model.Container.AVI
        "mov" -> com.cosmotune.watch.model.Container.MOV
        else -> com.cosmotune.watch.model.Container.UNKNOWN
    }
}
