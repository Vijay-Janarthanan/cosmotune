package com.cosmotune.watch

import com.cosmotune.watch.model.MyListEntry
import com.cosmotune.watch.model.WatchPlaylist
import com.cosmotune.watch.model.WatchProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One viewer's Watch library as a file they keep: My list, where they are in everything (watched included), their
 * playlists, favourite channels, their settings and their add-ons (as a plugin pack, which never holds a password, key or
 * server sign-in). Restoring adds to what is here and never removes anything; for the same title the newer place wins.
 */
@Serializable
data class WatchBackup(
    val format: String = FORMAT,
    val version: Int = 1,
    val savedAtMs: Long = 0,
    val profile: String = "",
    val myList: List<MyListEntry> = emptyList(),
    val progress: List<WatchProgress> = emptyList(),
    val playlists: List<WatchPlaylist> = emptyList(),
    val favourites: Set<String> = emptySet(),
    val preferences: WatchPreferences? = null,
    /** A `cosmotune://watch/pack?d=…` plugin pack. */
    val addons: String? = null,
) {
    /** What restoring would bring: "12 in My list, 40 places, 2 playlists, 5 channels, 6 add-ons". */
    fun summary(addonCount: Int = 0): String = listOfNotNull(
        myList.size.takeIf { it > 0 }?.let { "$it in My list" },
        progress.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "place" else "places"} in shows and films" },
        playlists.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "playlist" else "playlists"}" },
        favourites.size.takeIf { it > 0 }?.let { "$it favourite ${if (it == 1) "channel" else "channels"}" },
        addonCount.takeIf { it > 0 }?.let { "$it add-ons" },
    ).joinToString(", ").ifEmpty { "nothing" }

    companion object {
        const val FORMAT = "cosmotune-watch-backup"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        fun encode(backup: WatchBackup): String = json.encodeToString(serializer(), backup)

        /** The backup in [text], or an error the listener understands. */
        fun decode(text: String): WatchBackup {
            require(text.length <= 32 shl 20) { "This file is too large to be a Watch backup." }
            val marked = runCatching { (json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)?.get("format") }.getOrNull()
            require((marked as? kotlinx.serialization.json.JsonPrimitive)?.content == FORMAT) { "This file is not a Watch backup." }
            val backup = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
            require(backup != null) { "This backup could not be read." }
            require(backup.version <= 1) { "This backup is from a newer Cosmotune. Update Cosmotune first." }
            return backup
        }

        /** [mine] with [incoming] added: nothing removed, the newer place for a title, playlists joined by name. */
        fun merge(mine: WatchBackup, incoming: WatchBackup): WatchBackup {
            val list = (mine.myList + incoming.myList.filter { e -> mine.myList.none { it.key == e.key } })
            val places = (mine.progress + incoming.progress).groupBy { it.key }.map { (_, all) -> all.maxBy { it.updatedAtMs } }
            val playlists = mine.playlists.map { p ->
                val other = incoming.playlists.firstOrNull { it.name.equals(p.name, true) } ?: return@map p
                p.copy(items = (p.items + other.items).distinctBy { it.key }, updatedAtMs = maxOf(p.updatedAtMs, other.updatedAtMs))
            } + incoming.playlists.filter { p -> mine.playlists.none { it.name.equals(p.name, true) } }
                .map { p -> if (mine.playlists.any { it.id == p.id }) p.copy(id = "restored-" + p.id) else p }
            return mine.copy(myList = list, progress = places, playlists = playlists, favourites = mine.favourites + incoming.favourites,
                preferences = incoming.preferences ?: mine.preferences)
        }
    }
}
