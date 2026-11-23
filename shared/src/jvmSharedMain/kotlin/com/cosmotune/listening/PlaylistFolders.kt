package com.cosmotune.listening

/** A folder the listener made in Library, Playlists, holding playlists by their ids (a playlist sits in one folder at most). */
data class PlaylistFolder(val name: String, val playlistIds: List<String> = emptyList())

/** The playlist folders, kept as one line each ("name<TAB>id,id,…") in the settings store; the rules for changing them. */
object PlaylistFolders {
    const val MAX = 50
    const val MAX_NAME = 40

    fun encode(folders: List<PlaylistFolder>): String =
        folders.joinToString("\n") { folder -> clean(folder.name) + "\t" + folder.playlistIds.joinToString(",") }

    fun decode(text: String): List<PlaylistFolder> {
        val taken = HashSet<String>()
        return text.lineSequence().mapNotNull { line ->
            val name = clean(line.substringBefore('\t')).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val ids = line.substringAfter('\t', "").split(',').map { it.trim() }.filter { it.isNotEmpty() && taken.add(it) }
            PlaylistFolder(name, ids)
        }.distinctBy { it.name.lowercase() }.take(MAX).toList()
    }

    /** [folders] with a new empty one called [name] at the end; a blank name, a name already used (any case) or a full list changes nothing. */
    fun create(folders: List<PlaylistFolder>, name: String): List<PlaylistFolder> {
        val tidy = clean(name)
        if (tidy.isEmpty() || folders.size >= MAX || folders.any { it.name.equals(tidy, ignoreCase = true) }) return folders
        return folders + PlaylistFolder(tidy)
    }

    /** The folder [from] called [to] instead, keeping its playlists and its place; not when [to] is blank or another folder's name. */
    fun rename(folders: List<PlaylistFolder>, from: String, to: String): List<PlaylistFolder> {
        val tidy = clean(to)
        if (tidy.isEmpty() || folders.any { it.name != from && it.name.equals(tidy, ignoreCase = true) }) return folders
        return folders.map { if (it.name == from) it.copy(name = tidy) else it }
    }

    /** [folders] without [name]; its playlists go back to the top of Playlists. */
    fun remove(folders: List<PlaylistFolder>, name: String): List<PlaylistFolder> = folders.filterNot { it.name == name }

    /** The folder [name] holding exactly [ids], in that order; each of them leaves whatever other folder held it. */
    fun setPlaylists(folders: List<PlaylistFolder>, name: String, ids: List<String>): List<PlaylistFolder> {
        val wanted = ids.filter { it.isNotBlank() && !it.contains(',') && !it.contains('\n') }.distinct()
        val moving = wanted.toSet()
        return folders.map { folder ->
            if (folder.name == name) folder.copy(playlistIds = wanted) else folder.copy(playlistIds = folder.playlistIds.filterNot { it in moving })
        }
    }

    /** The name of the folder holding playlist [id], if any. */
    fun folderOf(folders: List<PlaylistFolder>, id: String?): String? = id?.let { wanted -> folders.firstOrNull { wanted in it.playlistIds }?.name }

    /** [items] that sit in no folder: the top of Playlists. */
    fun <T> loose(items: List<T>, folders: List<PlaylistFolder>, id: (T) -> String?): List<T> {
        val filed = folders.flatMapTo(HashSet()) { it.playlistIds }
        return items.filter { item -> id(item)?.let { it !in filed } ?: true }
    }

    /** The playlists among [items] in the folder [name], in the order the page shows [items]. */
    fun <T> inFolder(items: List<T>, folders: List<PlaylistFolder>, name: String, id: (T) -> String?): List<T> {
        val ids = folders.firstOrNull { it.name == name }?.playlistIds?.toSet().orEmpty()
        return items.filter { item -> id(item)?.let { it in ids } ?: false }
    }

    /** How many of [items] each folder holds (a playlist since removed from the account is not counted). */
    fun <T> counts(items: List<T>, folders: List<PlaylistFolder>, id: (T) -> String?): Map<String, Int> {
        val present = items.mapNotNullTo(HashSet(), id)
        return folders.associate { folder -> folder.name to folder.playlistIds.count { it in present } }
    }

    private fun clean(name: String) = name.replace(Regex("[\t\r\n]+"), " ").trim().take(MAX_NAME)
}
