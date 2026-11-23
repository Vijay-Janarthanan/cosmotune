package com.cosmotune.video.downloads

import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request

/** The cover picture a download carries inside: the video's own thumbnail when it is a JPEG or PNG, else the site's JPEG one. */
class CoverFetcher(private val client: OkHttpClient = RangeDownloader.defaultClient()) {
    suspend fun fetch(item: VideoItem): ByteArray? {
        for (url in candidates(item)) {
            val bytes = try {
                get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            if (isPicture(bytes)) return bytes
        }
        return null
    }

    private suspend fun get(url: String): ByteArray? = client.newCall(Request.Builder().url(url).get().build()).await { response ->
        if (response.isSuccessful) response.body.bytes().takeIf { it.size <= MAX_BYTES } else null
    }

    companion object {
        private const val MAX_BYTES = 4 shl 20
        private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

        internal fun candidates(item: VideoItem): List<String> = listOfNotNull(
            item.thumbnailUrl?.takeIf { it.startsWith("https://") },
            "https://i.ytimg.com/vi/${item.id}/hqdefault.jpg".takeIf { VIDEO_ID.matches(item.id) },
        ).distinct()

        /** Whether [bytes] are a JPEG or a PNG (the two pictures an MP4 cover may be). */
        fun isPicture(bytes: ByteArray): Boolean =
            (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) ||
                (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte())
    }
}
