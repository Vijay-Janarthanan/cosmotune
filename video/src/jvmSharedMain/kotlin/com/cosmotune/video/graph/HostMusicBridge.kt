package com.cosmotune.video.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.music.VideoMusicBridge
import com.cosmotune.video.ui.VideoHostLinks

/** Video's way to hand a video to Music (spec 9: "Play as a song"): the host's own [VideoHostLinks.playAsSong]. */
class HostMusicBridge(private val links: VideoHostLinks) : VideoMusicBridge {
    override fun playAsSong(item: VideoItem) {
        links.playAsSong(item.id, item.title, item.channelName.orEmpty(), item.thumbnailUrl)
    }
}

/**
 * The host's links as the process-wide graph sees them. The graph lives as long as the process; the links belong to the screen
 * that is drawn (the phone's activity, the desktop's window), so the graph holds this relay, which forwards to whichever links
 * are [attach]ed now and does nothing while none are. A host that goes (the mode flips to Music, the activity is recreated)
 * [detach]es its own links and takes nothing with it.
 */
internal class HostLinksRelay {
    @Volatile
    private var current: VideoHostLinks? = null

    /** The links the graph is built with. */
    val links: VideoHostLinks = VideoHostLinks(
        switchToMusic = { current?.switchToMusic?.invoke() },
        playAsSong = { id, title, channel, thumbnail -> current?.playAsSong?.invoke(id, title, channel, thumbnail) },
        pauseMusic = { current?.pauseMusic?.invoke() },
        pendingVideo = { current?.pendingVideo?.invoke() },
        onPendingHandled = { current?.onPendingHandled?.invoke() },
    )

    fun attach(host: VideoHostLinks) {
        current = host
    }

    fun detach(host: VideoHostLinks) {
        if (current === host) current = null
    }
}

/** Keeps [host] attached to [relay] while this call is in the composition. */
@Composable
internal fun AttachHostLinks(relay: HostLinksRelay, host: VideoHostLinks) {
    DisposableEffect(relay, host) {
        relay.attach(host)
        onDispose { relay.detach(host) }
    }
}
