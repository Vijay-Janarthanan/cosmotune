package com.cosmotune.cloud

import com.cosmotune.music.data.covers.CoverJpeg
import com.cosmotune.music.data.covers.PlaylistCoverRules
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * A cover picked for one of the person's own playlists, as it travels between their devices: the entry id is the playlist id, and
 * the data is the small copy of the picture (about 400 px, JPEG) as base64. A playlist whose cover went back to the automatic one is
 * a delete. YouTube Music never sees any of it.
 *
 * The account keeps a kind in a few documents ([CloudSync.BUCKETS]) and a Firestore document holds at most 1 MiB, so a picture may
 * be at most [MAX_PICTURE_BYTES] and a bucket's covers at most [BUCKET_BUDGET] characters; a cover that does not fit stays on the
 * device it was picked on.
 */
object SyncPlaylistCovers {
    const val KIND = "covers"

    /** The account's copy is fitted under this when it is made (see [CoverJpeg.fitWithin]). */
    const val MAX_PICTURE_BYTES = 48_000

    /** Room for the covers in one bucket document, well under Firestore's 1 MiB with the entries' own fields. */
    const val BUCKET_BUDGET = 880_000

    private val base64 = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun data(jpeg: ByteArray): JsonObject = buildJsonObject { put("jpeg", base64.encodeToString(jpeg)) }

    /** The picture an entry carries, or null for one that is not a JPEG in base64. */
    fun jpeg(data: JsonObject): ByteArray? {
        val text = (data["jpeg"] as? JsonPrimitive)?.contentOrNull ?: return null
        val bytes = runCatching { decoder.decode(text) }.getOrNull() ?: return null
        return bytes.takeIf { CoverJpeg.looksLikeJpeg(it) && it.size <= MAX_PICTURE_BYTES * 2 }
    }

    /**
     * The covers as entries: only playlist ids, only pictures that are small enough, and, bucket by bucket in the order of their ids,
     * only as many as the bucket has room for. The same pictures give the same entries every time, so the journal sees no change.
     */
    fun snapshot(pictures: Map<String, ByteArray>): Map<String, JsonObject> {
        val used = HashMap<String, Int>()
        val out = LinkedHashMap<String, JsonObject>()
        for (id in pictures.keys.sorted()) {
            val bytes = pictures.getValue(id)
            if (PlaylistCoverRules.key(id) != id || bytes.size > MAX_PICTURE_BYTES || !CoverJpeg.looksLikeJpeg(bytes)) continue
            val data = data(bytes)
            val size = data.toString().length + id.length + ENTRY_OVERHEAD
            val bucket = CloudSync.bucketName(KIND, id)
            val total = (used[bucket] ?: 0) + size
            if (total > BUCKET_BUDGET) continue
            used[bucket] = total
            out[id] = data
        }
        return out
    }

    /** What an entry adds round its data in the bucket document (its time, its deleted flag, the field names). */
    private const val ENTRY_OVERHEAD = 96
}
