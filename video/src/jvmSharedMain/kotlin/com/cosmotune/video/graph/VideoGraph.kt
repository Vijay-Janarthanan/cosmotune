package com.cosmotune.video.graph

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import com.cosmotune.video.account.SignedOutVideoAccount
import com.cosmotune.video.account.VideoAccount
import com.cosmotune.video.downloads.DownloadController
import com.cosmotune.video.feed.SubscriptionFeed
import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.music.VideoMusicBridge
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.segments.BrandingProvider
import com.cosmotune.video.segments.SegmentProvider
import com.cosmotune.video.segments.VotesProvider
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.shorts.ShortsFeed
import com.cosmotune.video.source.VideoSource

/**
 * One of everything Video mode's screens use (spec 4.3), built once per process by [rememberVideoGraph] and read from
 * [LocalVideoGraph]. Every slot is an interface: the factories of each platform choose the class behind it, one line each.
 *
 * - [source] the catalogue: kiosks, search, details, channels, playlists, comments; [library] what lives on the device;
 *   [settings] the preferences; [segments], [branding] and [votes] the three web extras; [feed] the subscription feed;
 *   [downloads] the download queue;
 * - [playback] what the screens drive and [surface] draws its picture (null where no picture can be drawn yet);
 * - [music] hands a video to Music as a song; [metered] says whether the connection costs money (downloads, quality choice);
 *   [shorts] feeds the Shorts pager;
 * - [account] the listener's own recommendations, subscriptions, playlists and history when a Google account is signed in;
 *   it shares the sign-in Music holds, and is signed out (every call fails with NotSignedIn) otherwise.
 */
@Stable
class VideoGraph(
    val source: VideoSource,
    val library: VideoLibrary,
    val settings: VideoSettingsRepository,
    val segments: SegmentProvider,
    val branding: BrandingProvider,
    val votes: VotesProvider,
    val feed: SubscriptionFeed,
    val downloads: DownloadController,
    val playback: VideoPlayback,
    val surface: VideoSurfaceProvider?,
    val music: VideoMusicBridge,
    val metered: () -> Boolean,
    val shorts: ShortsFeed,
    val account: VideoAccount = SignedOutVideoAccount,
)

/** The graph of the screen being drawn; provided by the shell ([com.cosmotune.video.ui.CosmoVideoApp] on the phone, the desktop page). */
val LocalVideoGraph = staticCompositionLocalOf<VideoGraph> { error("LocalVideoGraph: no VideoGraph is provided here") }
