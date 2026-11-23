package com.cosmotune.ui.pages

import com.cosmotune.music.data.model.LikeStatus
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.isMatchMissing
import com.cosmotune.music.data.model.isMatchPending
import com.cosmotune.settings.CosmoLookSettings
import kotlinx.coroutines.CancellationException

/**
 * "Keep downloaded" (a playlist page's switch, [CosmoLookSettings.keepDownloaded]) away from its page: one pass reads each kept
 * playlist, hands its new songs to the app's own downloads and remembers them, so a song the person removes from the device is not
 * fetched again. The page runs it while it is open; the phone's job (WorkManager, on Wi-Fi, at most daily and when the switch is
 * turned on) and the desktop (on start and once a day while it runs) run it in the background.
 */
object KeepDownloaded {
    /** A day: the background passes run at most this often. */
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** Liked Music's page id: its own "Keep downloaded" switch is what keeps liked songs downloaded. */
    const val LIKED_MUSIC = "VLLM"

    /** How long after a like the quick pass waits; each like restarts it, so a run of likes is one pass. */
    const val LIKED_SETTLE_MS = 20_000L

    /**
     * Whether a change of ratings from [before] to [now] wants a quick pass over Liked Music (upstream 1.10's "Keep liked songs
     * downloaded"): a song newly liked while Liked Music is [kept] downloaded. An unlike keeps its file and fetches nothing.
     */
    fun likedPassWanted(before: Map<String, LikeStatus>, now: Map<String, LikeStatus>, kept: Set<String>): Boolean =
        LIKED_MUSIC in kept && now.any { (id, status) -> status == LikeStatus.LIKE && before[id] != LikeStatus.LIKE }

    /** What a pass hands to the downloads of [songs]: the matched ones on neither the device ([onDevice]) nor in [kept], once each. */
    fun fresh(songs: List<Song>, onDevice: (String) -> Boolean, kept: Set<String>): List<Song> =
        songs.filter { !it.isMatchPending && !it.isMatchMissing && it.localPath == null && !onDevice(it.videoId) && it.videoId !in kept }
            .distinctBy { it.videoId }

    /** Whether the daily pass is due at [nowMs]: never run, a day or more since [lastMs], or a clock set back. */
    fun due(lastMs: Long, nowMs: Long): Boolean = lastMs <= 0L || nowMs < lastMs || nowMs - lastMs >= DAY_MS

    /**
     * One pass over [playlists] (every kept one unless told): each one's songs from [songsOf], the fresh ones handed to [download] with
     * the playlist's id and then remembered as kept (a hand-over that fails is tried again on the next pass). A playlist that cannot be
     * read, or whose switch was turned off meanwhile, is passed over. Marks the pass's time ([CosmoLookSettings.keepDownloadedCheckedMs])
     * and says how many songs it handed over.
     */
    suspend fun run(
        look: CosmoLookSettings,
        songsOf: suspend (String) -> List<Song>,
        onDevice: (String) -> Boolean,
        download: suspend (String, List<Song>) -> Unit,
        playlists: Collection<String> = look.keepDownloaded.value,
        nowMs: Long = System.currentTimeMillis(),
        /** False for a quick pass that read only a playlist's newest page: the whole daily pass stays due. */
        stamp: Boolean = true,
    ): Int {
        var handed = 0
        for (id in playlists.toList()) {
            if (id !in look.keepDownloaded.value) continue
            val songs = try { songsOf(id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { continue }
            val fresh = fresh(songs, onDevice, look.keptSongs(id))
            if (fresh.isEmpty()) continue
            download(id, fresh)
            look.addKeptSongs(id, fresh.map { it.videoId })
            handed += fresh.size
        }
        if (stamp) look.setKeepDownloadedCheckedMs(nowMs)
        return handed
    }
}

/**
 * Smart downloads (SimpMusic's, [CosmoLookSettings.smartDownloads]): the listener's most played songs kept downloaded without
 * them choosing any. Each pass takes the top [CosmoLookSettings.smartDownloads] of the play counts, downloads those on neither the
 * device nor already fetched, and removes again a song it fetched itself once that song falls well out of the top (past
 * [RELEASE_MARGIN] times the count, so a song hovering at the edge is not fetched and dropped day after day). A song the listener
 * downloaded themselves is never removed.
 */
object SmartDownloads {
    /** The choices the setting offers; 0 is off. */
    val COUNTS = listOf(0, 25, 50, 100)

    /** How far past the count a song this fetched may slip before it is removed. */
    const val RELEASE_MARGIN = 1.5

    private val STREAM_ID = Regex("[A-Za-z0-9_-]{11}")

    /** Whether [videoId] names a song that streams (a YouTube id), rather than a local file or an add-on's track. */
    fun streamable(videoId: String): Boolean = STREAM_ID.matches(videoId)

    /** What one pass does: the songs to download, and the ids of songs this fetched that go. */
    data class Plan(val download: List<Song>, val release: List<String>)

    /**
     * The pass for [ranked] (most played first) at [count]: the top songs not [onDevice] (and not already [kept]) to download;
     * the [kept] ones now below `count * RELEASE_MARGIN` to release. Off ([count] 0) releases everything it fetched.
     */
    fun plan(ranked: List<Song>, count: Int, onDevice: (String) -> Boolean, kept: Set<String>): Plan {
        val playable = ranked.filter { !it.isMatchPending && !it.isMatchMissing && it.localPath == null }.distinctBy { it.videoId }
        val top = playable.take(count)
        val download = top.filter { !onDevice(it.videoId) && it.videoId !in kept }
        val stay = playable.take((count * RELEASE_MARGIN).toInt()).map { it.videoId }.toSet()
        val release = kept.filter { it !in stay }
        return Plan(download, release)
    }

    /**
     * One pass: [ranked] read, the plan's songs handed to [download], its releases given to [remove] (a removal that fails stays
     * kept, to try again). The kept set is updated to what this fetched and still keeps. Says how many songs it handed over.
     */
    suspend fun run(
        look: CosmoLookSettings,
        ranked: suspend () -> List<Song>,
        onDevice: (String) -> Boolean,
        download: suspend (List<Song>) -> Unit,
        remove: suspend (String) -> Boolean,
    ): Int {
        val count = look.smartDownloads.value
        val kept = look.smartKept()
        if (count <= 0 && kept.isEmpty()) return 0
        val songs = if (count > 0) try { ranked() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return 0 } else emptyList()
        val plan = plan(songs, count, onDevice, kept)
        // A song a "Keep downloaded" playlist also fetched stays on the device; Smart downloads only lets go of it.
        val playlists = look.keepDownloaded.value.flatMap { look.keptSongs(it) }.toSet()
        val released = plan.release.filter { id -> id in playlists || runCatching { remove(id) }.getOrDefault(false) }.toSet()
        if (plan.download.isNotEmpty()) download(plan.download)
        look.setSmartKept(kept - released + plan.download.map { it.videoId })
        return plan.download.size
    }
}
