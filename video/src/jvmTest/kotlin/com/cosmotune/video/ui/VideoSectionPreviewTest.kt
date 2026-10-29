package com.cosmotune.video.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.video.account.AccountChoice
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.ui.blocks.ChannelRow
import com.cosmotune.video.ui.blocks.ChannelStrip
import com.cosmotune.video.ui.blocks.LocalVideoNowMs
import com.cosmotune.video.ui.blocks.PlaylistRow
import com.cosmotune.video.ui.blocks.SubscribePill
import com.cosmotune.video.ui.blocks.VideoCard
import com.cosmotune.video.ui.blocks.VideoCardBody
import com.cosmotune.video.ui.blocks.VideoChips
import com.cosmotune.video.ui.blocks.VideoResultCard
import com.cosmotune.video.ui.home.CardSkeleton
import com.cosmotune.video.ui.library.YouHeader
import com.cosmotune.video.ui.shell.AccountSheetBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The blocks drawn to PNGs with fake data (no network, no graph): the cards, the result card with its description, the channel row
 * and the Subscribe pill, You and the account sheet, the Subscriptions strip and the placeholders, at a phone's width (390 dp) and a
 * desktop's (1280 dp), light and dark, into `build/ui-preview/`. The pictures are the gradients of [PreviewKit.standIn]. The test
 * only asserts that each picture is not blank; the looking is done by opening them.
 */
class VideoSectionPreviewTest {
    @BeforeTest fun mainDispatcher() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest fun resetMainDispatcher() = Dispatchers.resetMain()

    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private val titles = listOf(
        "Building a synthesizer from scratch: oscillators, filters and a very long title that needs two lines",
        "Lo-fi beats to study to",
        "The complete history of the pipe organ",
        "Live: mission control during the launch window",
        "Ten minutes of rain on a tin roof",
        "How a mechanical watch keeps time",
        "Cooking pasta the way they do in Naples",
        "A walk through the old town at dawn",
    )
    private val channels = listOf("Moog Lab", "Quiet Hours", "Organ Works", "Space Desk", "Rainy Day", "Watchmaker", "Cucina", "Slow Walks")

    private val videos: List<VideoItem> = titles.mapIndexed { i, title ->
        VideoItem(
            id = "preview$i",
            title = title,
            channelId = "channel$i",
            channelName = channels[i],
            channelAvatarUrl = null,
            thumbnailUrl = "https://media.cosmotune.invalid/preview/$i.jpg",
            durationSec = if (i == 3) null else 180L + i * 211,
            viewCount = 1_200L * (i + 1) * (i + 1),
            uploadedAtMs = now - (i + 1) * day,
            uploadedText = null,
            isLive = i == 3,
            snippet = "In this one we take a long, slow look at how it is made, from the first sketch to the last screw, with notes on what went wrong.",
        )
    }
    private val subscriptions = channels.mapIndexed { i, name ->
        Subscription("channel$i", name, null, now - 100 * day, if (i % 3 == 0) now - day else now - 20 * day)
    }

    private fun render(name: String, widthDp: Int, heightDp: Int, dark: Boolean, content: @Composable () -> Unit) =
        PreviewKit.render(name, widthDp, heightDp, dark, frames = 40, providers = arrayOf(LocalVideoNowMs provides now), content = content)

    private fun every(name: String, phoneHeight: Int, wideHeight: Int, phone: @Composable () -> Unit, wide: @Composable () -> Unit) {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            render("$name-phone-$theme", 390, phoneHeight, dark, phone)
            render("$name-desktop-$theme", 1280, wideHeight, dark, wide)
        }
    }

    // ---- Search -------------------------------------------------------------------------------------------------------------

    @Test fun searchDrawsOnAPhoneAndOnTheDesktop() {
        val channel = ChannelItem("channel0", "Moog Lab", null, 1_400_000, 320, null, handle = "@mooglab")
        val playlist = PlaylistItem("pl1", "Synth basics", null, "Moog Lab", "channel0", 24)
        every(
            "search", 1500, 1000,
            phone = {
                Column(Modifier.fillMaxWidth()) {
                    VideoChips(listOf("All", "Videos", "Shorts", "Channels", "Playlists"), 0, {})
                    VideoCard(videos[0], {}, {})
                    VideoCard(videos[3], {}, {})
                    ChannelRow(channel, subscribed = false, onClick = {}, onSubscribe = {})
                    PlaylistRow(playlist, {})
                }
            },
            wide = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 120.dp)) {
                    VideoChips(listOf("All", "Videos", "Shorts", "Channels", "Playlists"), 1, {})
                    VideoResultCard(videos[0], {}, {})
                    VideoResultCard(videos[3], {}, {})
                    ChannelRow(channel, subscribed = true, onClick = {}, onSubscribe = {})
                    VideoResultCard(videos[2], {}, {}, watchedFraction = 0.5f)
                }
            },
        )
    }

    // ---- You and the account ------------------------------------------------------------------------------------------------

    @Test fun youHeaderAndAccountSheetDraw() {
        val me = AccountChoice("1", "Vijay Janarthanan", "@vijay", null)
        val signedIn = AccountState.SignedIn("Vijay Janarthanan", "@vijay", null, listOf(me, AccountChoice("2", "Studio channel", null, null)), me)
        every(
            "you", 700, 560,
            phone = {
                Column(Modifier.fillMaxWidth()) {
                    YouHeader(signedIn, {}, {})
                    YouHeader(AccountState.SignedOut, {}, {})
                    Column { AccountSheetBody(signedIn, {}, {}, {}, {}) }
                }
            },
            wide = {
                Row(Modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    Column(Modifier.weight(1f)) { YouHeader(signedIn, {}, {}); YouHeader(AccountState.SignedOut, {}, {}) }
                    Column(Modifier.weight(1f)) { AccountSheetBody(AccountState.SignedOut, {}, {}, {}, {}) }
                }
            },
        )
    }

    // ---- Subscriptions and the placeholders ---------------------------------------------------------------------------------

    @Test fun subscriptionsStripAndPlaceholdersDraw() = every(
        "subscriptions", 900, 700,
        phone = {
            Column(Modifier.fillMaxWidth()) {
                ChannelStrip(subscriptions, selectedId = "channel0", onSelect = {})
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { SubscribePill(false, {}); SubscribePill(true, {}) }
                VideoCard(videos[0], {}, {})
                CardSkeleton(Modifier.padding(16.dp))
            }
        },
        wide = {
            Column(Modifier.fillMaxWidth()) {
                ChannelStrip(subscriptions, selectedId = null, onSelect = {}, modifier = Modifier.padding(horizontal = 12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (item in videos.take(3)) Box(Modifier.weight(1f)) { VideoCardBody(item, {}, {}, Modifier, null, item.title, false) }
                }
                Row(Modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { repeat(3) { CardSkeleton(Modifier.weight(1f)) } }
            }
        },
    )
}
