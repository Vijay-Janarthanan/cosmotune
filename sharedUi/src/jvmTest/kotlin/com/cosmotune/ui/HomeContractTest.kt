package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What cannot be seen without a window, pinned by reading the source (there is no Compose UI test harness here and none is to be
 * added): the seam of Home's switch, the two call sites in the roots, the load-more rule copied from the old screen, the draw-phase
 * rule for the live palette, the keys of the list, and the numbers of the hero and the mood row.
 */
class HomeContractTest {
    private val home = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/home"
    private val oldScreenPath = "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt"
    private val phoneRoot = "app/src/main/java/com/music/bitchord/MainActivity.kt"
    private val desktopRoot = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt"

    private fun file(name: String): String = SourceContract.read("$home/$name")
    private fun code(name: String): String = SourceContract.code(file(name))

    private val oldList get() = SourceContract.parameters(SourceContract.read(oldScreenPath), "HomeScreen")
    private val browseParameter = "browse: CosmoBrowse? = null"

    // ---- the seam ---------------------------------------------------------------------------------------------------------------

    @Test fun theSwitchTakesHomeScreensParametersNameForNameAndTypeForTypeAndThenBrowse() {
        val old = oldList
        assertTrue(old.size >= 19, "the old screen's list was read (${old.size})")
        assertEquals(old + browseParameter, SourceContract.parameters(file("HomeSwitch.kt"), "HomeSwitch"))
        assertEquals("browse", SourceContract.names(SourceContract.parameters(file("HomeSwitch.kt"), "HomeSwitch")).last())
    }

    @Test fun theNewScreenTakesTheSameList() {
        assertEquals(oldList + browseParameter, SourceContract.parameters(file("CosmoHome.kt"), "CosmoHome"))
    }

    @Test fun bothBranchesOfTheSwitchPassEveryParameterByNameAndTheOldOneDoesNotPassBrowse() {
        val src = file("HomeSwitch.kt")
        val names = SourceContract.names(SourceContract.parameters(src, "HomeSwitch"))
        val statement = SourceContract.firstStatement(SourceContract.functionBody(src, "HomeSwitch"))
        assertTrue(statement.startsWith("if (LocalCosmoShell.current != null) {"), "the switch is its one statement: $statement")
        assertTrue(statement.endsWith("}"), "and the whole if/else is read, to its closing brace")
        val newBranch = statement.substringBefore("} else {")
        val oldBranch = statement.substringAfter("} else {")
        assertTrue(newBranch.contains("CosmoHome("), "look on: the new screen")
        assertTrue(oldBranch.contains("HomeScreen("), "look off: the old screen")
        SourceContract.assertPassesEveryParameter(newBranch, names)
        SourceContract.assertPassesEveryParameter(oldBranch, names - "browse")
        assertFalse(Regex("""\bbrowse\b""").containsMatchIn(oldBranch), "the old screen has no browse")
    }

    // ---- the call sites -----------------------------------------------------------------------------------------------------------

    /** The text of the call whose name starts at [start]: from there to the parenthesis that closes its argument list. */
    private fun callFrom(src: String, start: Int): String {
        val open = src.indexOf('(', start)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return src.substring(start, i + 1)
            }
        }
        error("the call at $start is never closed")
    }

    @Test fun thePhoneCallsTheSwitchWithItsBrowseArgumentAfterTheTitle() {
        val src = SourceContract.code(SourceContract.read(phoneRoot))
        assertFalse(Regex("""\bHomeScreen\(""").containsMatchIn(src), "the root no longer calls the old screen directly")
        val at = src.indexOf("TAB_HOME -> com.cosmotune.ui.home.HomeSwitch(")
        assertTrue(at >= 0, "the Home tab calls the switch, fully qualified")
        assertEquals(at, src.lastIndexOf("TAB_HOME -> com.cosmotune.ui.home.HomeSwitch("), "once")
        val call = callFrom(src, at)
        val browse = "browse = com.cosmotune.ui.browse.CosmoBrowse(exploreState, onOpen = { viewModel.openMoodGenre(it); selectedTab = TAB_EXPLORE }, onRetry = viewModel::loadExplore),"
        assertTrue(browse in call, "the mood page lives under the Explore tab (decision E4)")
        val title = call.indexOf("title = stringResource(R.string.listen_now),")
        assertTrue(title in 0 until call.indexOf(browse), "browse comes right after the title, well away from the arguments that end the call")
        assertTrue(call.indexOf(browse) < call.indexOf("recentlyPlayedLoading ="), "and before recentlyPlayedLoading, which sits two lines from the mood page's site")
    }

    @Test fun theDesktopCallsTheSwitchWithItsBrowseArgumentAfterTheTitleAndOpensTheMoodThroughExplore() {
        val src = SourceContract.code(SourceContract.read(desktopRoot))
        assertFalse(Regex("""\bHomeScreen\(""").containsMatchIn(src), "the root no longer calls the old screen directly")
        val at = src.indexOf("destination == DesktopDestination.LISTEN_NOW -> com.cosmotune.ui.home.HomeSwitch(")
        assertTrue(at >= 0, "the Listen now page calls the switch, fully qualified")
        val call = callFrom(src, at)
        val browse = "browse = com.cosmotune.ui.browse.CosmoBrowse(exploreState, onOpen = { selectDestination(DesktopDestination.EXPLORE); openMoodGenre(it) }, onRetry = { exploreReloads++ }),"
        assertTrue(browse in call, "selectDestination clears the chosen mood, so it comes before openMoodGenre")
        assertTrue(call.indexOf("title = null,") in 0 until call.indexOf(browse))
        assertTrue(call.indexOf(browse) < call.indexOf("leadHero = false,"), "well before leadHero, which sits two lines from the mood page's site")
    }

    @Test fun theDesktopLoadsExploreWhileOnListenNowWithTheNewLookOnlyAndOnlyWhenItIsStillLoading() {
        val src = SourceContract.code(SourceContract.read(desktopRoot))
        val at = src.indexOf("val homeWantsExplore =")
        assertTrue(at >= 0, "the extra loader exists")
        val block = src.substring(at, src.indexOf("LaunchedEffect(exploreState)", at))
        assertTrue("destination == DesktopDestination.LISTEN_NOW" in block, "only on Listen now")
        assertTrue("rememberCosmoLookSettings().newLook" in block, "only while the new look is on: with it off the app loads what it loaded before")
        assertTrue("if (homeWantsExplore && exploreState is UiState.Loading) loadExplore()" in block, "once, while Explore has not arrived")
        // the original loader is as it was
        assertTrue(
            Regex("""LaunchedEffect\(destination, exploreReloads\) \{\s*if \(destination == DesktopDestination\.EXPLORE &&\s*\(exploreState is UiState\.Loading \|\| exploreReloads > 0\)\s*\) \{\s*loadExplore\(\)""").containsMatchIn(src),
            "the loader of the Explore page is untouched",
        )
    }

    // ---- the load-more rule ---------------------------------------------------------------------------------------------------------

    private val loadMoreCondition = "if (!loadingMore && total > 0 && lastVisible >= total - 3) loadMore()"

    @Test fun theLoadMoreConditionIsTheOldScreensAndIsInCosmoHome() {
        assertTrue(loadMoreCondition in code("CosmoHome.kt"), "CosmoHome checks the tail the way HomeScreen does")
        assertTrue(loadMoreCondition in SourceContract.code(SourceContract.read(oldScreenPath)), "and the old screen still says it that way")
        val src = code("CosmoHome.kt")
        assertTrue("LaunchedEffect(listState, loadingMore)" in src, "restarted on loadingMore, so the next page is asked for when one settles")
        assertTrue("val layout = listState.layoutInfo" in src && "(layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount" in src, "the last visible index and the total are read in a snapshot flow")
        assertTrue(file("CosmoHome.kt").contains("HomeScreen's load-more effect"), "and the file names the source it was copied from")
        assertTrue("if (onLoadMore != null && state is UiState.Success)" in src, "only for a loaded feed with a loader")
    }

    // ---- the draw-phase rule --------------------------------------------------------------------------------------------------------

    /** The text before the `{` of every block that encloses [index], innermost first. */
    private fun enclosingHeads(text: String, index: Int): List<String> {
        val heads = ArrayList<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (text[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else heads += text.substring(maxOf(0, i - 120), i)
            }
            i--
        }
        return heads
    }

    // a draw lambda, a layer block (the shadow's colours are read in one), or the colour lambdas the shadow takes
    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer|Canvas)\s*(\([^)]*\))?\s*$|\b(spot|ambient)\s*=\s*$""")

    @Test fun theLivePaletteIsReadOnlyWhileDrawing() {
        for (name in listOf("HomeSwitch.kt", "CosmoHome.kt", "HomeHero.kt", "HomeBento.kt")) {
            val text = code(name)
            var from = 0
            while (true) {
                val at = text.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '_')) continue
                assertTrue(
                    enclosingHeads(text, at).any { drawHead.containsMatchIn(it) },
                    "$name: live( at line ${text.take(at).count { it == '\n' } + 1} is not in a draw lambda or a layer block",
                )
            }
        }
        // and it is read somewhere: the hero's scrim and shadow follow the palette's fade
        assertTrue("colors.live(CoverField.SCRIM)" in code("HomeHero.kt") && "colors.live(CoverField.SHADOW)" in code("HomeHero.kt"))
    }

    @Test fun noBrushIsRememberedNoColourIsPickedByHandAndOnlyWhiteAndBlackAreNamed() {
        for (name in listOf("HomeSwitch.kt", "CosmoHome.kt", "HomeHero.kt", "HomeBento.kt")) {
            val text = code(name)
            assertFalse(Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\(|cssGradient)""").containsMatchIn(text), "$name remembers a brush or a live colour")
            assertFalse(Regex("""Color\(0x""").containsMatchIn(text), "$name picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b""").containsMatchIn(text), "$name names a colour")
        }
        // white over the media scrim (the title and its line) and the shadow's black: nowhere else
        val hero = code("HomeHero.kt")
        assertEquals(2, Regex("""color = Color\.White""").findAll(hero).count(), "the title and its line are white over the scrim")
        assertEquals(1, Regex("""Color\.Black""").findAll(hero).count(), "the shadow's ambient is the one black")
        for (name in listOf("HomeSwitch.kt", "CosmoHome.kt", "HomeBento.kt")) {
            assertFalse(Regex("""Color\.(White|Black)""").containsMatchIn(code(name)), "$name uses no fixed colour")
        }
    }

    // ---- the list ---------------------------------------------------------------------------------------------------------------------

    @Test fun everyItemOfTheListHasAKey() {
        val text = code("CosmoHome.kt")
        val calls = Regex("""\b(item|items|itemsIndexed)\(""").findAll(text).toList()
        assertTrue(calls.size >= 8, "the list's items were found (${calls.size})")
        for (call in calls) {
            val args = callFrom(text, call.range.first)
            assertTrue("key =" in args, "$call at line ${text.take(call.range.first).count { it == '\n' } + 1} has a key: ${args.take(60)}")
        }
        assertFalse(Regex("""\bitem\s*\{""").containsMatchIn(text), "no item without its key")
        assertTrue("item(key = shelf.title + index)" in text, "the shelves keep the old screen's keys")
    }

    // ---- the page -----------------------------------------------------------------------------------------------------------------------

    @Test fun theHeadingIsTheGreetingWithTheTitleAsOverlineAndKeepsTheOldLift() {
        val text = code("CosmoHome.kt")
        assertTrue("title = rememberGreeting()" in text && "overline = overline" in text && "large = wide" in text)
        assertFalse("subtitle =" in text.substringAfter("private fun HomeHeader").substringBefore("WIDE_HEAD_TOP"), "no subtitle: the mood sentence needs the recommender")
        for (key in listOf("cosmo_home_morning", "cosmo_home_afternoon", "cosmo_home_evening", "cosmo_home_late")) assertTrue(key in text, key)
        assertTrue("java.time.DayOfWeek.of(now.dayOfWeek).getDisplayName(java.time.format.TextStyle.FULL, locale)" in text, "the weekday is in the device's language")
        assertTrue("private val HOME_TITLE_LIFT = 28.dp" in text)
        assertTrue("top = (contentPadding.calculateTopPadding() - HOME_TITLE_LIFT).coerceAtLeast(0.dp)" in text, "with a title the feed keeps its 28 dp lift")
    }

    @Test fun theMoodRowIsSplitFromExploreSkeletonsWhileLoadingAndHiddenOtherwise() {
        val text = code("CosmoHome.kt")
        assertTrue("BrowseSplit.moods(" in text, "the chips are the first section of Explore")
        assertTrue("CosmoMoodChip(label = mood.title, selected = false, onClick = { onOpen(mood) })" in text, "none selected; a tap opens the mood")
        assertTrue("val showMoods = browse != null && (moodsLoading || moods.isNotEmpty())" in text, "an error, an empty list or no browse hides the row")
        assertTrue("private const val MOOD_SKELETONS = 4" in text && "MOOD_SKELETON_WIDTH = 96.dp" in text && "MOOD_SKELETON_RADIUS = 13.dp" in text, "four 44 x 96 skeletons of radius 13")
        assertTrue("height(BlockMetrics.MOOD_CHIP_HEIGHT_DP.dp)" in text && "const val MOOD_CHIP_HEIGHT_DP = 44f" in SourceContract.read("shared/src/commonMain/kotlin/com/cosmotune/design/BlockMetrics.kt") && "ShimmerBox(" in text)
        assertTrue("PaddingValues(start = inset, top = 16.dp, end = inset, bottom = 4.dp)" in text, "padding 16 16 4 (the page's margin at the sides)")
        assertTrue("Arrangement.spacedBy(8.dp)" in text, "gap 8")
        assertTrue("stringResource(Res.string.cosmo_moods)" in text && "contentDescription = label" in text, "the row is named cosmo_moods")
    }

    @Test fun theMixTilesAndTheShelvesAreAsTheBriefSays() {
        val bento = code("HomeBento.kt")
        assertTrue("title = localizeShelfTitle(shelf.title)" in bento && "localizeShelfSubtitle(shelf.subtitle).ifBlank { HomePlan.fallbackSubtitle(shelf) }" in bento)
        assertTrue("shelf.items.take(MIX_COVERS)" in bento && "MIX_COVERS = 3" in bento, "three covers")
        assertTrue("onClick = { onOpenShelf(mix.shelfIndex) }" in bento, "a tap scrolls to the shelf")
        assertTrue("held(shelf.items.first())" in bento, "a hold opens the first item's menu")
        val text = code("CosmoHome.kt")
        assertTrue("animateScrollToItem(target)" in text, "the list scrolls to the shelf")
        assertTrue("HomePlan.firstShelfItemIndex(" in text, "the target is counted from the items that precede the shelves")
        assertTrue("CosmoShelf(" in text && "onItemClick = { onItemClick(it, shelf.title) }" in text && "onItemLongPress = onItemLongPress" in text, "every shelf is a CosmoShelf")
        assertFalse("leadFirst" in text, "lead 168, then 112: the shelf's defaults")
        // Recents: pages of four list rows, cover leading, the row's width less 48 dp
        assertTrue("HomePlan.isRecents(state.data, index)" in text && "RECENT_TRACKS_PER_PAGE = 4" in text && "RECENTS_PAGE_INSET = 48.dp" in text && "CosmoRowLeading.Cover(item.thumbnailUrl)" in text)
        // states, as the old screen chooses them
        assertTrue("recentlyPlayedSkeleton(" in text && "feedSkeleton(firstIsHero = false)" in text && "feedSkeleton(firstIsHero = leadHero)" in text && "feedMoreSkeleton()" in text)
        assertTrue("MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)" in text)
        assertTrue("SignInBanner(onSignIn = signIn," in text && "val showBanner = !signedIn && signIn != null" in text)
        assertTrue("PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState, modifier = modifier)" in text)
    }

    @Test fun theHeroHasTheBoardsNumbersAndIsAButtonWithAFloatingPlayButton() {
        val text = code("HomeHero.kt")
        // phone: radius 28, tag 12 in, text 16 / 14 / 84, play 56 at 14; wide: 32, 18, 26 / 26 / 120, 64 at 24
        assertTrue("HeroMetrics(radius = 28.dp, tagInset = 12.dp, textStart = 16.dp, textBottom = 14.dp, textEnd = 84.dp, playSize = 56.dp, playInset = 14.dp, displayTitle = false)" in text)
        assertTrue("HeroMetrics(radius = 32.dp, tagInset = 18.dp, textStart = 26.dp, textBottom = 26.dp, textEnd = 120.dp, playSize = 64.dp, playInset = 24.dp, displayTitle = true)" in text)
        assertTrue("SCRIM_START = 0.3f" in text && "SCRIM_ALPHA = 0.82f" in text, "transparent at 30 %, the scrim at 82 % at the bottom")
        assertTrue("HERO_ELEVATION = 16.dp" in text && "HERO_AMBIENT_ALPHA = 0.30f" in text, "the shadow: black 30 % and the cover's colour as a 16 dp lift")
        assertTrue("TAG_HEIGHT = 30.dp" in text && "SuperellipseShape(15.dp)" in text && "cosmoGlass(hazeState = null, shape = TAG_SHAPE)" in text && "fontSize = 12.5.sp" in text && "FontWeight(650)" in text, "the tag: 30 dp, radius 15, 12.5 sp 650, glass")
        assertTrue("HEADER_ART_PX" in text && "ContentScale.Crop" in text, "the artwork at the header size, cropped")
        assertTrue("type.title(TypeScale.TITLE_L)" in text && "type.display(TypeScale.DISPLAY_S)" in text)
        assertTrue("localizeCardSubtitle(item.subtitle)" in text && "fontSize = 13.5.sp" in text)
        assertTrue("loading = false" in text && "size = metrics.playSize" in text && "floating = true" in text, "the C play button")
        assertTrue("role = Role.Button" in text && "longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)" in text && "contextClick(onLongPress)" in text, "a tap plays, a hold or a right click opens the menu")
        val bento = code("HomeBento.kt")
        assertTrue("playing = isPlaying && hero.isSong(currentSong)" in bento, "the button shows pause only for the song that is playing")
        assertTrue("onItemClick(hero, heroShelf.title)" in bento, "the hero plays like a tap on the shelf's card")
    }

    @Test fun theBentoUsesTheGridsAndKeepsTheWideLayoutAtTheMarginOf28() {
        val bento = code("HomeBento.kt")
        assertTrue("Bento.gridFor(pageWidth.value)" in bento && "grid.heroWidth(pageWidth.value)" in bento && "grid.mixWidth(pageWidth.value)" in bento, "sizes come from Bento")
        val text = code("CosmoHome.kt")
        assertTrue("Bento.gridFor(pageWidth.value)" in text && "val inset = grid.marginDp.dp" in text)
    }
}
