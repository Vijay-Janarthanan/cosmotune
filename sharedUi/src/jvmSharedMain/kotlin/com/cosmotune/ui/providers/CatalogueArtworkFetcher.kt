package com.cosmotune.ui.providers

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.cosmotune.providers.CatalogueArtwork
import com.cosmotune.providers.ProviderHub
import okio.FileSystem
import okio.buffer
import okio.source

/** Raw source addresses never enter the general image loader or its request logs. */
class CatalogueArtworkFetcher private constructor(private val reference: String) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val cache = ProviderHub.artwork ?: error("Source artwork is unavailable")
        val file = cache.resolve(reference)
        return SourceFetchResult(ImageSource(file.inputStream().source().buffer(), FileSystem.SYSTEM),
            mimeType = null, dataSource = DataSource.DISK)
    }
    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            data.toString().let { if (CatalogueArtwork.handles(it)) CatalogueArtworkFetcher(it) else null }
    }
}
