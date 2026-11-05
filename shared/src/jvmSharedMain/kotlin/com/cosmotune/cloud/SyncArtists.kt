package com.cosmotune.cloud

import com.cosmotune.listening.ListeningState
import com.cosmotune.listening.WatchedArtist
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A followed artist (the release watch of [com.cosmotune.listening.ListeningStore]) as it travels: the artist id and its name.
 * Which releases were already seen and when the artist was last checked stay on the device: each device keeps its own inbox.
 *
 * Source-catalogue follows (SourceLibraryStore.followed) are not part of this kind. Their ids cannot collide with these, but following
 * one means saving the provider's page with its tracks, which only a device with that provider installed (and its server in reach)
 * can do, and their keys can be far longer than a cloud map key may be.
 */
object SyncArtists {
    const val KIND = "artists"

    /** Same limits as the store: at most 50 followed artists, a name of 1 to 80 characters. */
    private const val MAX_ARTISTS = 50
    private const val MAX_NAME = 80

    fun snapshot(artists: List<WatchedArtist>): Map<String, JsonObject> =
        artists.associate { it.id to buildJsonObject { put("name", it.name) } }

    fun name(data: JsonObject): String? = (data["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.length in 1..MAX_NAME }

    /**
     * [state] with cloud changes laid over it, as watchArtist and unwatchArtist would make them: a new artist is added (while there is
     * room), a followed one takes the synced name and keeps its release history, and an unfollowed one leaves with its inbox notices.
     * An entry that cannot be applied (no usable name, or the list is full) is left out, and the sync leaves it unrecorded.
     */
    fun apply(state: ListeningState, upserts: Map<String, JsonObject>, deletes: Set<String>): ListeningState {
        val gone = deletes - upserts.keys
        val artists = state.artists.filterNot { it.id in gone }.map { artist ->
            val name = upserts[artist.id]?.let(::name) ?: return@map artist
            artist.copy(name = name)
        }.toMutableList()
        for ((id, data) in upserts) {
            if (id.isBlank() || artists.any { it.id == id } || artists.size >= MAX_ARTISTS) continue
            artists += WatchedArtist(id, name(data) ?: continue)
        }
        return state.copy(artists = artists, inbox = state.inbox.filterNot { it.artistId in gone })
    }
}
