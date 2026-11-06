package com.cosmotune.watch.plugins.youtube

import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.VideoSource
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * A broadcaster's own YouTube channel that carries its live TV: [youTubeId] is the channel id (`UC...`, 24 characters) the
 * broadcaster runs, checked by hand against its website or YouTube's verified badge; nothing else ever plays under its name.
 * [allDay] is true for a channel that streams around the clock (news), false for one that goes live for programmes and events
 * (DD National, DD Sports), which is labelled "Not 24/7".
 */
data class OfficialLiveChannel(
    /** The id inside the plugin's keys (`c:dd:DDNational`): letters only, never a colon. */
    val slug: String,
    val name: String,
    val youTubeId: String,
    val categories: List<String>,
    /** ISO 639-3, lower case. */
    val languages: List<String>,
    val countries: List<String> = listOf("in"),
    val allDay: Boolean = true,
    /** The iptv-org id of the same channel ("DDNews.in"), so guides and favourites line up with other lists. */
    val iptvOrgId: String? = null,
    /** The channel's number in Live TV, when it has a well-known one. */
    val number: Int? = null,
)

/**
 * What a channel has on now and next on YouTube: its live video (the first its Live tab lists, its main stream), every video
 * it has live ([lives]: a news channel often runs a special beside its 24x7 stream), and the streams it has scheduled.
 */
data class LiveOnYouTube(val live: VideoItem?, val upcoming: List<VideoItem>, val atMs: Long, val lives: List<VideoItem> = listOfNotNull(live))

/**
 * Live TV from broadcasters' official YouTube channels, through Video mode's own source (InnerTube with NewPipe behind it):
 * the channel list is fixed and checked by hand ([channelList]); what plays is whatever the channel itself has live at the
 * moment Play is pressed, resolved from its Live tab, handed to Video's player as a YouTube watch address. Nothing is
 * scraped from any other site and no address is stored. The guide is the live video's title (on now) and the channel's
 * scheduled streams (next). Quiet when Video mode's source is not available.
 */
open class OfficialLivePlugin(
    override val id: String,
    override val kind: PluginKind,
    override val displayName: String,
    override val badge: String,
    val channelList: List<OfficialLiveChannel>,
    private val video: () -> VideoSource?,
    private val now: () -> Long = System::currentTimeMillis,
    /** How long a channel's live lookup is kept: Play and the guide ask often, YouTube is asked at most this often. */
    private val liveTtlMs: Long = 3 * 60_000L,
) : WatchPlugin {
    override val capabilities = setOf(Capability.LIVE, Capability.EPG, Capability.STREAMS)
    private val source = StreamSource(id, displayName, badge)
    private val lookups = ConcurrentHashMap<String, LiveOnYouTube>()
    private val avatars = ConcurrentHashMap<String, String>()
    @Volatile private var avatarsLoaded = false

    fun official(slug: String): OfficialLiveChannel? = channelList.firstOrNull { it.slug == slug }

    fun channelOf(def: OfficialLiveChannel) = Channel(
        key = WatchKey.channel(id, def.slug), pluginId = id, name = def.name, number = def.number, logoUrl = avatars[def.youTubeId],
        categories = def.categories, countries = def.countries, languages = def.languages, epgId = def.slug, tvgId = def.iptvOrgId,
        labels = if (def.allDay) emptyList() else listOf(NOT_ALL_DAY),
    )

    override suspend fun channels(): List<Channel> {
        val src = video() ?: return emptyList()
        if (!avatarsLoaded) loadAvatars(src)
        return channelList.map(::channelOf)
    }

    /** The channels' pictures (their YouTube avatars), asked once per run and never for long. */
    private suspend fun loadAvatars(src: VideoSource) {
        withTimeoutOrNull(AVATAR_WAIT_MS) {
            coroutineScope {
                channelList.filter { !avatars.containsKey(it.youTubeId) }.map { def ->
                    async { runCatching { src.channel(def.youTubeId).channel.avatarUrl }.getOrNull()?.let { avatars[def.youTubeId] = it } }
                }.awaitAll()
            }
        }
        avatarsLoaded = true
    }

    /** What [def] has live and scheduled now, from its Live tab (kept [liveTtlMs]); null when YouTube cannot be asked. */
    suspend fun liveOf(def: OfficialLiveChannel, fresh: Boolean = false): LiveOnYouTube? {
        val t = now()
        lookups[def.youTubeId]?.takeIf { !fresh && t - it.atMs < liveTtlMs }?.let { return it }
        val src = video() ?: return null
        val items = src.channelTab(def.youTubeId, ChannelTab.LIVE).items.mapNotNull { (it as? ListItem.Video)?.item }
            // Only the broadcaster's own videos; a tab never holds others, but a collaboration or a mistake must not play under its name.
            .filter { it.channelId == null || it.channelId == def.youTubeId }
        val lives = items.filter { it.isLive }
        val result = LiveOnYouTube(lives.firstOrNull(), items.filter { it.isUpcoming }.sortedBy { it.uploadedAtMs ?: Long.MAX_VALUE }, t, lives)
        lookups[def.youTubeId] = result
        return result
    }

    override suspend fun streams(target: PlayTarget): List<Stream> = when (target) {
        is PlayTarget.Live -> {
            val def = target.channel.takeIf { it.pluginId == id }?.let { official(it.key.id) }
            val live = def?.let { runCatching { liveOf(it, fresh = true) }.getOrNull()?.live }
            if (def == null || live == null) emptyList() else listOf(stream(live.id, live.title, def.name))
        }
        is PlayTarget.Item -> if (target.pluginId == id && VIDEO_ID.matches(target.itemId)) listOf(stream(target.itemId, target.name, null)) else emptyList()
        else -> emptyList()
    }

    private fun stream(videoId: String, title: String, channelName: String?) = Stream(source, "Live on YouTube",
        listOfNotNull(channelName, cleanTitle(title)).distinct().joinToString(" · "), "https://www.youtube.com/watch?v=$videoId",
        StreamProtocol.YOUTUBE, isLive = true, isPublic = true)

    override suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): List<EpgProgramme> = coroutineScope {
        val t = now()
        channels.filter { it.pluginId == id }.mapNotNull { c -> official(c.key.id) }.distinctBy { it.slug }.map { def ->
            async {
                val live = runCatching { liveOf(def) }.getOrNull() ?: return@async emptyList()
                programmes(def, live, t).filter { it.stopMs > fromMs && it.startMs < toMs }
            }
        }.awaitAll().flatten()
    }

    /**
     * The live video as the programme on now (from its start when YouTube gives one, else the top of the hour, until at least
     * half an hour ahead, as YouTube publishes no end), then the scheduled streams, an hour each.
     */
    private fun programmes(def: OfficialLiveChannel, live: LiveOnYouTube, t: Long): List<EpgProgramme> {
        val out = mutableListOf<EpgProgramme>()
        var cursor = t
        live.live?.let { v ->
            val start = v.uploadedAtMs?.takeIf { it <= t } ?: (t / HOUR * HOUR)
            val nextStart = live.upcoming.firstNotNullOfOrNull { it.uploadedAtMs }?.takeIf { it > t }
            val stop = nextStart ?: maxOf(start + HOUR, t + 30 * 60_000L)
            out += EpgProgramme(def.slug, start, stop, cleanTitle(v.title), iconUrl = v.thumbnailUrl, categories = def.categories)
            cursor = stop
        }
        for (v in live.upcoming) {
            val start = v.uploadedAtMs?.takeIf { it >= cursor } ?: continue
            out += EpgProgramme(def.slug, start, start + HOUR, cleanTitle(v.title), iconUrl = v.thumbnailUrl, categories = def.categories)
            cursor = start + HOUR
        }
        return out
    }

    override suspend fun health(): String? = if (video() == null) "Needs Video mode" else null

    companion object {
        const val NOT_ALL_DAY = "Not 24/7"
        private const val HOUR = 3_600_000L
        private const val AVATAR_WAIT_MS = 8_000L
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /** "🔴LIVE : Chennai Rains | Sun News" reads "Chennai Rains | Sun News"; HTML entities YouTube leaves in are decoded. */
        fun cleanTitle(title: String): String {
            val decoded = title.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
            val stripped = decoded.replace(Regex("^[\\s\\p{So}\\uFE0F]*(LIVE\\s*[:|\\-–]?|(Live|live)\\s*[:|\\-–])\\s*"), "").trim()
            return stripped.ifBlank { decoded.trim() }
        }
    }
}
