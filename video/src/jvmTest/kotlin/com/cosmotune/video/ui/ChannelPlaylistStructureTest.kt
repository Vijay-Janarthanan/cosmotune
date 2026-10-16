package com.cosmotune.video.ui

import com.cosmotune.video.model.ChannelPage
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSourceError
import com.cosmotune.video.ui.channel.AboutText
import com.cosmotune.video.ui.channel.ChannelModel
import com.cosmotune.video.ui.channel.ChannelSection
import com.cosmotune.video.ui.channel.ChannelSections
import com.cosmotune.video.ui.channel.Loaded
import com.cosmotune.video.ui.channel.ModelCache
import com.cosmotune.video.ui.channel.PagedItems
import com.cosmotune.video.ui.channel.PagedState
import com.cosmotune.video.ui.channel.ShareLinks
import com.cosmotune.video.ui.channel.videosOf
import com.cosmotune.video.ui.playlist.PlaylistModel
import com.cosmotune.video.ui.playlist.PlaylistText
import com.cosmotune.video.ui.playlist.ReorderMath
import com.cosmotune.video.ui.playlist.RowBounds
import com.cosmotune.video.ui.playlist.startPlaylist
import com.cosmotune.video.ui.shell.VideoNavigator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The channel and playlist pages (plan task Y-20, step 4). What no render can show is read from the source: where the live palette is read,
 * that every clickable has a role and every lazy item a key, where the numbers come from, that no text is a literal, and the three branch
 * lines of the nav host. The pieces of logic the pages are made of are run: the sections of a channel, the paged lists (against the
 * fake source), the model cache, the playlist's totals, the arithmetic of a drag, and what a tap on a row starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelPlaylistStructureTest {
    private val channelDir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/channel"
    private val playlistDir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/playlist"

    /** The files the brief lists, and the helpers they are built from. */
    private val briefs = listOf("channel/ChannelPage", "channel/ChannelHeader", "channel/ChannelAbout", "playlist/PlaylistPage", "playlist/PlaylistHeader")
    private val helpers = listOf(
        "channel/ChannelModel", "channel/PageItems", "channel/Branded", "channel/ShareLinks",
        "playlist/PlaylistModel", "playlist/PlaylistReorder", "playlist/PlaylistDialogs",
    )
    private val all = briefs + helpers

    private fun path(name: String) = (if (name.startsWith("channel/")) channelDir else playlistDir) + "/" + name.substringAfter('/') + ".kt"
    private fun source(name: String) = VideoSourceContract.read(path(name))
    private fun code(name: String) = VideoSourceContract.code(source(name))

    // ---- The files, the signatures, the branches ---------------------------------------------------------------------------------

    @Test fun theDirectoriesHoldTheBriefsFilesAndTheHelpers() {
        for (name in all) assertTrue(File(VideoSourceContract.root, path(name)).isFile, "$name is at ${path(name)}")
        val channel = File(VideoSourceContract.root, channelDir).listFiles { f -> f.extension == "kt" }!!.map { "channel/" + it.nameWithoutExtension }.toSet()
        val playlist = File(VideoSourceContract.root, playlistDir).listFiles { f -> f.extension == "kt" }!!.map { "playlist/" + it.nameWithoutExtension }.toSet()
        assertEquals(all.toSet(), channel + playlist, "no file but the listed ones")
    }

    @Test fun theTwoPagesKeepTheBriefsSignatures() {
        assertEquals(
            listOf("route: VideoRoute.Channel", "contentPadding: PaddingValues", "wide: Boolean"),
            VideoSourceContract.parameters(source("channel/ChannelPage"), "ChannelPage"),
        )
        assertEquals(
            listOf("remoteId: String?", "localId: String?", "contentPadding: PaddingValues", "wide: Boolean"),
            VideoSourceContract.parameters(source("playlist/PlaylistPage"), "PlaylistPage"),
        )
        assertTrue(Regex("""(?m)^@Composable\s+fun ChannelPage\(""").containsMatchIn(source("channel/ChannelPage")), "a public composable")
        assertTrue(Regex("""(?m)^@Composable\s+fun PlaylistPage\(""").containsMatchIn(source("playlist/PlaylistPage")), "a public composable")
    }

    @Test fun theNavHostRoutesTheThreeBranchesToThePagesAndTouchesNoOtherBranch() {
        val lines = VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell/VideoNavHost.kt").lines()
        fun branch(route: String) = lines.single { it.trim().startsWith("is VideoRoute.$route ->") }.trim()
        assertEquals("is VideoRoute.Channel -> com.cosmotune.video.ui.channel.ChannelPage(r, contentPadding, wide)", branch("Channel"))
        assertEquals("is VideoRoute.Playlist -> com.cosmotune.video.ui.playlist.PlaylistPage(r.playlistId, null, contentPadding, wide)", branch("Playlist"))
        assertEquals("is VideoRoute.LocalPlaylist -> com.cosmotune.video.ui.playlist.PlaylistPage(null, r.id, contentPadding, wide)", branch("LocalPlaylist"))
        // no line of Y-20 is left as a placeholder, and the branches of the others keep their markers
        assertFalse(lines.any { "Y-20 replaces" in it })
        for ((route, owner) in listOf("Home" to "Y-17", "Subscriptions" to "Y-21", "Library" to "Y-22", "Search" to "Y-18", "Watch" to "Y-19", "Shorts" to "Y-31", "Downloads" to "Y-33", "Settings" to "Y-25")) {
            val line = branch(route)
            if ("VideoPlaceholder(r)" in line) assertTrue(line.endsWith("// $owner replaces this line"), "$route keeps its marker: $line")
        }
        // a blank line before each of the three, as before
        for (route in listOf("Channel", "Playlist", "LocalPlaylist")) {
            val at = lines.indexOfFirst { it.trim().startsWith("is VideoRoute.$route ->") }
            assertTrue(lines[at - 1].isBlank(), "a blank line sits before the branch of $route")
        }
        assertTrue(lines.count { "VideoActionsHost(contentPadding)" in it } == 1, "Y-16's action host is still there once")
    }

    // ---- Rules of the whole set ---------------------------------------------------------------------------------------------------

    @Test fun theLivePaletteIsReadOnlyInDrawPhaseLambdas() {
        var seen = 0
        for (file in all) {
            val src = code(file)
            var from = 0
            while (true) {
                val at = src.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (src[at - 1].isLetterOrDigit() || src[at - 1] == '_')) continue
                seen++
                val inDraw = enclosingHeads(src, at).any { drawHead.containsMatchIn(it) }
                assertTrue(inDraw, "$file: live( at line ${src.take(at).count { it == '\n' } + 1} is not in a draw lambda")
            }
        }
        assertTrue(seen >= 9, "the check found the pages' live reads (found $seen)")
    }

    @Test fun nothingRemembersALiveColourOrABrushAndNoDrawLambdaAllocates() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\()""")
        for (file in all) {
            val src = code(file)
            assertNull(remembering.find(src), "$file remembers a brush or a live colour")
            var from = 0
            while (true) {
                val at = src.indexOf("drawBehind {", from)
                if (at < 0) break
                from = at + 1
                var depth = 0
                var end = at
                for (i in at until src.length) {
                    if (src[i] == '{') depth++
                    if (src[i] == '}' && --depth == 0) { end = i; break }
                }
                val body = src.substring(at, end)
                assertFalse(Regex("""Brush\.|Path\(|\.copy\(|listOf\(|mutableListOf|\.toList\(\)|\.map \{""").containsMatchIn(body), "$file: a draw lambda builds something per frame:\n$body")
            }
        }
    }

    @Test fun nothingReadsThePlayheadOrTheMusicAccountOrNamesTheSource() {
        for (file in all) {
            val src = source(file)
            val code = VideoSourceContract.code(src)
            assertFalse("positionMs()" in code, "$file reads the player's position")
            assertFalse("org.schabi" in code, "$file imports the extractor")
            assertFalse(Regex("""(?i)cookie|CookieJar""").containsMatchIn(src), "$file touches cookies")
            // the one file that holds an address names the site; nothing a person reads does
            if (file != "channel/ShareLinks") assertFalse(Regex("""(?i)youtube|\bYT\b""").containsMatchIn(src), "$file names the source")
        }
        assertTrue("youtube.com" in source("channel/ShareLinks"))
        assertFalse(File(VideoSourceContract.root, channelDir).walkTopDown().any { it.name.contains("youtube", ignoreCase = true) })
    }

    @Test fun everyClickableHasARole() {
        var seen = 0
        for (file in all) {
            val src = code(file)
            for (match in Regex("""\b(clickable|combinedClickable|selectable|toggleable)\(""").findAll(src)) {
                seen++
                assertTrue("role =" in argumentsOf(src, match.range.last), "$file: ${match.value} has no role")
            }
        }
        assertTrue(seen >= 2, "found the clickables (found $seen)")
    }

    @Test fun everyLazyItemHasAKey() {
        var seen = 0
        for (file in all) {
            val src = code(file)
            for (match in Regex("""\b(items|itemsIndexed|item)\(""").findAll(src)) {
                if (match.range.first > 0 && (src[match.range.first - 1].isLetterOrDigit() || src[match.range.first - 1] == '_' || src[match.range.first - 1] == '.')) continue
                seen++
                assertTrue("key =" in argumentsOf(src, match.range.last), "$file: ${match.value} has no key")
            }
        }
        assertTrue(seen >= 6, "found the lazy items (found $seen)")
        // the keys of a list of videos do not repeat
        assertTrue("uniqueKeys(" in code("channel/ChannelPage") && "uniqueKeys(" in code("playlist/PlaylistPage"))
        // the page items write their keys through
        val items = code("channel/PageItems")
        assertTrue(Regex("""scope\.item\(key = key""").containsMatchIn(items) && items.count { it == '\n' } > 0)
        assertTrue("scope.items(count = items.size, key = { key(it, items[it]) }" in items)
    }

    @Test fun noTextShownToPeopleIsALiteralAndEveryStringKeyExists() {
        val strings = VideoSourceContract.read("video/src/commonMain/composeResources/values/strings.xml")
        val declared = Regex("""<(?:string|plurals) name="(\w+)"""").findAll(strings).map { it.groupValues[1] }.toSet()
        for (file in all) {
            val src = code(file)
            assertFalse(Regex("""\bText\(\s*(text\s*=\s*)?"""").containsMatchIn(src), "$file: a Text takes a literal")
            assertFalse(Regex("""\b(contentDescription|label|text|title|subtitle|message)\s*=\s*"""").containsMatchIn(src), "$file: a shown value is a literal")
            for (key in Regex("""Res\.(?:string|plurals)\.(video_\w+)""").findAll(src).map { it.groupValues[1] }.toSet()) {
                assertTrue(key in declared, "$file uses $key, which strings.xml does not declare")
            }
        }
        // the three strings this task adds stand together under a comment of their own, English only
        val lines = strings.lines()
        val at = lines.indexOfFirst { "(Y-20)" in it }
        assertTrue(at > 0, "the comment of this task's strings")
        assertEquals(listOf("video_remove", "video_move_up", "video_move_down"), lines.subList(at + 1, at + 4).map { Regex("""name="(\w+)"""").find(it)!!.groupValues[1] })
    }

    @Test fun noColourIsPickedByHand() {
        for (file in all) {
            val src = code(file)
            assertFalse(Regex("""Color\(0x""").containsMatchIn(src), "$file picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|White|Black)\b""").containsMatchIn(src), "$file uses a named colour")
        }
    }

    @Test fun thePagesTakeOnlyPartTwosBlocksAndTheWorkOfTheOtherTasksThroughTheirStubsAndHandler() {
        for (file in all) {
            val imports = Regex("""(?m)^import (com\.music\.bitchord\.[\w.]+)""").findAll(source(file)).map { it.groupValues[1] }.toSet()
            // part two's resources, and nothing of Music's screens or its player
            assertTrue(imports.all { it.startsWith("com.music.bitchord.sharedui.resources.") }, "$file imports from Music: $imports")
        }
        val page = code("channel/ChannelPage")
        for (name in listOf("ShortsShelf(", "ShortsGrid(", "openShort(", "rememberVideoActions()", "rememberDownloadedIds()", "actions.play(", "actions.downloadBatch(", "VideoActionsSheet(")) {
            assertTrue(name in page, "the channel page calls $name")
        }
        for (name in listOf("rememberVideoTitle", "rememberVideoThumbnail")) assertTrue(name in code("channel/Branded"), "the cards ask for DeArrow through $name")
        assertFalse("BrandingProvider" in all.joinToString { code(it) } || "graph.branding" in all.joinToString { code(it) }, "a screen never calls the DeArrow client itself")
    }

    // ---- The channel page --------------------------------------------------------------------------------------------------------

    @Test fun theBannerIsDrawnInDrawWithContentAndFadesOverItsLower40PercentIntoBackgroundTop() {
        val header = code("channel/ChannelHeader")
        assertTrue("VideoMetrics.channelBannerHeightDp(widthDp)" in header, "0.274 x W tall on the phone")
        assertTrue("VideoMetrics.DESKTOP_BANNER_DP.dp" in header && "SuperellipseShape(CosmoRadii.lg)" in header, "180 dp with radius 22 on a wide page")
        val draw = Regex("""\.drawWithContent \{.*?\n            \}""", RegexOption.DOT_MATCHES_ALL).find(header)
        assertNotNull(draw, "the banner has a drawWithContent")
        assertTrue("drawContent()" in draw.value && "colors.live(CoverField.BACKGROUND_TOP)" in draw.value, "the fade is drawn over the picture, in the page's ground")
        assertTrue("private const val BANNER_FADE_FROM = 0.60f" in header && "BANNER_FADE_FROM to ground.copy(alpha = 0f), 1f to ground" in header, "clear to 60 %, solid at the foot")
        assertTrue("if (!wide)" in draw.value, "no fade on the wide page's rounded banner")
    }

    @Test fun theHeadIsA72dpAvatarWithItsShadowAndTheNameInTitleLOr96AndDisplaySWhenWide() {
        val header = code("channel/ChannelHeader")
        assertTrue("VideoMetrics.CHANNEL_AVATAR_DESKTOP_DP else VideoMetrics.CHANNEL_AVATAR_DP" in header)
        assertTrue("type.display(TypeScale.DISPLAY_S) else type.title(TypeScale.TITLE_L)" in header)
        assertTrue("maxLines = 2" in header, "the name takes two lines")
        assertTrue("ChannelAvatar(channel.avatarUrl, avatar, Modifier.avatarShadow())" in header)
        val shadow = Regex("""fun Modifier\.avatarShadow\(\).*?\n}\n""", RegexOption.DOT_MATCHES_ALL).find(header)!!.value
        assertTrue("graphicsLayer" in shadow && "shadowElevation" in shadow && "colors.live(CoverField.SHADOW)" in shadow, "the shadow is the cover's, read in the layer block")
        assertTrue("MetaText(handle" in header && "VideoMetrics.FEED_META_SP" in header, "the handle and the counts in 13 sp")
        assertTrue("padding(start = margin, end = margin, top = 12.dp)" in header, "padding 12 16 0")
    }

    @Test fun theActionsAreSubscribeShareAndDownloadTheLatest() {
        val header = code("channel/ChannelHeader")
        assertTrue("kind = if (subscribed) CosmoButtonKind.TONAL else CosmoButtonKind.PRIMARY" in header && "size = CosmoButtonSize.SMALL" in header)
        assertTrue(Regex("""icon = CosmoIcons\.Share,.*?style = CosmoIconButtonStyle\.TONAL,\s+size = 40\.dp""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(header), "Share is a 40 dp tonal icon button")
        assertTrue("padding(start = margin, end = margin, top = 12.dp, bottom = 4.dp)" in header, "padding 12 16 4")
        assertTrue("Res.string.video_dl_latest" in header, "Download the latest")
        val page = code("channel/ChannelPage")
        assertTrue("DownloadBatchInfo(channel.id, BatchKind.CHANNEL_LATEST, channel.name)" in page && "latestPicker = true" in page)
        assertTrue("const val DOWNLOAD_LATEST_MAX = 50" in page && "model.uploads(ChannelSections.uploadsTab(section), DOWNLOAD_LATEST_MAX)" in page, "the tab is read until 50 uploads are in")
        assertTrue("graph.library.subscribe(channel)" in page && "graph.library.unsubscribe(channel.id)" in page)
    }

    @Test fun theChipsAreTheChannelsTabsAndShortsLeaveWithHideShorts() {
        val page = code("channel/ChannelPage")
        assertTrue("ChannelSections.of(page.tabs, hideShorts)" in page, "the chips are for the tabs the channel has, less Shorts when they are hidden")
        assertTrue("val shortsOffered = ChannelSection.SHORTS in sections" in page, "the shelf and the Shorts tab exist only while Shorts are offered")
        assertTrue("ui.section == ChannelSection.VIDEOS && ui.shortsOffered" in page, "the shelf is on the Videos tab only")
        assertTrue("SHORTS_SHELF_COUNT = 10" in page && "shorts.take(SHORTS_SHELF_COUNT)" in page, "the first 10 of the Shorts tab")
        assertTrue("onSeeAll = { ui.onSelect(ChannelSection.SHORTS) }" in page, "See all selects the Shorts tab")
        assertTrue("ShortsFilter.filter(videosOf(paged.items), ui.hideShorts) { it }" in page, "Shorts are filtered out of the video tabs while hidden")
        assertTrue("ShortsOrigin.Channel(channel.id)" in page, "a Short opens the pager with the channel as its origin")
        assertTrue(Regex("""ShortsGrid\(\s+items = shorts,\s+onOpen = \{ ui\.onOpenShort\(it, shorts\) \}""").containsMatchIn(page), "the Shorts tab is the grid, a Short opens the pager with the grid's Shorts")
        assertTrue("val hideShorts = settings.hideShorts" in page, "the switch is the Video settings'")
    }

    @Test fun theTabsFeedCardsAndPlaylistRowsAndTheWidePageIsAGrid() {
        val page = code("channel/ChannelPage")
        assertTrue("BrandedVideoCell(item, isDownloaded, onClick, onMore) else BrandedVideoCard(item, isDownloaded, onClick, onMore)" in page, "the phone's card, the wide page's grid cell")
        assertTrue("PlaylistRow(item = playlist, onClick = { ui.onOpenPlaylist(playlist.id) }" in page, "playlists are playlist rows")
        assertTrue("VideoGrid(" in page && "VideoGridSpec.of(widthDp)" in page, "a grid when wide")
        assertTrue("ChannelAbout(ui.page, line, ui.wide)" in page, "the About tab")
        assertTrue("pagedStatus(" in page, "a tab loads in pages")
        assertTrue("ui.actions.play(item, videos)" in page, "a card tap goes through the handler (a Short opens the pager)")
        assertTrue("VideoSheetContext.DEFAULT" in page)
    }

    @Test fun theShortsTabMovesTheHeadWithTheGridThatTakesNoHeader() {
        val page = code("channel/ChannelPage")
        assertTrue("class HeaderScroll(private val scrolled: MutableState<Float>) : NestedScrollConnection" in page)
        assertTrue("override fun onPostScroll(" in page && "scrolled.value - consumed.y" in page, "tracks what the grid consumed")
        assertTrue("translationY = -scrolled.value" in page && Regex("""graphicsLayer \{\s+translationY""").containsMatchIn(page), "the head moves in a layer block, not in composition")
        assertTrue("top = with(density) { headerPx.toDp() }" in page, "the grid starts under the head")
    }

    @Test fun theAboutTabHasTheDescriptionTheJoinDateAndTheLinks() {
        val about = code("channel/ChannelAbout")
        assertTrue("cosmoTile(shape)" in about && "SuperellipseShape(CosmoRadii.card)" in about, "a tile")
        assertTrue("Res.string.video_channel_about" in about && "Res.string.video_channel_joined" in about && "Res.string.video_channel_links" in about)
        assertTrue("LocalUriHandler.current" in about && "AboutText.openable(link)" in about && "role = Role.Button" in about, "a link opens only if it is a web address, as a button")
        assertTrue("onTextLayout" in about && "hasVisualOverflow" in about, "Read more waits for the layout")
    }

    // ---- The playlist page --------------------------------------------------------------------------------------------------------

    @Test fun thePhoneHeadIsTheHeroTheTitleBlockAndTheButtons() {
        val header = code("playlist/PlaylistHeader")
        assertTrue("HeroThumbnail(url, Modifier.fillMaxWidth().aspectRatio(16f / 9f))" in header, "the window less the margins, 16:9")
        assertTrue("padding(start = margin, end = margin, top = 6.dp)" in header, "6 dp from the top")
        assertTrue("SuperellipseShape(CosmoRadii.lg).shadowShape" in header && "HERO_LIFT = 24.dp" in header && "COVER_LIFT = 14.dp" in header, "radius 22, the album hero's deeper shadow")
        assertTrue("Res.string.video_your_playlist" in header && "Res.string.video_saved_playlist" in header && "Res.string.video_playlist" in header, "the overline")
        assertTrue("type.display(TypeScale.DISPLAY_S) else type.title(TypeScale.TITLE_L)" in header && "maxLines = if (wide) 2 else 3" in header, "title(TITLE_L) in three lines, display(DISPLAY_S) in two when wide")
        assertTrue("OWNER_AVATAR = 28.dp" in header && "OWNER_NAME_SP = 15f" in header && "FontWeight(650)" in header, "the owner row")
        assertTrue("WIDE_THUMB_WIDTH = 320.dp" in header && "WIDE_THUMB_HEIGHT = 180.dp" in header, "the wide hero row")
        assertTrue("VideoText.durationParts(sec)" in header && "VideoText.duration(sec)" in header, "the total time comes from VideoText")
    }

    @Test fun theButtonsArePlayAllShuffleSaveDownloadAllAndMore() {
        val header = code("playlist/PlaylistHeader")
        assertTrue(Regex("""text = playAll,\s+onClick = actions\.onPlayAll,\s+kind = CosmoButtonKind\.PRIMARY,\s+size = CosmoButtonSize\.LARGE""").containsMatchIn(header), "Play all is PRIMARY and LARGE")
        assertTrue(Regex("""icon = CosmoIcons\.Shuffle,.*?style = CosmoIconButtonStyle\.TONAL,\s+size = 48\.dp""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(header), "Shuffle is a 48 dp tonal icon")
        assertTrue(Regex("""icon = VideoIcons\.Bookmark,.*?style = CosmoIconButtonStyle\.TONAL,\s+size = 48\.dp,\s+on = shown\.saved""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(header), "Save is a bookmark, on when saved")
        assertTrue(Regex("""icon = CosmoIcons\.Download,.*?style = CosmoIconButtonStyle\.TONAL,\s+size = 48\.dp""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(header), "Download all is a 48 dp tonal icon")
        assertTrue("style = CosmoIconButtonStyle.PLAIN" in header && "CosmoIcons.MoreVertical" in header, "More is plain")
        assertTrue("Res.string.video_dl_all" in header && "Res.string.video_save_playlist" in header && "Res.string.video_unsave_playlist" in header)
        assertTrue("if (maxWidth < NARROW_DP) ACTIONS_GAP_NARROW else ACTIONS_GAP" in header && "ACTIONS_GAP = 8.dp" in header)
    }

    @Test fun thePlaylistPageSavesPlaysAndDownloadsThroughTheLibraryThePlaybackAndTheHandler() {
        val page = code("playlist/PlaylistPage")
        assertTrue("playback.play(start.id, queue = queue)" in page && "navigator.push(VideoRoute.Watch(start.id))" in page, "Play all opens the watch page and plays with the list as the queue")
        assertTrue("openShort(start, queue)" in page, "a Short opens the pager")
        assertTrue("graph.library.savePlaylist(" in page && "graph.library.unsavePlaylist(p.id)" in page, "Save goes through the library")
        assertTrue("DownloadBatchInfo(playlist.id, BatchKind.PLAYLIST, playlist.title)" in page && "latestPicker = false" in page, "Download all, a batch of the playlist")
        assertTrue("model.everything()" in page, "the remaining pages are loaded before a remote playlist is offered")
        assertTrue("DownloadBatchInfo(id, BatchKind.PLAYLIST, playlist.name)" in page, "your own playlist")
        assertTrue("library.moveInPlaylist(id, from, to)" in page && "library.moveInPlaylist(id, index, index + by)" in page, "reordering is the library's")
        assertTrue("library.removeFromPlaylist(id, item.id)" in page && "SwipeToDismissBox(" in page, "a swipe removes")
        assertTrue("VideoSheetContext.YOUR_PLAYLIST" in page && "playlistId = id" in page, "the sheet of your playlist removes from this playlist")
        assertTrue("RenamePlaylistDialog(" in page && "DeletePlaylistDialog(" in page && "library.renamePlaylist(id, name)" in page && "library.deletePlaylist(id)" in page)
        assertTrue("ListPageItems(this).pagedStatus(\"playlist\", paged, rowStyle = true" in page, "a remote playlist reads on as the end comes into view")
        val dialogs = code("playlist/PlaylistDialogs")
        assertTrue("CosmoDialogCard(" in dialogs && "Res.string.video_rename" in dialogs && "Res.string.video_delete_playlist_confirm" in dialogs, "Rename and Delete go through CosmoDialogCard")
    }

    @Test fun theRowsOfYourPlaylistCarryAHandleAndAnAccessibleWayToMoveAndRemove() {
        val page = code("playlist/PlaylistPage")
        assertTrue("trailing = {" in page && "DragHandle(onStart = { reorder.start(key) }, onDrag = reorder::drag, onEnd = { reorder.end(key) })" in page)
        assertTrue("customActions = buildList" in page && "CustomAccessibilityAction(moveUp)" in page && "CustomAccessibilityAction(moveDown)" in page && "CustomAccessibilityAction(remove)" in page)
        assertTrue("translationY = reorder.translationOf(key)" in page && "gesturesEnabled = reorder.heldKey == null" in page, "the held row follows the finger in a layer block; a swipe waits for the drag")
        val reorder = code("playlist/PlaylistReorder")
        assertTrue("detectDragGestures(" in reorder && "onDragCancel" in reorder, "a cancelled drag ends")
        assertTrue("SharedRes.string.drag_to_reorder" in reorder, "the handle is described")
        assertTrue("fun ReorderAutoScroll(" in reorder && "listState.scrollBy(step)" in reorder)
    }

    // ---- The sections of a channel ------------------------------------------------------------------------------------------------

    @Test fun aChannelOffersItsTabsInTheSpecsOrderAndAboutLast() {
        val all = ChannelTab.entries
        assertEquals(listOf(ChannelSection.VIDEOS, ChannelSection.SHORTS, ChannelSection.LIVE, ChannelSection.PLAYLISTS, ChannelSection.ABOUT), ChannelSections.of(all, hideShorts = false))
        // the order is the spec's, whatever order the source lists the tabs in
        assertEquals(
            listOf(ChannelSection.VIDEOS, ChannelSection.PLAYLISTS, ChannelSection.ABOUT),
            ChannelSections.of(listOf(ChannelTab.PLAYLISTS, ChannelTab.VIDEOS), hideShorts = false),
        )
        assertEquals(listOf(ChannelSection.ABOUT), ChannelSections.of(emptyList(), hideShorts = false), "a channel with no tab still has About")
    }

    @Test fun hideShortsTakesTheShortsTabAway() {
        val sections = ChannelSections.of(ChannelTab.entries, hideShorts = true)
        assertEquals(listOf(ChannelSection.VIDEOS, ChannelSection.LIVE, ChannelSection.PLAYLISTS, ChannelSection.ABOUT), sections)
        assertFalse(ChannelSection.SHORTS in sections)
        // a route that asks for the Shorts tab lands on the first tab that is there
        assertEquals(ChannelSection.VIDEOS, ChannelSections.resolve(ChannelSection.SHORTS, sections))
        assertEquals(ChannelSection.LIVE, ChannelSections.resolve(ChannelSection.LIVE, sections))
        assertEquals(ChannelSection.ABOUT, ChannelSections.resolve(ChannelSection.SHORTS, listOf(ChannelSection.ABOUT)))
    }

    @Test fun aSectionNameIsReadBackAndAWrongOneFallsBack() {
        for (section in ChannelSection.entries) assertEquals(section, ChannelSections.parse(section.name, ChannelSection.ABOUT))
        assertEquals(ChannelSection.LIVE, ChannelSections.parse("nonsense", ChannelSection.LIVE))
        assertEquals(ChannelSection.LIVE, ChannelSections.parse(null, ChannelSection.LIVE))
        for (tab in ChannelTab.entries) assertEquals(tab, ChannelSections.tabOf(ChannelSections.sectionOf(tab)))
        assertNull(ChannelSections.tabOf(ChannelSection.ABOUT))
    }

    @Test fun downloadTheLatestTakesTheOpenTabsUploadsAndTheVideosTabOtherwise() {
        assertEquals(ChannelTab.VIDEOS, ChannelSections.uploadsTab(ChannelSection.VIDEOS))
        assertEquals(ChannelTab.SHORTS, ChannelSections.uploadsTab(ChannelSection.SHORTS))
        assertEquals(ChannelTab.LIVE, ChannelSections.uploadsTab(ChannelSection.LIVE))
        assertEquals(ChannelTab.VIDEOS, ChannelSections.uploadsTab(ChannelSection.PLAYLISTS))
        assertEquals(ChannelTab.VIDEOS, ChannelSections.uploadsTab(ChannelSection.ABOUT))
    }

    // ---- A list in pages ---------------------------------------------------------------------------------------------------------

    private fun pageOf(items: IntRange, next: Int?): Page<Int> = Page(items.toList(), next?.let { PageToken("p:$it") })

    @Test fun aPagedListLoadsItsFirstPageOnceAndTheNextOnesOnDemand() = runTest {
        val asked = mutableListOf<String?>()
        val list = PagedItems(this) { token ->
            asked += token?.value
            when (token?.value) {
                null -> pageOf(1..3, 4)
                "p:4" -> pageOf(4..6, 7)
                else -> pageOf(7..8, null)
            }
        }
        assertEquals(PagedState.IDLE, list.state)
        list.start()
        list.start()
        assertEquals(PagedState.LOADING_FIRST, list.state)
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), asked, "started twice, asked once")
        assertEquals(listOf(1, 2, 3), list.items)
        assertTrue(list.hasMore && list.state == PagedState.READY)
        list.loadMore()
        list.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(null, "p:4"), asked, "a page is asked for once however often the end calls")
        assertEquals((1..6).toList(), list.items)
        list.loadMore()
        advanceUntilIdle()
        assertEquals((1..8).toList(), list.items)
        assertFalse(list.hasMore)
        list.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(null, "p:4", "p:7"), asked, "nothing is asked after the last page")
    }

    @Test fun aFailedFirstPageCanBeRetriedAndALaterOneKeepsWhatIsLoaded() = runTest {
        var fail = true
        val list = PagedItems(this) { token ->
            if (fail) throw VideoSourceError.Network("offline", null)
            if (token == null) pageOf(1..2, 3) else pageOf(3..4, null)
        }
        list.start()
        advanceUntilIdle()
        assertEquals(PagedState.FAILED_FIRST, list.state)
        assertTrue(list.items.isEmpty() && list.error is VideoSourceError.Network)
        list.loadMore()
        advanceUntilIdle()
        assertEquals(PagedState.FAILED_FIRST, list.state, "no more is asked for while the first page is missing")
        fail = false
        list.retry()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), list.items)
        fail = true
        list.loadMore()
        advanceUntilIdle()
        assertEquals(PagedState.FAILED_MORE, list.state)
        assertEquals(listOf(1, 2), list.items, "a failure keeps what was loaded")
        assertTrue(list.hasMore)
        fail = false
        list.retry()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3, 4), list.items)
        assertEquals(PagedState.READY, list.state)
    }

    @Test fun loadUntilReadsPagesUntilThereAreEnoughOrTheEndComes() = runTest {
        val list = PagedItems(this) { token ->
            val from = token?.value?.substringAfter(':')?.toInt() ?: 0
            Page((from until from + 10).toList(), if (from + 10 < 35) PageToken("p:${from + 10}") else null)
        }
        list.loadUntil(25)
        assertEquals(30, list.items.size, "three pages of ten hold 25")
        assertTrue(list.hasMore)
        list.loadUntil(1_000)
        assertEquals(40, list.items.size, "the fourth page is the last")
        assertFalse(list.hasMore)
        assertEquals(PagedState.READY, list.state)
    }

    @Test fun loadUntilStopsAtAFailureAndReturnsWhatIsLoaded() = runTest {
        var calls = 0
        val list = PagedItems(this) { token ->
            calls++
            if (calls == 3) throw VideoSourceError.Network("offline", null)
            val from = token?.value?.substringAfter(':')?.toInt() ?: 0
            Page((from until from + 10).toList(), PageToken("p:${from + 10}"))
        }
        list.loadUntil(100)
        assertEquals(20, list.items.size)
        assertEquals(PagedState.FAILED_MORE, list.state)
    }

    // ---- The models, against the fake source -------------------------------------------------------------------------------------

    @Test fun aChannelModelLoadsTheHeaderAndEachTabApart() = runTest {
        val model = ChannelModel(FakeVideoSource(), this, FakeVideoSource.CHANNEL_1)
        assertEquals(Loaded.Loading, model.header)
        model.start()
        model.start()
        advanceUntilIdle()
        val header = model.header as Loaded.Ready<ChannelPage>
        assertEquals(FakeVideoSource.CHANNEL_1, header.value.channel.id)
        assertEquals(ChannelTab.entries, header.value.tabs)
        val videos = model.tab(ChannelTab.VIDEOS)
        val shorts = model.tab(ChannelTab.SHORTS)
        assertSame(videos, model.tab(ChannelTab.VIDEOS), "one list for a tab")
        videos.start()
        shorts.start()
        advanceUntilIdle()
        assertEquals((1..6).map { "fakevideo0$it" }, videosOf(videos.items).map { it.id })
        assertTrue(videosOf(shorts.items).isNotEmpty() && videosOf(shorts.items).all { it.isShort }, "the Shorts tab is Shorts")
        assertEquals(PagedState.IDLE, model.tab(ChannelTab.LIVE).state, "a tab nobody opened was not loaded")
    }

    @Test fun aChannelModelThatFailedIsNotWorthKeepingAndRetriesTheHeader() = runTest {
        val source = FakeVideoSource()
        val model = ChannelModel(source, this, FakeVideoSource.CHANNEL_2)
        source.failNext(VideoSourceError.Network("offline", null))
        model.start()
        advanceUntilIdle()
        assertTrue(model.header is Loaded.Failed)
        assertFalse(model.reusable)
        model.retry()
        advanceUntilIdle()
        assertTrue(model.header is Loaded.Ready)
        assertTrue(model.reusable)
    }

    @Test fun uploadsReadsATabUntilItHoldsEnoughVideos() = runTest {
        val model = ChannelModel(FakeVideoSource(), this, FakeVideoSource.CHANNEL_1)
        val uploads = model.uploads(ChannelTab.VIDEOS, 50)
        assertEquals(6, uploads.size, "the fake channel has six uploads: the tab ends")
        assertTrue(uploads.none { it.isLive || it.isUpcoming })
        val shorts = model.uploads(ChannelTab.SHORTS, 3)
        assertTrue(shorts.size >= 3 && shorts.all { it.isShort })
    }

    @Test fun aPlaylistModelTakesItsHeaderFromTheFirstPageAndReadsEverythingOnDemand() = runTest {
        val model = PlaylistModel(FakeVideoSource(), this, FakeVideoSource.PLAYLIST_ID)
        assertNull(model.info)
        model.start()
        advanceUntilIdle()
        assertEquals("Thirty for the road", model.info!!.playlist.title)
        assertEquals("Thirty sample videos, for paging ten at a time.", model.info!!.description)
        assertEquals(10, model.videos.items.size)
        assertTrue(model.videos.hasMore)
        val everything = model.everything()
        assertEquals(30, everything.size)
        assertEquals(FakeVideoSource.playlistItems.map { it.id }, everything.map { it.id })
        assertFalse(model.videos.hasMore)
        assertEquals("Thirty for the road", model.info!!.playlist.title, "a later page does not replace the header")
    }

    @Test fun aPlaylistThatCouldNotBeReadIsNotKept() = runTest {
        val model = PlaylistModel(FakeVideoSource(), this, "PLnothere")
        model.start()
        advanceUntilIdle()
        assertEquals(PagedState.FAILED_FIRST, model.videos.state)
        assertNull(model.info)
        assertFalse(model.reusable)
    }

    // ---- The model cache ---------------------------------------------------------------------------------------------------------

    @Test fun theCacheKeepsAModelForItsOwnerUntilItIsOldAndForgetsTheLeastRecentlyUsed() {
        var now = 0L
        val cache = ModelCache<StringBuilder>(capacity = 2, ttlMs = 1_000, clock = { now })
        val owner = Any()
        val a = cache.obtain("a", owner) { StringBuilder("a") }
        assertSame(a, cache.obtain("a", owner) { StringBuilder("again") }, "found where it was left")
        val b = cache.obtain("b", owner) { StringBuilder("b") }
        cache.obtain("a", owner) { StringBuilder("not made") }
        cache.obtain("c", owner) { StringBuilder("c") }
        assertEquals(2, cache.size())
        assertSame(a, cache.obtain("a", owner) { StringBuilder("not made") }, "a was used more recently than b")
        assertTrue(cache.obtain("b", owner) { StringBuilder("b2") } !== b, "b went out first")
        now = 2_000
        assertTrue(cache.obtain("a", owner) { StringBuilder("fresh") } !== a, "a model older than the limit is made again")
        val other = Any()
        val c1 = cache.obtain("x", owner) { StringBuilder("x1") }
        assertTrue(cache.obtain("x", other) { StringBuilder("x2") } !== c1, "a model made for another source is not handed to this one")
    }

    @Test fun aModelThatIsNotReusableIsMadeAgain() {
        val cache = ModelCache<StringBuilder>(capacity = 4, ttlMs = 10_000, clock = { 0L }, reusable = { it.isNotEmpty() })
        val owner = Any()
        val empty = cache.obtain("k", owner) { StringBuilder() }
        val next = cache.obtain("k", owner) { StringBuilder("made") }
        assertTrue(next !== empty && next.toString() == "made")
        assertSame(next, cache.obtain("k", owner) { StringBuilder("not made") })
    }

    // ---- The words and the arithmetic -------------------------------------------------------------------------------------------

    private fun item(id: String, durationSec: Long?, live: Boolean = false, upcoming: Boolean = false) =
        FakeVideoSource.videos[0].copy(id = id, durationSec = durationSec, isLive = live, isUpcoming = upcoming)

    @Test fun aPlaylistsTotalTimeIsTheSumWhenEveryDurationIsKnown() {
        assertEquals(3_600L + 125L, PlaylistText.totalSec(listOf(item("a", 3_600), item("b", 125))))
        assertNull(PlaylistText.totalSec(emptyList()), "nothing to add up")
        assertNull(PlaylistText.totalSec(listOf(item("a", 60), item("b", null))), "a duration is missing")
        assertNull(PlaylistText.totalSec(listOf(item("a", 60), item("b", 100, live = true))), "a live stream has no length")
        assertNull(PlaylistText.totalSec(listOf(item("a", 60), item("b", 100, upcoming = true))))
        assertEquals(FakeVideoSource.playlistItems.sumOf { it.durationSec!! }, PlaylistText.totalSec(FakeVideoSource.playlistItems))
    }

    @Test fun theOwnersInitialIsTheFirstLetterOrDigitInUpperCase() {
        assertEquals("N", PlaylistText.initial("Northern Signal"))
        assertEquals("2", PlaylistText.initial("  2 Cellos"))
        assertEquals("", PlaylistText.initial("   "))
        assertEquals("", PlaylistText.initial("***"))
    }

    @Test fun theJoinDateIsTheSourcesOwnSentenceOrOursMadeFromTheDate() {
        assertEquals("Joined 3 Mar 2015", AboutText.joined("Joined 3 Mar 2015", "Joined", "Joined Joined 3 Mar 2015"))
        assertEquals("Joined 3 Mar 2015", AboutText.joined("3 Mar 2015", "Joined", "Joined 3 Mar 2015"))
        assertEquals("Joined 2015", AboutText.joined("2015", "", "Joined 2015"))
    }

    @Test fun onlyWebAddressesOpenAndTheyAreShownWithoutTheirScheme() {
        assertTrue(AboutText.openable("https://example.com/a") && AboutText.openable("HTTP://example.com"))
        assertFalse(AboutText.openable("javascript:alert(1)") || AboutText.openable("file:///etc/passwd") || AboutText.openable("mailto:a@b.c") || AboutText.openable(""))
        assertEquals("example.com/a", AboutText.display("https://example.com/a/"))
        assertEquals("example.com", AboutText.display("http://example.com"))
    }

    @Test fun theSharedAddressesCarryTheIds() {
        assertEquals("https://www.youtube.com/channel/UCabc_-123", ShareLinks.channel("UCabc_-123"))
        assertEquals("https://www.youtube.com/playlist?list=PLabc", ShareLinks.playlist("PLabc"))
    }

    // ---- The drag ----------------------------------------------------------------------------------------------------------------

    @Test fun aHeldRowIsCarriedOntoTheRowItsMiddleLiesOver() {
        val rows = listOf(RowBounds(3, 0, 100), RowBounds(4, 100, 100), RowBounds(5, 200, 100), RowBounds(6, 300, 100))
        assertNull(ReorderMath.target(4, 150f, rows), "the middle is still over the row itself")
        assertEquals(5, ReorderMath.target(4, 200f, rows), "the middle has crossed into the next row")
        assertEquals(3, ReorderMath.target(4, 99.9f, rows), "and into the one above")
        assertEquals(6, ReorderMath.target(3, 399f, rows), "a long drag lands on the row under the finger")
        assertNull(ReorderMath.target(4, -5f, rows), "over none of the visible rows")
        assertNull(ReorderMath.target(4, 400f, rows), "the end of a row belongs to the next")
    }

    @Test fun theListScrollsFasterTheNearerAHeldRowIsToAnEdge() {
        // the visible part of the list is 100 to 900, the edge zone 50 deep, a step is at most 20
        fun step(top: Float) = ReorderMath.autoScroll(top, top + 100f, 100f, 900f, 50f, 20f)
        assertEquals(0f, step(400f))
        assertEquals(0f, ReorderMath.autoScroll(150f, 250f, 100f, 900f, 50f, 20f), "exactly on the edge of the zone")
        assertEquals(-10f, ReorderMath.autoScroll(125f, 225f, 100f, 900f, 50f, 20f))
        assertEquals(-20f, ReorderMath.autoScroll(100f, 200f, 100f, 900f, 50f, 20f), "at the edge: full speed up")
        assertEquals(-20f, ReorderMath.autoScroll(40f, 140f, 100f, 900f, 50f, 20f), "past the edge: no faster")
        assertEquals(10f, ReorderMath.autoScroll(775f, 875f, 100f, 900f, 50f, 20f))
        assertEquals(20f, ReorderMath.autoScroll(800f, 900f, 100f, 900f, 50f, 20f))
        assertEquals(0f, ReorderMath.autoScroll(0f, 100f, 100f, 900f, 0f, 20f), "no zone, no scrolling")
    }

    // ---- What a tap on a row starts ---------------------------------------------------------------------------------------------

    @Test fun aTapOnAVideoRowOpensTheWatchPageAndPlaysWithTheWholeListAsTheQueue() {
        val navigator = VideoNavigator()
        val playback = FakeVideoPlayback()
        val list = FakeVideoSource.playlistItems.take(5)
        var shorts = 0
        startPlaylist(list[2], list, navigator, playback) { _, _ -> shorts++ }
        assertEquals(VideoRoute.Watch(list[2].id), navigator.top)
        assertEquals(listOf("play(${list[2].id})"), playback.calls)
        assertEquals(list, playback.state.value.queue, "the whole list is the queue the controller cuts at the start")
        assertEquals(0, shorts)
    }

    @Test fun aShortInAPlaylistOpensThePagerInsteadAndStartsNoVideo() {
        val navigator = VideoNavigator()
        val playback = FakeVideoPlayback()
        val opened = mutableListOf<Pair<VideoItem, List<VideoItem>>>()
        val list = listOf(FakeVideoSource.videos[0], FakeVideoSource.shorts[0])
        startPlaylist(list[1], list, navigator, playback) { s, l -> opened += s to l }
        assertEquals(VideoRoute.Home, navigator.top)
        assertEquals(emptyList(), playback.calls)
        assertEquals(listOf(list[1] to list), opened)
    }

    @Test fun theChannelsFakeSourceListsVideosAndPlaylistsAsMixedItems() = runTest {
        // what the Playlists tab and the Videos tab hand the page: playlists as ListItem.Playlist, videos as ListItem.Video
        val source = FakeVideoSource()
        val playlists = PagedItems(this) { token -> source.channelTab(FakeVideoSource.CHANNEL_1, ChannelTab.PLAYLISTS, token) }
        playlists.start()
        advanceUntilIdle()
        assertEquals(listOf(FakeVideoSource.PLAYLIST_ID), playlists.items.mapNotNull { (it as? ListItem.Playlist)?.item?.id })
        assertTrue(videosOf(playlists.items).isEmpty())
    }

    // ---- helpers of the source reading ---------------------------------------------------------------------------------------------

    private fun enclosingHeads(code: String, index: Int): List<String> {
        val heads = ArrayList<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (code[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else heads += code.substring(maxOf(0, i - 120), i)
            }
            i--
        }
        return heads
    }

    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer|Canvas)\s*(\([^)]*\))?\s*$""")

    private fun argumentsOf(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return code.substring(open + 1, i)
            }
        }
        error("the parenthesis at $open is never closed")
    }
}
