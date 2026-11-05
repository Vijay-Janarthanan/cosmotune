package com.cosmotune.music.data.cloud

import com.cosmotune.cloud.SyncLikes
import com.cosmotune.music.data.model.LikeStatus
import kotlinx.serialization.json.JsonObject

/**
 * How the phone's likes turn into cloud entries and back. Pure, so it is tested on the JVM; [AndroidCloud] keeps the file of likes
 * made here and paints the hearts through [com.cosmotune.music.data.LikeState].
 */
object CloudLikes {
    /** The likes made here as entries: the file holds exactly the entry data, so nothing needs converting. */
    fun snapshot(likes: Map<String, JsonObject>): Map<String, JsonObject> = likes.filterKeys(SyncLikes::travels)

    /** The part of a cloud change that may touch this phone: never a source rating or a local file's like. */
    fun travelling(upserts: Map<String, JsonObject>, deletes: Set<String>): Pair<Map<String, JsonObject>, Set<String>> =
        upserts.filterKeys(SyncLikes::travels) to deletes.filterTo(HashSet(), SyncLikes::travels)

    /**
     * The heart each synced change paints. A like from another device shows as liked; a like removed on another device shows as not
     * liked only where this session is showing it liked because of an override, so a song YouTube itself lists as liked is left alone.
     */
    fun overrides(liked: Set<String>, unliked: Set<String>, current: Map<String, LikeStatus>): Map<String, LikeStatus> = buildMap {
        for (id in liked) if (current[id] != LikeStatus.LIKE) put(id, LikeStatus.LIKE)
        for (id in unliked) if (id !in liked && current[id] == LikeStatus.LIKE) put(id, LikeStatus.INDIFFERENT)
    }
}
