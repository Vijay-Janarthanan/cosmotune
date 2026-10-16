package com.cosmotune.video.player.android

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor

/**
 * Turns the engine's media items into Media3 sources (spec 6.2, "Sources"; plan decision W3).
 *
 * - **DASH**: a `data:application/dash+xml;base64,...` item (our static manifest of one video and one audio representation),
 *   with its subtitles as `SubtitleConfiguration`s, goes through [DefaultMediaSourceFactory]: the only way side-loaded subtitles
 *   are honoured (Media3's issue #185). The data source factory is wrapped in [DefaultDataSource.Factory], whose
 *   `DataSchemeDataSource` reads the `data:` address.
 * - **HLS** (live, `MimeTypes.APPLICATION_M3U8`): the same factory, 10 s behind the live edge ([LIVE_OFFSET_MS]).
 * - **The pair scheme** `cosmotune-video://pair?video=<base64url>&audio=<base64url>` ([pairUri]): progressive streams without
 *   DASH ranges and downloaded files ([com.cosmotune.video.player.MediaInput.Progressive] and `LocalFiles`), which a
 *   `MediaController` cannot carry as a source of its own. It becomes `MergingMediaSource(true, true, video, audio, subtitles...)`
 *   of progressive sources, with each subtitle added as its own [ProgressiveMediaSource] (the default factory is not involved
 *   there, so issue #185 applies: the subtitles are built here as the default factory builds them). Either stream may be absent
 *   (a muxed stream or a muxed file is the video alone).
 */
@OptIn(UnstableApi::class)
class VideoMediaSourceFactory(
    context: Context,
    private val dataSourceFactory: DataSource.Factory = dataSourceFactory(context),
) : MediaSource.Factory {

    private val default = DefaultMediaSourceFactory(dataSourceFactory).setLiveTargetOffsetMs(LIVE_OFFSET_MS)
    private val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)
    private val subtitleParsers = DefaultSubtitleParserFactory()
    private var drmProvider: DrmSessionManagerProvider? = null
    private var loadErrorPolicy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        drmProvider = drmSessionManagerProvider
        default.setDrmSessionManagerProvider(drmSessionManagerProvider)
        progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        loadErrorPolicy = loadErrorHandlingPolicy
        default.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (uri != null && uri.scheme == PAIR_SCHEME) return pair(mediaItem, uri)
        return default.createMediaSource(mediaItem)
    }

    private fun pair(mediaItem: MediaItem, uri: Uri): MediaSource {
        val streams = listOfNotNull(uri.getQueryParameter(PAIR_VIDEO)?.let(::decode), uri.getQueryParameter(PAIR_AUDIO)?.let(::decode))
        require(streams.isNotEmpty()) { "a pair address with neither a video nor an audio stream" }
        val children = ArrayList<MediaSource>(streams.size + 4)
        streams.forEachIndexed { index, address ->
            // The merged source reports its first child's item, and the session and the notification read the id, the title,
            // the channel and the thumbnail from there: so the first child carries the whole item with its own address.
            val item = if (index == 0) {
                mediaItem.buildUpon().setUri(address).setMimeType(null).setSubtitleConfigurations(emptyList()).build()
            } else {
                MediaItem.fromUri(address)
            }
            children += progressive.createMediaSource(item)
        }
        mediaItem.localConfiguration?.subtitleConfigurations?.forEach { subtitle -> subtitleSource(subtitle)?.let(children::add) }
        return MergingMediaSource(/* adjustPeriodTimeOffsets = */ true, /* clipDurations = */ true, *children.toTypedArray())
    }

    /**
     * One side-loaded subtitle as its own source, built the way [DefaultMediaSourceFactory] builds it (parsed while it is read,
     * into Media3's cue format), or null for a format no parser reads. Its duration is unknown (the subtitle extractor's seek
     * map says so), so the merge's `clipDurations` never shortens the video to the last cue.
     */
    private fun subtitleSource(subtitle: MediaItem.SubtitleConfiguration): MediaSource? {
        val format = Format.Builder()
            .setSampleMimeType(subtitle.mimeType)
            .setLanguage(subtitle.language)
            .setSelectionFlags(subtitle.selectionFlags)
            .setRoleFlags(subtitle.roleFlags)
            .setLabel(subtitle.label)
            .setId(subtitle.id)
            .build()
        if (!subtitleParsers.supportsFormat(format)) return null
        val extractors = ExtractorsFactory { arrayOf(SubtitleExtractor(subtitleParsers.create(format), format)) }
        val factory = ProgressiveMediaSource.Factory(dataSourceFactory, extractors)
        drmProvider?.let { factory.setDrmSessionManagerProvider(it) }
        loadErrorPolicy?.let { factory.setLoadErrorHandlingPolicy(it) }
        return factory.createMediaSource(MediaItem.fromUri(subtitle.uri))
    }

    companion object {
        /** The engine's own scheme for a pair of progressive streams or files. */
        const val PAIR_SCHEME = "cosmotune-video"

        private const val PAIR_VIDEO = "video"
        private const val PAIR_AUDIO = "audio"
        private const val BASE64URL = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

        /** How far behind the live edge a live stream plays (spec 5.13, 6.2): 10 s. */
        const val LIVE_OFFSET_MS = 10_000L

        /** `cosmotune-video://pair?video=<base64url>&audio=<base64url>`, each part only when its address is given. */
        fun pairUri(videoAddress: String?, audioAddress: String?): Uri {
            require(videoAddress != null || audioAddress != null) { "a pair needs a video or an audio stream" }
            val builder = Uri.Builder().scheme(PAIR_SCHEME).authority("pair")
            videoAddress?.let { builder.appendQueryParameter(PAIR_VIDEO, encode(it)) }
            audioAddress?.let { builder.appendQueryParameter(PAIR_AUDIO, encode(it)) }
            return builder.build()
        }

        private fun encode(address: String): String = Base64.encodeToString(address.toByteArray(Charsets.UTF_8), BASE64URL)

        private fun decode(part: String): String = String(Base64.decode(part, BASE64URL), Charsets.UTF_8)

        /**
         * The data sources of Video's player: googlevideo's range rules ([GooglevideoDataSource]) under the 2 MiB ranges
         * ([ChunkedRangeDataSource]), wrapped in [DefaultDataSource.Factory] so `data:` (the DASH manifest), `file:` and
         * `content:` (downloads) are read as well (decision W3).
         */
        fun dataSourceFactory(context: Context): DataSource.Factory =
            DefaultDataSource.Factory(context, ChunkedRangeDataSource.Factory(GooglevideoDataSource.Factory()))
    }
}
