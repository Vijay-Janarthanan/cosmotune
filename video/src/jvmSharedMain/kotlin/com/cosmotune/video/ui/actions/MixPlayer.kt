package com.cosmotune.video.ui.actions

import com.cosmotune.video.account.VideoAccount
import com.cosmotune.video.model.PlaylistRef
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.VideoPlayback

/**
 * Plays a suggested mix as a mix: its first video is already playing; this asks the site for the mix's videos in its order (and, as a
 * mix has no end, again from the last one, up to [ROUNDS] stretches) and hands them to the player as the queue, so Up next, Next and
 * autoplay walk the mix. A failed ask leaves the first video playing alone.
 */
internal suspend fun playMix(account: VideoAccount, mix: PlaylistRef, playback: VideoPlayback, rounds: Int = MIX_ROUNDS) {
    val first = mix.firstVideoId ?: return
    val videos = LinkedHashMap<String, VideoItem>()
    var from = first
    for (round in 0 until rounds) {
        val stretch = account.mix(mix.id, from).getOrNull() ?: break
        val before = videos.size
        for (video in stretch) if (!video.isShort) videos.putIfAbsent(video.id, video)
        if (videos.size == before) break
        from = videos.keys.last()
    }
    val queue = videos.values.filter { it.id != first }
    if (queue.isNotEmpty()) playback.play(first, queue = queue)
}

internal const val MIX_ROUNDS = 3
