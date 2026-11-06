package com.cosmotune.video.ui

import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.blocks.VideoAction
import com.cosmotune.video.ui.blocks.VideoGridSpec
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.blocks.uniqueKeys
import com.cosmotune.video.ui.blocks.videoActionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The video building blocks (plan task Y-13, step 6): thumbnails, cards, rows, the channel strip, the chips, the grid, the video
 * sheet and the downloaded badge. What no render can show is read from the source: where the live palette is read, that every
 * clickable has a role, that every lazy list has keys, where the numbers come from, that no text is a literal; and the small pieces
 * of logic the blocks are made of are run.
 */
class VideoBlocksStructureTest {
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/blocks"
    private fun block(name: String) = VideoSourceContract.read("$dir/$name.kt")
    private fun code(name: String) = VideoSourceContract.code(block(name))

    /** The eleven files the brief lists, and the one helper that carries the touch, hold and focus behaviour. */
    private val listed = listOf(
        "ThumbnailBox", "VideoCard", "VideoShelfCard", "VideoShelf", "VideoRow", "PlaylistRow", "ChannelStrip", "VideoChips", "VideoGrid", "MetaLine",
        "VideoActionsSheet",
    )
    private val all = listed + "VideoClickable"

    private fun params(file: String, function: String) = VideoSourceContract.parameters(block(file), function)

    // ---- The signatures of the brief ----------------------------------------------------------------------------------------

    @Test fun theDirectoryHoldsTheBriefsFilesAndTheOneHelper() {
        val names = java.io.File(VideoSourceContract.root, dir).listFiles { f -> f.extension == "kt" }!!.map { it.nameWithoutExtension }.toSet()
        assertTrue(names.containsAll(all), "missing: ${all.toSet() - names}")
    }

    @Test fun thumbnailBoxAndTheCardsKeepTheBriefsParametersInOrder() {
        assertEquals(
            listOf(
                "url: String?", "widthDp: Dp", "modifier: Modifier = Modifier",
            ),
            params("ThumbnailBox", "ThumbnailBox"),
        )
        assertEquals(
            listOf(
                "item: VideoItem", "onClick: () -> Unit", "onMore: () -> Unit", "modifier: Modifier = Modifier", "watchedFraction: Float? = null",
                "title: String = item.title", "downloaded: Boolean = false", "onAvatarClick: (() -> Unit)? = null",
            ),
            params("VideoCard", "VideoCard"),
        )
        assertEquals(
            listOf(
                "item: VideoItem", "onClick: () -> Unit", "onMore: () -> Unit", "modifier: Modifier = Modifier", "watchedFraction: Float? = null",
                "width: Dp = VideoMetrics.SHELF_CARD_W_DP.dp", "downloaded: Boolean = false",
            ),
            params("VideoShelfCard", "VideoShelfCard"),
        )
        assertEquals(
            listOf(
                "title: String", "items: List<VideoItem>", "onItemClick: (VideoItem) -> Unit", "onItemMore: (VideoItem) -> Unit",
                "modifier: Modifier = Modifier", "watched: (String) -> Float? = { null }", "cardWidth: Dp = VideoMetrics.SHELF_CARD_W_DP.dp",
                "downloaded: (String) -> Boolean = { false }",
            ),
            params("VideoShelf", "VideoShelf"),
        )
        assertEquals(
            listOf(
                "item: VideoItem", "onClick: () -> Unit", "onMore: () -> Unit", "modifier: Modifier = Modifier", "watchedFraction: Float? = null",
                "desktop: Boolean = false", "trailing: (@Composable RowScope.() -> Unit)? = null", "title: String = item.title",
                "downloaded: Boolean = false",
            ),
            params("VideoRow", "VideoRow"),
        )
    }

    @Test fun theRowsStripChipsMetaLineAndSheetKeepTheBriefsParameters() {
        assertEquals(listOf("item: PlaylistItem", "onClick: () -> Unit", "modifier: Modifier = Modifier", "desktop: Boolean = false"), params("PlaylistRow", "PlaylistRow"))   // Y-34: desktop
        assertEquals(
            listOf("item: ChannelItem", "subscribed: Boolean", "onClick: () -> Unit", "onSubscribe: () -> Unit", "modifier: Modifier = Modifier"),
            params("ChannelStrip", "ChannelRow"),
        )
        assertEquals(
            listOf("channels: List<Subscription>", "selectedId: String?", "onSelect: (String?) -> Unit", "modifier: Modifier = Modifier", "onViewAll: (() -> Unit)? = null"),
            params("ChannelStrip", "ChannelStrip"),
        )
        // the chips take one more, last: the Shorts chip of Home stands first in the same scrolling row
        assertEquals(
            listOf("labels: List<String>", "selected: Int", "onSelect: (Int) -> Unit", "modifier: Modifier = Modifier", "leading: (@Composable () -> Unit)? = null", "trailing: (@Composable () -> Unit)? = null"),
            params("VideoChips", "VideoChips"),
        )
        assertEquals(listOf("item: VideoItem", "nowMs: Long", "showChannel: Boolean = true"), params("MetaLine", "MetaLine"))
        assertTrue(Regex("""@Composable\s+fun MetaLine\([^)]*\): String \{""").containsMatchIn(code("MetaLine")), "MetaLine returns the line as a String")
        assertEquals(
            listOf(
                "item: VideoItem", "context: VideoSheetContext", "onAction: (VideoAction) -> Unit", "onDismiss: () -> Unit",
                "downloaded: Boolean = false",
                "extraActions: (@Composable () -> Unit)? = null",
            ),
            params("VideoActionsSheet", "VideoActionsSheet"),
        )
    }

    @Test fun theGridHelperIsAnExtensionOfTheLazyGridScopeAndTheSpecComesFromTheMetrics() {
        val src = code("VideoGrid")
        assertTrue("fun LazyGridScope.videoGridItems(" in src)
        assertEquals(listOf("items: List<VideoItem>", "onItemClick: (VideoItem) -> Unit", "onItemMore: (VideoItem) -> Unit"), params("VideoGrid", "videoGridItems").take(3))
        assertTrue("data class VideoGridSpec(val columns: Int, val cardWidth: Dp)" in src)
        assertTrue("VideoMetrics.gridColumns(widthDp)" in src && "VideoMetrics.gridCardWidthDp(widthDp).dp" in src)
        assertTrue("VideoMetrics.GRID_COL_GAP_DP.dp" in src && "VideoMetrics.GRID_ROW_GAP_DP.dp" in src, "16 dp between columns, 28 between rows")
    }

    @Test fun theSheetsActionsAndContextsAreTheBriefs() {
        val src = block("VideoActionsSheet")
        assertTrue("enum class VideoSheetContext {" in src)
        assertEquals(listOf("DEFAULT", "HISTORY", "YOUR_PLAYLIST"), VideoSheetContext.entries.map { it.name })
        val declared = Regex("""data object (\w+) : VideoAction""").findAll(src).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("Play", "AddToQueue", "PlayAsSong", "SaveToPlaylist", "WatchLater", "Download", "Share", "OpenChannel", "RemoveFromHistory", "RemoveFromPlaylist"),
            declared,
        )
        assertTrue("sealed interface VideoAction {" in src)
    }

    // ---- The downloaded badge (step 3b) -------------------------------------------------------------------------------------

    @Test fun downloadedIsTheLastParameterAndDefaultsToFalseOnEveryBlockThatDrawsAThumbnail() {
        for ((file, function) in listOf("VideoShelfCard" to "VideoShelfCard", "VideoRow" to "VideoRow")) {
            assertEquals("downloaded: Boolean = false", params(file, function).last(), "$function")
        }
        assertTrue("downloaded: Boolean = false" in params("VideoCard", "VideoCard"), "the feed card's")
        assertEquals("downloaded: (String) -> Boolean = { false }", params("VideoShelf", "VideoShelf").last())
        assertEquals("downloaded: (String) -> Boolean = { false }", params("VideoGrid", "videoGridItems").first { it.startsWith("downloaded") })
    }

    @Test fun aDownloadedVideoSaysSoInItsWords() {
        val meta = code("MetaLine")
        assertTrue("downloaded: Boolean = false" in meta && "stringResource(Res.string.video_dl_done_cd)" in meta, "\"Downloaded\" in the meta line")
        assertFalse("VideoDownloadedBadge" in code("ThumbnailBox"), "never a badge on the picture")
    }

    @Test fun everyBlockThatDrawsAThumbnailPassesDownloadedOn() {
        for (file in listOf("VideoCard", "VideoShelfCard", "VideoRow")) assertTrue("downloaded = downloaded" in code(file), "$file passes downloaded to the thumbnail")
        assertTrue("downloaded = downloaded(item.id)" in code("VideoShelf") && "downloaded = downloaded(item.id)" in code("VideoGrid"))
    }

    // ---- Where the numbers come from ----------------------------------------------------------------------------------------

    @Test fun thePictureReadsItsShapeFromVideoMetrics() {
        val src = code("ThumbnailBox")
        assertTrue("VideoMetrics.thumbHeightDp(widthDp.value)" in src && "VideoMetrics.thumbRadiusDp(minOf(size.width, size.height) / density.density)" in src)
        assertTrue("RowPictureShape = RoundedCornerShape(16.dp)" in src, "a row's picture: Orbit's 16")
    }

    @Test fun theCardsRowsShelvesAndTheStripAreTheOrbitKitsPieces() {
        val card = code("VideoCard")
        assertTrue("OrbitVideoCard(" in card && "CARD_AVATAR = 32.dp" in card && "padding(horizontal = CosmoSpacing.margin)" in card, "the kit's video card, in the page's margin")
        assertTrue("CardMetaLine(item, rememberNowMs(), downloaded = downloaded)" in card && "progress = watchedFraction" in card)
        val shelfCard = code("VideoShelfCard")
        assertTrue("VideoCardBody(" in shelfCard && "avatar = false" in shelfCard)
        val shelf = code("VideoShelf")
        assertTrue("OrbitShelfRow(CosmoSpacing.margin, 18.dp)" in shelf && "OrbitSectionHeader(title" in shelf)
        val row = code("VideoRow")
        for (name in listOf("ROW_THUMB_W_DP", "DESKTOP_ROW_THUMB_W_DP")) assertTrue("VideoMetrics.$name" in row, "the row reads $name")
        assertTrue("ROW_RADIUS = 22.dp" in row && ".background(look.row)" in row, "Orbit rule 8: radius 22, the ink at 5 %")
        assertTrue("OrbitRoundButton(CosmoIcons.MoreVertical" in row)
        assertEquals(128f, VideoMetrics.ROW_THUMB_W_DP, "128 x 72")
        val strip = code("ChannelStrip")
        assertTrue("VideoMetrics.STRIP_AVATAR_DP" in strip && "VideoMetrics.STRIP_ITEM_W_DP" in strip)
        assertTrue("PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)" in strip, "padding 8 16 4")
        assertTrue("private val STRIP_GAP = 12.dp" in strip && "type.forText(type.caption, name)" in strip)
        assertTrue("private val RING_WIDTH = 2.dp" in strip && "colors.live(CoverField.TEXT_PRIMARY)" in strip, "a 2 dp neutral ring: never the accent")
        val chips = code("VideoChips")
        assertTrue("OrbitChipRow(CosmoSpacing.margin, modifier)" in chips && "OrbitSelectChip(label" in chips)
    }

    @Test fun theSheetsHeadIsA96By54ThumbnailWithRadius12AndRowsOf54() {
        val src = code("VideoActionsSheet")
        assertTrue("HEAD_THUMB_W = 96.dp" in src && "HEAD_THUMB_H = 54.dp" in src && "RoundedCornerShape(CosmoRadii.rSm)" in src)
        assertTrue("SurfaceSheetSwitch(" in src && "CosmoActionRow(" in src, "the sheet is part two's surface with part two's rows (54 dp)")
        assertTrue("maxLines = 2" in src, "the title takes two lines")
        assertTrue(Regex("""onAction\(action\)\s+onDismiss\(\)""").containsMatchIn(src), "a row says what was tapped, then the sheet closes")
        assertTrue("skipPartiallyExpanded = true" in src)
    }

    @Test fun nothingIsDrawnOnAPicture() {
        // Orbit rule 9: the length, Live, the watched line, the downloaded mark and a playlist's count are said beside the picture
        val src = code("ThumbnailBox")
        for (gone in listOf("TimeBadge", "CountStrip", "WatchedBar", "HoverPlay", "VideoDownloadedBadge", "COUNT_STRIP")) assertFalse(gone in src, "no $gone on the picture")
        assertTrue("internal fun ThumbnailFrame(url: String?, modifier: Modifier, shape: Shape)" in src)
        assertTrue("playlistMeta" !in code("PlaylistRow") && "pluralStringResource(Res.plurals.video_count_videos, quantity, quantity)" in code("PlaylistRow"), "the count is in the row's words")
        assertTrue("val length = item.durationSec" in code("MetaLine"), "the length is in the card's meta line")
    }

    // ---- Rules of the whole directory ---------------------------------------------------------------------------------------

    /** The text before the `{` of every block that encloses [index], innermost first. */
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
    private val topLevelFun = Regex("""(?m)^(?:(?:internal|private|public)\s+)?fun\s+(?:<[^>]*>\s*)?([\w.<>?]+\.)?\w+\s*\(""")

    private fun inDrawScopeFunction(code: String, index: Int): Boolean {
        val header = topLevelFun.findAll(code).lastOrNull { it.range.first <= index } ?: return false
        return header.groupValues[1] == "DrawScope."
    }

    /** The text inside the parentheses that open at [open] in [code]. */
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
                assertTrue(inDraw || inDrawScopeFunction(src, at), "$file: live( at line ${src.take(at).count { it == '\n' } + 1} is not in a draw lambda")
            }
        }
        assertTrue(seen >= 4, "the check found the blocks' live reads (found $seen)")
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

    @Test fun everyClickableHasARole() {
        var seen = 0
        for (file in all) {
            val src = code(file)
            for (match in Regex("""\b(clickable|combinedClickable|selectable|toggleable)\(""").findAll(src)) {
                seen++
                val args = argumentsOf(src, match.range.last)
                assertTrue("role =" in args, "$file: ${match.value} has no role")
            }
            for (match in Regex("""\blongPressMenuClickable\(""").findAll(src)) {
                seen++
                val before = src.substring(maxOf(0, match.range.first - 400), match.range.first)
                assertTrue("role = Role.Button" in before, "$file: the held clickable is not preceded by a role")
            }
        }
        assertTrue(seen >= 2, "found the clickables (found $seen)")
    }

    @Test fun everyCardAndRowGoesThroughTheOneClickableHelper() {
        val helper = code("VideoClickable")
        assertTrue("longPressMenuClickable(onClick = onClick, onLongClick = onMore)" in helper && ".contextClick(onMore)" in helper, "the hold and the right click both open the menu")
        assertTrue(".semantics(mergeDescendants = true) { role = Role.Button }" in helper, "one button for assistive technology")
        assertTrue(".cosmoFocusRing(interaction, radius)" in helper && "FocusInteraction.Focus()" in helper, "keyboard focus draws the ring")
        assertTrue(".videoClickable(" in code("VideoRow"), "the row uses the helper")
        // the cards are the kit's video card, whose button takes the hold and the right click
        assertTrue("onLongClick = onMore" in code("VideoCard") && "VideoCardBody(" in code("VideoShelfCard"))
        assertFalse(".clickable(" in code("VideoShelfCard") + code("VideoRow") + code("PlaylistRow"), "no bare clickable on a card or a row")
        assertTrue(".clickable(role = Role.Button, onClick = onAvatarClick)" in code("VideoCard"), "only the channel's disc, which opens the channel")
        // the card has no visible more button: a screen reader gets the action instead
        assertTrue("customActions = listOf(CustomAccessibilityAction(more) { onMore(); true })" in code("VideoCard"))
        // the channel row is part two's row with a tonal small button, and a 52 dp circle
        val channel = code("ChannelStrip")
        assertTrue("CosmoListRow(" in channel && "CosmoRowLeading.Cover(item.avatarUrl, circle = true)" in channel)
        assertTrue("SubscribePill(subscribed = subscribed, onClick = onSubscribe, tonal = true)" in channel, "in a list of channels Subscribe is tonal: the page has no one main action")
    }

    @Test fun everyLazyListOrGridHasKeys() {
        var seen = 0
        for (file in all) {
            val src = code(file)
            for (match in Regex("""\b(items|itemsIndexed|item)\(""").findAll(src)) {
                // a call, not the property `items`: the character before it is no dot of a longer name
                if (match.range.first > 0 && (src[match.range.first - 1].isLetterOrDigit() || src[match.range.first - 1] == '_')) continue
                seen++
                assertTrue("key =" in argumentsOf(src, match.range.last), "$file: ${match.value} has no key")
            }
        }
        assertTrue(seen >= 4, "found the lazy items (found $seen)")
        assertTrue("remember(items) { uniqueKeys(items) }" in code("VideoShelf"), "the shelf's keys do not repeat")
        assertTrue("val keys = uniqueKeys(items)" in code("VideoGrid"), "the grid's keys do not repeat")
    }

    @Test fun noTextShownToPeopleIsALiteral() {
        for (file in all) {
            val src = code(file)
            assertFalse(Regex("""\bText\(\s*(text\s*=\s*)?"""").containsMatchIn(src), "$file: a Text takes a literal")
            assertFalse(Regex("""\b(contentDescription|label|text|title|subtitle)\s*=\s*"""").containsMatchIn(src), "$file: a shown value is a literal")
            for (match in Regex("""\bText\(""").findAll(src)) {
                val args = argumentsOf(src, match.range.last)
                assertTrue(Regex("""text\s*=\s*\w""").containsMatchIn(args) || Regex("""^\s*\w""").containsMatchIn(args), "$file: a Text without a text")
            }
        }
        // the words are the module's resources
        for (file in listOf("VideoCard", "VideoActionsSheet", "ChannelStrip", "MetaLine")) assertTrue("stringResource(" in code(file) || "pluralStringResource(" in code(file), file)
    }

    @Test fun noColourIsPickedByHandAndWhiteAndBlackStayOnThePicture() {
        for (file in all) {
            val src = code(file)
            assertFalse(Regex("""Color\(0x""").containsMatchIn(src), "$file picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b""").containsMatchIn(src), "$file uses a named colour")
            if (file != "ThumbnailBox") assertFalse(Regex("""Color\.(White|Black)\b""").containsMatchIn(src), "$file uses white or black away from the picture")
        }
    }

    @Test fun nothingNamesTheSourceOrTheExtractorAndNothingTouchesAnAccountOrThePlayersPosition() {
        for (file in all) {
            val src = block(file)
            val code = VideoSourceContract.code(src)
            assertFalse("org.schabi" in code, "$file imports the extractor")
            assertFalse(Regex("""(?i)youtube|\bYT\b|cookie|CookieJar""").containsMatchIn(src), "$file names the source or touches cookies")
            assertFalse("positionMs()" in code, "$file reads the player's position")
            assertFalse("LocalVideoGraph" in code, "$file reaches for the graph: the blocks take what they show")
        }
    }

    @Test fun theBlocksImportOnlyPartTwosBlocksAndTheTwoClickModifiersFromMusic() {
        for (file in all) {
            val imports = Regex("""(?m)^import (com\.cosmotune\.music\.[\w.]+)""").findAll(block(file)).map { it.groupValues[1] }.toSet()
            val allowed = setOf(
                "com.cosmotune.music.ui.components.longPressMenuClickable", "com.cosmotune.music.ui.components.contextClick",
                "com.cosmotune.music.ui.components.LocalShelfRowChrome",
            )
            assertTrue(allowed.containsAll(imports), "$file imports from Music: ${imports - allowed}")
        }
    }

    // ---- The pieces of logic, run -------------------------------------------------------------------------------------------

    private val video: VideoItem = FakeVideoSource.sampleItem("fakevideo01")!!

    @Test fun aVideosSheetOffersTheActionsOfSpec512InOrder() {
        val common = listOf(
            VideoAction.Play, VideoAction.AddToQueue, VideoAction.PlayAsSong, VideoAction.SaveToPlaylist, VideoAction.WatchLater,
            VideoAction.Download, VideoAction.Share, VideoAction.OpenChannel,
        )
        assertEquals(common, videoActionsFor(video, VideoSheetContext.DEFAULT))
        assertEquals(common + VideoAction.RemoveFromHistory, videoActionsFor(video, VideoSheetContext.HISTORY))
        assertEquals(common + VideoAction.RemoveFromPlaylist, videoActionsFor(video, VideoSheetContext.YOUR_PLAYLIST))
    }

    @Test fun aLiveStreamAndAPremiereHaveNoPlayAsASongAndNoDownload() {
        for (item in listOf(video.copy(isLive = true), video.copy(isUpcoming = true))) {
            val actions = videoActionsFor(item, VideoSheetContext.DEFAULT)
            assertFalse(VideoAction.PlayAsSong in actions || VideoAction.Download in actions, "$item")
            assertTrue(VideoAction.Play in actions && VideoAction.SaveToPlaylist in actions && VideoAction.Share in actions)
        }
    }

    @Test fun anItemWithNoChannelHasNoChannelToOpen() {
        assertFalse(VideoAction.OpenChannel in videoActionsFor(video.copy(channelId = null), VideoSheetContext.DEFAULT))
        assertTrue(VideoAction.OpenChannel in videoActionsFor(video, VideoSheetContext.DEFAULT))
    }

    @Test fun aShortGetsTheSameSheet() {
        val short = FakeVideoSource.shorts.first()
        assertEquals(videoActionsFor(video, VideoSheetContext.DEFAULT), videoActionsFor(short, VideoSheetContext.DEFAULT))
    }

    @Test fun theKeysOfAListAreTheVideoIdsAndARepeatGetsASuffix() {
        val a = video
        val b = FakeVideoSource.sampleItem("fakevideo02")!!
        assertEquals(listOf(a.id, b.id), uniqueKeys(listOf(a, b)))
        assertEquals(listOf(a.id, b.id, "${a.id}#1", "${a.id}#2"), uniqueKeys(listOf(a, b, a, a)))
        val keys = uniqueKeys(listOf(a, a, b, b, a))
        assertEquals(keys.size, keys.toSet().size, "no two items share a key")
        assertEquals(emptyList(), uniqueKeys(emptyList()))
    }

    @Test fun theGridSpecIsTheMetricsOwn() {
        for (width in listOf(840f, 980f, 1280f, 1600f, 1920f, 2560f)) {
            val spec = VideoGridSpec.of(width)
            assertEquals(VideoMetrics.gridColumns(width), spec.columns, "$width")
            assertEquals(VideoMetrics.gridCardWidthDp(width), spec.cardWidth.value, 0.001f, "$width")
            assertTrue(spec.columns in 2..5)
        }
        assertEquals(4, VideoGridSpec.of(1280f).columns)
        assertEquals(294f, VideoGridSpec.of(1280f).cardWidth.value, 0.001f)
        assertEquals(16f, VideoGridSpec.ColumnGap.value)
        assertEquals(28f, VideoGridSpec.RowGap.value)
    }
}
