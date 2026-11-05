package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.Song
import com.cosmotune.providers.ProviderHub
import com.cosmotune.providers.ProviderInstall

/** Installed package providers participate in the same recording matcher as HTTP sources. */
internal class PackageMusicSource(private val install: ProviderInstall) : MusicSource {
    override val configId = "sflx-${install.manifest.name}"
    override val displayName get() = install.manifest.title
    override val kind = SourceKind.ADDON
    override suspend fun health() = SourceHealth.Ok()
    override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?): List<Song> {
        val manager = ProviderHub.state.value.manager ?: return emptyList()
        val tracks = manager.search(install.manifest.name, query, limit)
        return tracks.mapIndexed { index, track ->
            val enriched = if (request != null && track.durationMs == null && index < 3)
                manager.track(install.manifest.name, track.id) ?: track else track
            enriched.song()
        }
    }
    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val stream = ProviderHub.state.value.manager?.stream(install.manifest.name, trackId,
            maxKbps = (request as? StreamRequest.Capped)?.maxKbps, lossless = request is StreamRequest.Lossless) ?: return null
        return SourceStream(stream.url, StreamFormat(stream.codec, stream.kbps, stream.sampleRateHz, stream.bitDepth),
            durationSec = stream.durationMs?.div(1000)?.toInt(), sourceConfigId = configId)
    }
    companion object {
        fun enabled(): List<MusicSource> = ProviderHub.state.value.manager?.store?.state?.value?.installed.orEmpty()
            .filter { it.enabled && "download_provider" in it.manifest.type }.map(::PackageMusicSource)
    }
}
