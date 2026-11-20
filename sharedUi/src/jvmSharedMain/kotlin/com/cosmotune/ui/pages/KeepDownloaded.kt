package com.cosmotune.ui.pages

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
        look.setKeepDownloadedCheckedMs(nowMs)
        return handed
    }
}
