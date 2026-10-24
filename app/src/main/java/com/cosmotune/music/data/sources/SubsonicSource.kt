package com.cosmotune.music.data.sources

import com.cosmotune.music.data.Http
import com.cosmotune.music.data.TrackLog
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.subsonic.SubsonicException
import com.cosmotune.providers.ProviderBrowseKey
import com.cosmotune.providers.SourceCataloguePage
import com.cosmotune.providers.SubsonicCatalogue
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

private const val TAG = "Cosmotune"

/**
 * The listener's own Subsonic/OpenSubsonic server as a [MusicSource]: its search feeds the Addons
 * search and cross-source matching, its albums and artists open through [SourceCatalogue], and a
 * row from it streams the stored file. The stream address carries credentials, so it is handed to
 * the player and never logged.
 */
class SubsonicSource(
    override val config: SourceConfig,
    private val http: OkHttpClient = Http.client,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.SUBSONIC
    override val displayName: String get() = config.displayName

    /** Null while the stored settings lack an address or credential. */
    val catalogue: SubsonicCatalogue? by lazy { SubsonicCatalogue.forSource(config.id, config.baseUrl, config.userSettings, http) }

    override suspend fun health(): SourceHealth {
        val client = catalogue?.client ?: return SourceHealth.Rejected("Add the user name and password or API key")
        return try {
            val info = client.ping()
            SourceHealth.Ok(listOfNotNull(info.type, info.version.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { null })
        } catch (error: SubsonicException) {
            if (error.code in 40..50) SourceHealth.Rejected(error.message ?: "Sign-in refused") else SourceHealth.Unreachable(error.message ?: "No answer")
        } catch (error: Exception) {
            SourceHealth.Unreachable(error.message ?: "No answer")
        }
    }

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?): List<Song> {
        val songs = catalogue?.searchSongs(query, limit).orEmpty()
        TrackLog.d(TAG, "Subsonic ${config.displayName}: ${songs.size} rows for \"$query\"")
        return songs
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val stream = catalogue?.stream(trackId) ?: return null
        TrackLog.d(TAG, "Subsonic ${config.displayName}: serving ${stream.codec ?: "an unstated format"}")
        return SourceStream(
            url = stream.url,
            format = StreamFormat(codec = stream.codec, kbps = stream.kbps, sampleRateHz = stream.sampleRateHz, bitDepth = stream.bitDepth),
            durationSec = stream.durationSec,
        )
    }

    /** Catalogue pages in the shape the shared catalogue browser reads. */
    suspend fun catalogueResponse(resource: String, id: String): JsonObject =
        requireNotNull(catalogue) { "Add the user name and password or API key" }.load(resource, id)

    suspend fun page(key: ProviderBrowseKey): SourceCataloguePage =
        requireNotNull(catalogue) { "Add the user name and password or API key" }.page(key)
}
