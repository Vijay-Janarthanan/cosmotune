package com.cosmotune.video.ui

import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.ui.comments.ExpandedComments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The comments of plan task Y-23 (spec 5.9 and 5.4), read from the source (there is no Compose UI harness here): the two signatures Y-16
 * fixed, the numbers of the comment, the six-line rule and "Read more", the replies inside the panel with a back row, the lazy keyed list
 * that asks for the next page near its end, the turned-off state, the header, the sheet, the tile, and the rules of the look. The paging
 * itself is tested in `:shared` (`CommentsFeedTest`).
 */
class CommentsStructureTest {
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/comments"
    private val names = listOf("CommentsStubs", "CommentItem", "CommentsList", "RepliesList", "CommentsPanelBody")

    private fun read(name: String) = VideoSourceContract.read("$dir/$name.kt")
    private fun code(name: String) = VideoSourceContract.code(read(name))
    private fun body(file: String, function: String) = VideoSourceContract.functionBody(read(file), function)
    private fun squash(text: String) = text.replace(Regex("\\s+"), " ")

    // ---- the signatures -----------------------------------------------------------------------------------------------

    @Test fun theTwoComposablesKeepTheSignaturesY16Fixed() {
        val src = read("CommentsStubs")
        assertEquals(
            listOf("videoId: String", "sheet: Boolean", "onDismiss: () -> Unit", "modifier: Modifier = Modifier"),
            VideoSourceContract.parameters(src, "CommentsPanel"),
        )
        assertEquals(listOf("videoId: String", "onOpen: () -> Unit", "modifier: Modifier = Modifier"), VideoSourceContract.parameters(src, "CommentsPreviewTile"))
        assertTrue(Regex("""@Composable\s+fun CommentsPanel\(""").containsMatchIn(src))
        assertTrue(Regex("""@Composable\s+fun CommentsPreviewTile\(""").containsMatchIn(src))
    }

    @Test fun theFilesBriefNamedAreThere() {
        for (name in listOf("CommentItem", "CommentsList", "RepliesList")) assertTrue(read(name).isNotBlank(), "$name.kt")
    }

    // ---- the comment ------------------------------------------------------------------------------------------------------

    @Test fun theNumbersOfTheCommentAreTheSpecs() {
        assertEquals(32f, VideoMetrics.COMMENT_AVATAR_DP)
        assertEquals(14.5f, VideoMetrics.COMMENT_TEXT_SP)
        assertEquals(6, VideoMetrics.COMMENT_MAX_LINES)
        val item = code("CommentItem")
        assertTrue("ChannelAvatar(comment.authorAvatarUrl, VideoMetrics.COMMENT_AVATAR_DP.dp)" in item, "a 32 dp avatar")
        assertTrue("Spacer(Modifier.width(CosmoSpacing.gutter))" in item, "12 dp between the avatar and the words")
        assertTrue(Regex("""padding\(horizontal = CosmoSpacing\.margin, vertical = 10\.dp\)""").containsMatchIn(item), "padding 10 16")
        assertTrue("AUTHOR_SP = 13f" in item && "rememberTitleStyle(AUTHOR_SP, author)" in item, "the author: 13 sp, weight 650 (the title style)")
        assertTrue("SMALL_SP = 12.5f" in item && "color = Color(palette.textTertiary)" in item, "the age: 12.5 sp in textTertiary")
        assertTrue("fontSize = VideoMetrics.COMMENT_TEXT_SP.sp" in item && "COMMENT_LINE = 1.45f" in item, "the text: 14.5 sp, line 1.45")
        assertTrue("lineHeight = VideoMetrics.COMMENT_TEXT_SP.sp * COMMENT_LINE" in item)
        assertTrue("LIKE_ICON = 16.dp" in item && "VideoIcons.ThumbUp" in item && "Modifier.size(LIKE_ICON)" in item, "a 16 dp thumb")
        assertTrue(Regex("""rememberMetaStyle\(SMALL_SP, count\)""").containsMatchIn(item), "the like count: 12.5 sp")
        assertTrue("CosmoIcons.HeartFill" in item && "Res.string.video_hearted" in item, "the creator's heart, read out as 'Hearted by the creator'")
        assertTrue("Res.string.video_likes_cd" in item, "the likes are read out as a count")
    }

    @Test fun thePinnedPillAndTheCreatorMark() {
        val item = code("CommentItem")
        assertTrue("PILL_HEIGHT = 18.dp" in item && "PILL_SP = 11.5f" in item && "PILL_WEIGHT = 700" in item, "18 dp, 11.5 sp, weight 700")
        assertTrue("if (comment.pinned)" in item && "CommentPill(stringResource(Res.string.video_pinned), accent = true)" in item)
        assertTrue("if (comment.byCreator)" in item && "CommentPill(stringResource(Res.string.video_creator), accent = false)" in item)
        assertTrue("colors.live(CoverField.ACCENT_CONTAINER)" in item && "palette.onAccentContainer" in item, "Pinned is the accent container")
    }

    @Test fun aTextOfMoreThanSixLinesIsCutAndHasReadMore() {
        val text = squash(body("CommentItem", "CommentText"))
        assertTrue("maxLines = if (expanded) Int.MAX_VALUE else VideoMetrics.COMMENT_MAX_LINES" in text, "six lines, or all of it when open")
        assertTrue("overflow = TextOverflow.Ellipsis" in text)
        assertTrue("result.hasVisualOverflow" in text, "the cut is read from the layout")
        assertTrue("result.lineCount > VideoMetrics.COMMENT_MAX_LINES" in text, "an open text still knows it was long, so 'Show less' stays")
        val more = squash(body("CommentItem", "ReadMore"))
        assertTrue("if (!cut && !expanded) return" in more, "a short text has no button")
        assertTrue("SharedRes.string.cosmo_show_less else SharedRes.string.cosmo_read_more" in more)
        assertTrue("kind = CosmoButtonKind.TEXT" in more && "size = CosmoButtonSize.SMALL" in more)
        val item = squash(code("CommentItem"))
        assertTrue("rememberSaveable(comment.id, comment.text)" in item, "the cut is kept for a row that scrolled away and back")
        // one state for both: the text reports into it and the button reads it (two remembered states would never meet: the button stayed hidden)
        assertEquals(1, Regex("""rememberSaveable\(""").findAll(item).count(), "one 'cut' state per comment")
        assertTrue("CommentText(comment, expanded, onCut = { cut = it })" in item && "ReadMore(cut, expanded, onToggleExpanded, floating)" in item)
    }

    @Test fun theRepliesButtonIsASmallTextButtonThatOnlyAnOpenableCommentHas() {
        val item = squash(code("CommentItem"))
        assertTrue("val canOpen = onReplies != null && comment.replies != null" in item)
        assertTrue("pluralStringResource(Res.plurals.video_replies, replies, replies)" in item)
        val actions = squash(body("CommentItem", "CommentActions"))
        assertTrue("if (likes == null && !hearted && !canOpen) return" in actions, "nothing to show, no row")
        assertTrue("kind = CosmoButtonKind.TEXT" in actions && "size = CosmoButtonSize.SMALL" in actions)
        assertTrue("floating = floating" in actions, "the sheet's buttons are C")
    }

    @Test fun theOpenCommentsAreKeptByTheListNotTheRow() {
        val ids = ExpandedComments()
        assertFalse("a" in ids)
        ids.toggle("a")
        ids.toggle("b")
        assertTrue("a" in ids && "b" in ids)
        ids.toggle("a")
        assertFalse("a" in ids)
        assertTrue("b" in ids)
    }

    // ---- the list ---------------------------------------------------------------------------------------------------------

    @Test fun theListIsLazyKeyedByIdAndAsksForTheNextPageNearTheEnd() {
        val list = squash(code("CommentsList"))
        assertTrue("LazyColumn(" in list)
        assertTrue("items(state.items, key = { it.id }, contentType = { COMMENT })" in list, "keyed by the comment's id")
        assertTrue("const val LOAD_AHEAD = 4" in list)
        assertTrue("derivedStateOf" in list && "last >= info.totalItemsCount - LOAD_AHEAD" in list, "read in a derived state: scrolling recomposes nothing")
        assertTrue("LaunchedEffect(nearEnd, state.items.size, state.loadingMore, state.hasMore) { if (nearEnd && state.hasMore) onLoadMore() }" in list)
        assertTrue("if (state.loading && state.items.isEmpty())" in list, "skeletons while the first page loads")
        assertTrue("if (state.loadingMore)" in list, "a skeleton row while a later page loads")
        assertTrue("state.failure?.let" in list && "state.moreFailure?.let" in list, "a 'Try again' row when a page failed")
        assertTrue("Res.string.video_retry" in list)
        assertFalse(Regex("""\bColumn\(.*verticalScroll""").containsMatchIn(list), "no scrolling column of comments")
    }

    @Test fun theListMakesNoCallToTheSourceItself() {
        for (name in names) {
            val text = code(name)
            assertFalse(Regex("""\.(comments|replies)\(""").containsMatchIn(text), "$name: the source is asked through the feed's loaders only")
        }
        assertTrue("CachedCommentsLoader(graph.source, videoId, SharedCommentsCache)" in code("CommentsStubs"))
        assertTrue("RepliesLoader(graph.source, token)" in code("RepliesList"))
    }

    // ---- the replies ------------------------------------------------------------------------------------------------------

    @Test fun repliesOpenInsideThePanelWithABackRow() {
        val panel = squash(code("CommentsPanelBody"))
        assertTrue("Crossfade(targetState = replyTo" in panel, "the replies take the panel's place")
        assertTrue("RepliesList(open, token, sheet, onBack = { onReplyTo(null) }, onDismiss = onDismiss" in panel)
        assertTrue("VideoBackHandler(enabled = replyTo != null) { onReplyTo(null) }" in panel, "the Back key leaves the replies first")
        val stub = squash(code("CommentsStubs"))
        assertTrue("var replyTo by remember(feed) { mutableStateOf<Comment?>(null) }" in stub, "a new video closes the replies")
        assertTrue("val listState = remember(feed) { LazyListState() }" in stub && "val expanded = remember(feed) { ExpandedComments() }" in stub,
            "the comments' scroll position and open texts live above the swap")
        val replies = squash(code("RepliesList"))
        val back = squash(VideoSourceContract.functionBody(read("RepliesList"), "RepliesBackRow"))
        assertTrue("CosmoIcons.ArrowLeft" in back && "SharedRes.string.cosmo_back" in back && "onClick = onBack" in back, "the back row's arrow")
        assertTrue("pluralStringResource(Res.plurals.video_replies, replies, replies)" in back, "titled '12 replies'")
        assertTrue("if (sheet) CommentsCloseButton(onDismiss)" in back)
        assertTrue("onReplies = null" in replies, "a reply has no replies of its own")
        assertTrue("item(key = \"~parent\"" in replies, "the comment whose replies these are stands first")
        assertTrue("onLoadMore = feed::loadMore" in replies && "onRetry = feed::retry" in replies, "replies page like comments")
        assertTrue("DisposableEffect(feed)" in replies && "onDispose { feed.close() }" in replies)
    }

    // ---- turned off, failed, empty ----------------------------------------------------------------------------------------

    @Test fun turnedOffFailedAndEmptyAreStatesNotBlankPanels() {
        val content = squash(body("CommentsPanelBody", "CommentsContent"))
        assertTrue("state.disabled -> CosmoEmptyState(message = stringResource(Res.string.video_comments_off)" in content, "Comments are turned off")
        assertTrue("failure != null -> CosmoEmptyState(" in content && "actionLabel = stringResource(Res.string.video_retry)" in content && "onAction = feed::retry" in content)
        assertTrue("state.empty -> CosmoEmptyState(message = stringResource(Res.string.video_comments_empty)" in content)
        val text = squash(code("CommentsList"))
        assertTrue("CommentsFailure.NETWORK -> Res.string.video_error_network" in text && "CommentsFailure.OTHER -> Res.string.video_comments_error" in text)
    }

    // ---- the header and the sheet -----------------------------------------------------------------------------------------

    @Test fun theHeaderIsTheSpecs() {
        val header = squash(code("CommentsPanelBody"))
        assertTrue("HEADER_START = 20.dp" in header && "HEADER_TOP = 4.dp" in header && "HEADER_END = 20.dp" in header && "HEADER_BOTTOM = 8.dp" in header, "padding 4 20 8")
        val body = squash(body("CommentsPanelBody", "CommentsHeader"))
        assertTrue("type.title(TypeScale.TITLE_S)" in body && "Res.string.video_comments)" in body, "the title in title(TITLE_S)")
        assertTrue("Res.string.video_comments_count, VideoText.compact(total)" in body, "the count")
        assertTrue("rememberMetaStyle(13f, count)" in body, "13 sp")
        assertTrue("if (state.sortable) {" in body, "Top and Newest only when the source offers them")
        assertTrue("Res.string.video_comments_top" in body && "Res.string.video_comments_newest" in body && "for (sort in CommentSort.entries)" in body)
        assertTrue("floating = sheet" in body, "the sheet's chips are C, the column's B")
        assertTrue("if (sheet) CommentsCloseButton(onDismiss)" in body, "a close button on the sheet only")
        val close = squash(body("CommentsPanelBody", "CommentsCloseButton"))
        assertTrue("size = 40.dp" in close && "SharedRes.string.cosmo_close" in close, "a 40 dp close button with a name")
    }

    @Test fun theSheetIsASolidGlassCardWithTheGrabberThatTheCallerPlacesUnderThePlayer() {
        val panel = squash(code("CommentsPanelBody"))
        assertTrue("SHEET_SHAPE = SuperellipseShape(CosmoRadii.sheet)" in panel, "radius 36")
        assertTrue("SHEET_MAX_HEIGHT_SHARE = 0.85f" in panel)
        assertTrue(".padding(start = CosmoRadii.sheetInset, end = CosmoRadii.sheetInset, bottom = CosmoRadii.sheetInset)" in panel, "detached 8 dp")
        assertTrue(".cosmoGlass(hazeState = null, shape = SHEET_SHAPE)" in panel, "solid glass: no backdrop blur")
        assertTrue("CosmoSheetHandle()" in panel)
        assertTrue(".heightIn(max = windowHeight * SHEET_MAX_HEIGHT_SHARE) .fillMaxHeight()" in panel, "as high as the caller's room allows, bounded")
        assertTrue(".pointerInput(Unit) {}" in panel, "the page behind does not get the touches")
        assertTrue("onDrawBehind { if (ground != null) drawPath(ground, colors.live(CoverField.BACKGROUND_BOTTOM)) }" in panel, "the page's ground under the glass: no scrim here, so nothing shows through")
        assertTrue("translationY = pull.value" in panel && ".graphicsLayer {" in panel, "the pull is read in the layer block")
        assertTrue("if (pull.value > dismissPx || velocity > flingPx) onDismiss() else pull.animateTo(0f, CosmoMotion.move(reduced))" in panel)
        assertFalse(Regex("""\bCosmoSheetSurface\(""").containsMatchIn(panel), "part two's surface scrolls its content itself: a lazy list cannot sit in it")
    }

    @Test fun theColumnNeverGivesTheListAnUnboundedHeight() {
        val surface = squash(body("CommentsPanelBody", "CommentsSurface"))
        assertTrue("if (!sheet) { Column(modifier.fillMaxWidth().heightIn(max = windowHeight), content = content) return }" in surface)
    }

    @Test fun theCrossFadeIsAFastSpringOrAShortFadeUnderReducedMotion() {
        val panel = squash(code("CommentsPanelBody"))
        assertTrue("if (reduced) tween(Springs.REDUCED_MS) else spring(dampingRatio = Springs.EFFECTS.dampingRatio, stiffness = Springs.EFFECTS.stiffness)" in panel)
        assertTrue("val reduced = LocalReducedMotion.current" in panel)
    }

    // ---- the tile ---------------------------------------------------------------------------------------------------------

    @Test fun theTileIsSpec54() {
        val src = read("CommentsStubs")
        val tile = squash(VideoSourceContract.functionBody(src, "CommentsPreviewTile"))
        assertTrue("Res.string.video_comments" in tile, "the tile says Comments")
        assertTrue("if (preview == CommentsPreview.Off) return" in tile, "hidden when comments are off")
        assertTrue(".videoClickable(CosmoRadii.card, onOpen, onMore = null)" in tile && ".cosmoTile(shape)" in tile, "the description's tile, one button")
        assertTrue("TILE_MARGIN_V = 8.dp" in squash(code("CommentsStubs")) && "TILE_PADDING_H = 16.dp" in squash(code("CommentsStubs")) && "TILE_PADDING_V = 14.dp" in squash(code("CommentsStubs")), "margin 8 16, padding 14 16")
        assertTrue("type.title(TypeScale.TITLE_S)" in tile)
        assertTrue("CommentsPreview.Loading -> PreviewSkeleton()" in tile && "CommentsPreview.Failed -> PreviewLine(stringResource(Res.string.video_comments_error))" in tile)
        assertTrue("if (first != null) FirstComment(first) else PreviewLine(stringResource(Res.string.video_comments_empty))" in tile)
        val all = squash(code("CommentsStubs"))
        assertTrue("PREVIEW_AVATAR = 24.dp" in all && "PREVIEW_SP = 14f" in all && "PREVIEW_LINES = 2" in all, "a 24 dp avatar and two lines of 14 sp")
        assertTrue("produceState<CommentsPreview>(CommentsPreview.Loading, videoId, graph)" in all, "one load per video, cancelled when the tile leaves")
        assertTrue("CachedCommentsLoader(graph.source, videoId, SharedCommentsCache).preview()" in all, "its first page is kept for the panel")
    }

    @Test fun thePanelAsksOncePerVideoAndStopsWhenItLeaves() {
        val panel = squash(VideoSourceContract.functionBody(read("CommentsStubs"), "CommentsPanel"))
        assertTrue("val feed = remember(videoId, graph) { CommentsFeed(" in panel)
        assertTrue("DisposableEffect(feed) { feed.start() onDispose { feed.close() } }" in panel)
        assertTrue("CommentsSurface(sheet, onDismiss, modifier) {" in panel && "CommentsPanelBody(feed, sheet, onDismiss, listState, expanded, replyTo, onReplyTo = { replyTo = it })" in panel)
    }

    // ---- the strings ------------------------------------------------------------------------------------------------------

    @Test fun everyVideoKeyTheFilesUseIsDeclaredOnce() {
        val strings = VideoSourceContract.read("video/src/commonMain/composeResources/values/strings.xml")
        for (name in names) {
            for (match in Regex("""Res\.(string|plurals)\.(video_\w+)""").findAll(code(name))) {
                val tag = if (match.groupValues[1] == "string") "string" else "plurals"
                val declared = Regex("""<$tag name="${match.groupValues[2]}"""").findAll(strings).count()
                assertEquals(1, declared, "$name uses ${match.value}: declared once")
            }
        }
        assertTrue("<string name=\"video_comments_empty\">No comments yet</string>" in strings)
        assertTrue("<string name=\"video_comments_error\">Could not load the comments</string>" in strings)
    }

    // ---- the look ---------------------------------------------------------------------------------------------------------

    @Test fun theFilesPickNoColourByHandAndReadLiveColoursOnlyWhileDrawing() {
        for (name in names) {
            val text = code(name)
            assertFalse(Regex("""Color\.(Black|White)|Color\(0x""").containsMatchIn(text), "$name: colours come from the palette")
            val lines = text.lines()
            for ((i, line) in lines.withIndex().filter { "live(" in it.value }) {
                val window = lines.subList((i - 6).coerceAtLeast(0), i + 1).joinToString("\n")
                assertTrue(Regex("""drawBehind|drawWithCache|drawWithContent|Canvas|graphicsLayer""").containsMatchIn(window), "$name: live colours only while drawing: $line")
            }
            assertFalse("positionMs()" in text, "$name: no playhead")
            assertFalse(Regex("""(?i)\byoutube\b|org\.schabi""").containsMatchIn(text), "$name: no source name")
        }
    }

    @Test fun nothingIsAllocatedByTheFrameAndNoStateIsWrittenInComposition() {
        // the pull of the sheet and the near-end test are read in layer and derived-state blocks, never as composition state per frame
        val panel = code("CommentsPanelBody")
        assertFalse(Regex("""val pull by """).containsMatchIn(panel), "the pull is an Animatable read in graphicsLayer")
        assertTrue("val pull = remember { Animatable(0f) }" in panel)
    }
}
