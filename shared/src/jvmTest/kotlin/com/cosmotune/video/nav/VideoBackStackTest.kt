package com.cosmotune.video.nav

import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.shorts.ShortsOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The back stack of Video mode (plan task Y-14, step 1): one stack per tab, `pop` refuses at a root, the tab button of the
 * current tab goes to its root, the same video pushed twice replaces itself, searches replace each other, and the stack and
 * every route turn into text for the phone's `Saver` and back.
 */
class VideoBackStackTest {

    // ---- The stacks -----------------------------------------------------------------------------------------------------

    @Test
    fun startsOnTheHomeRootAndCannotPopThere() {
        val stack = VideoBackStack()
        assertEquals(VideoRoute.Home, stack.top)
        assertEquals(VideoTab.HOME, stack.tab)
        assertFalse(stack.canPop)
        assertFalse(stack.pop())
        assertEquals(VideoRoute.Home, stack.top)
    }

    @Test
    fun aPushLandsOnTheCurrentTabAndAPopTakesItOff() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.History)
        stack.push(VideoRoute.Channel("UCfakechannel0000000001"))
        assertEquals(VideoRoute.Channel("UCfakechannel0000000001"), stack.top)
        assertEquals(VideoTab.HOME, stack.tab)
        assertTrue(stack.canPop)
        assertTrue(stack.pop())
        assertEquals(VideoRoute.History, stack.top)
        assertTrue(stack.pop())
        assertEquals(VideoRoute.Home, stack.top)
        assertFalse(stack.pop(), "a tab root cannot be popped")
    }

    @Test
    fun eachTabKeepsItsOwnStack() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.History)
        stack.selectTab(VideoTab.SUBSCRIPTIONS)
        assertEquals(VideoRoute.Subscriptions, stack.top, "the other tab starts at its root")
        stack.push(VideoRoute.Downloads)
        stack.selectTab(VideoTab.HOME)
        assertEquals(VideoRoute.History, stack.top, "Home still has History on it")
        stack.selectTab(VideoTab.SUBSCRIPTIONS)
        assertEquals(VideoRoute.Downloads, stack.top, "Subscriptions still has Downloads on it")
        assertEquals(listOf(VideoRoute.Subscriptions, VideoRoute.Downloads), stack.routesOf(VideoTab.SUBSCRIPTIONS))
        assertEquals(listOf(VideoRoute.Home, VideoRoute.History), stack.routesOf(VideoTab.HOME))
        assertEquals(listOf(VideoRoute.Library), stack.routesOf(VideoTab.LIBRARY))
    }

    @Test
    fun selectingTheCurrentTabPopsItToItsRoot() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.History)
        stack.push(VideoRoute.WatchLater)
        stack.selectTab(VideoTab.HOME)
        assertEquals(VideoRoute.Home, stack.top)
        assertFalse(stack.canPop)
        // and the other tabs are untouched
        stack.selectTab(VideoTab.LIBRARY)
        stack.push(VideoRoute.Liked)
        stack.selectTab(VideoTab.HOME)
        stack.selectTab(VideoTab.LIBRARY)
        assertEquals(VideoRoute.Liked, stack.top)
        stack.selectTab(VideoTab.LIBRARY)
        assertEquals(VideoRoute.Library, stack.top)
    }

    @Test
    fun theSameVideoPushedTwiceInARowReplacesInsteadOfStacking() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.Watch("fakevideo01"))
        stack.push(VideoRoute.Watch("fakevideo01", startMs = 90_000))
        assertEquals(VideoRoute.Watch("fakevideo01", 90_000), stack.top, "the newer request wins")
        assertTrue(stack.pop())
        assertEquals(VideoRoute.Home, stack.top, "one entry, not two")
        assertFalse(stack.canPop)
    }

    @Test
    fun anotherVideoStacksAndBackComesToTheFirst() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.Watch("fakevideo01"))
        stack.push(VideoRoute.Watch("fakevideo02"))
        assertEquals(VideoRoute.Watch("fakevideo02"), stack.top)
        stack.pop()
        assertEquals(VideoRoute.Watch("fakevideo01"), stack.top)
    }

    @Test
    fun theSameVideoNotInARowStacksAgain() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.Watch("fakevideo01"))
        stack.push(VideoRoute.Channel("UCfakechannel0000000001"))
        stack.push(VideoRoute.Watch("fakevideo01"))
        assertEquals(3, stack.routesOf(VideoTab.HOME).size - 1, "three pushed entries")
    }

    @Test
    fun searchesReplaceEachOther() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.Search())
        stack.push(VideoRoute.Search("cats"))
        stack.push(VideoRoute.Search("dogs"))
        assertEquals(VideoRoute.Search("dogs"), stack.top)
        assertTrue(stack.pop())
        assertEquals(VideoRoute.Home, stack.top, "one search entry")
        // a search after something else stacks
        stack.push(VideoRoute.Search("cats"))
        stack.push(VideoRoute.Watch("fakevideo01"))
        stack.push(VideoRoute.Search("dogs"))
        assertEquals(3, stack.routesOf(VideoTab.HOME).size - 1)
    }

    @Test
    fun pushingTheRouteThatIsAlreadyOnTopChangesNothing() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.History)
        stack.push(VideoRoute.History)
        assertEquals(listOf(VideoRoute.Home, VideoRoute.History), stack.routesOf(VideoTab.HOME))
    }

    @Test
    fun pushingATabRootSelectsThatTab() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.History)
        stack.push(VideoRoute.Library)
        assertEquals(VideoTab.LIBRARY, stack.tab)
        assertEquals(VideoRoute.Library, stack.top)
        stack.push(VideoRoute.Home)
        assertEquals(VideoTab.HOME, stack.tab)
        assertEquals(VideoRoute.Home, stack.top, "a root pushed shows the root: what was stacked on Home is dropped, nothing is stacked on it")
        assertEquals(listOf(VideoRoute.Home), stack.routesOf(VideoTab.HOME))
    }

    @Test
    fun replaceTopSwapsThePushedRouteAndNeverTheRoot() {
        val stack = VideoBackStack()
        stack.replaceTop(VideoRoute.History)
        assertEquals(listOf(VideoRoute.Home, VideoRoute.History), stack.routesOf(VideoTab.HOME), "the root stays, so the route is pushed")
        stack.replaceTop(VideoRoute.Downloads)
        assertEquals(listOf(VideoRoute.Home, VideoRoute.Downloads), stack.routesOf(VideoTab.HOME))
        stack.replaceTop(VideoRoute.Subscriptions)
        assertEquals(VideoTab.SUBSCRIPTIONS, stack.tab, "a root route selects its tab")
    }

    // ---- The routes as text ---------------------------------------------------------------------------------------------

    private val everyRoute: List<VideoRoute> = listOf(
        VideoRoute.Home,
        VideoRoute.Subscriptions,
        VideoRoute.Library,
        VideoRoute.Search(),
        VideoRoute.Search("cats and dogs"),
        VideoRoute.Watch("fakevideo01"),
        VideoRoute.Watch("fakevideo01", 125_000L),
        VideoRoute.Shorts(ShortsOrigin.Home),
        VideoRoute.Shorts(ShortsOrigin.Subscriptions, startVideoId = "fakeshort03", seedKey = "seed-4"),
        VideoRoute.Shorts(ShortsOrigin.Channel("UCfakechannel0000000001"), "fakeshort01"),
        VideoRoute.Shorts(ShortsOrigin.Search("lofi : beats; 100% | ~mix"), seedKey = "seed-1"),
        VideoRoute.Shorts(ShortsOrigin.Related("fakeshort09"), "fakeshort09", "seed-2"),
        VideoRoute.Channel("UCfakechannel0000000001"),
        VideoRoute.Channel("UCfakechannel0000000002", ChannelTab.SHORTS),
        VideoRoute.Playlist("PLfakeplaylist0000000001"),
        VideoRoute.LocalPlaylist("local-1"),
        VideoRoute.LocalPlayer("local:00ab12cd34ef56a7"),
        VideoRoute.LocalFiles,
        VideoRoute.History,
        VideoRoute.WatchLater,
        VideoRoute.Liked,
        VideoRoute.SavedPlaylists,
        VideoRoute.Channels,
        VideoRoute.Downloads,
        VideoRoute.Settings,
        VideoRoute.Import,
    )

    @Test
    fun everyRouteSurvivesTheRoundTrip() {
        for (route in everyRoute) {
            val text = route.encode()
            assertEquals(route, VideoRoute.decode(text), "round trip of $route as '$text'")
            assertFalse(text.any { it == ';' || it == '|' }, "'$text' holds no stack separator")
        }
    }

    @Test
    fun theShortsRouteKeepsItsOriginAndItsOptionalFields() {
        val route = VideoRoute.Shorts(ShortsOrigin.Channel("UCfakechannel0000000001"), startVideoId = null, seedKey = "seed-9")
        val back = VideoRoute.decode(route.encode()) as VideoRoute.Shorts
        assertEquals(ShortsOrigin.Channel("UCfakechannel0000000001"), back.origin)
        assertNull(back.startVideoId)
        assertEquals("seed-9", back.seedKey)
    }

    @Test
    fun anEmptyQueryAndAMissingValueAreNotTheSame() {
        assertEquals(VideoRoute.Search(""), VideoRoute.decode(VideoRoute.Search("").encode()))
        assertNull((VideoRoute.decode(VideoRoute.Watch("a", null).encode()) as VideoRoute.Watch).startMs)
        assertEquals("~", (VideoRoute.decode(VideoRoute.Search("~").encode()) as VideoRoute.Search).query, "a tilde typed by the user is not 'no value'")
        val short = VideoRoute.Shorts(ShortsOrigin.Home, startVideoId = "", seedKey = null)
        val back = VideoRoute.decode(short.encode()) as VideoRoute.Shorts
        assertEquals("", back.startVideoId)
        assertNull(back.seedKey)
    }

    @Test
    fun textInAnyScriptComesBackAsItWas() {
        val query = "नमस्ते दुनिया 100% :;|~ 🎵"
        val route = VideoRoute.Search(query)
        assertEquals(route, VideoRoute.decode(route.encode()))
    }

    @Test
    fun textThatIsNotARouteDecodesToNull() {
        for (bad in listOf("", "nothing", "watch", "watch:", "watch:a:notanumber", "channel:UC1:NOPE", "shorts:bogus:~:~", "shorts:home", "search:%ZZ", "search:%4", "home:extra:fields:no")) {
            assertNull(VideoRoute.decode(bad), "'$bad' is not a route")
        }
    }

    // ---- The stack as text ----------------------------------------------------------------------------------------------

    @Test
    fun aFreshStackRoundTrips() {
        val back = VideoBackStack.decode(VideoBackStack().encode())
        assertEquals(VideoRoute.Home, back?.top)
        assertEquals(VideoTab.HOME, back?.tab)
    }

    @Test
    fun aStackWithPagesOnEveryTabRoundTrips() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.Watch("fakevideo01", 4_000))
        stack.push(VideoRoute.Shorts(ShortsOrigin.Search("a:b;c|d"), "fakeshort01", "seed-3"))
        stack.selectTab(VideoTab.SUBSCRIPTIONS)
        stack.push(VideoRoute.Channel("UCfakechannel0000000002", ChannelTab.LIVE))
        stack.selectTab(VideoTab.LIBRARY)
        stack.push(VideoRoute.LocalPlaylist("local-7"))
        stack.push(VideoRoute.Settings)
        stack.selectTab(VideoTab.SUBSCRIPTIONS)

        val back = VideoBackStack.decode(stack.encode())!!
        assertEquals(VideoTab.SUBSCRIPTIONS, back.tab)
        for (tab in VideoTab.entries) assertEquals(stack.routesOf(tab), back.routesOf(tab), "the stack of $tab")
        assertEquals(stack.encode(), back.encode())
        // and it is a working stack, not a copy of a view: popping goes on from there
        assertTrue(back.pop())
        assertEquals(VideoRoute.Subscriptions, back.top)
    }

    @Test
    fun textThatIsNotAStackDecodesToNull() {
        for (bad in listOf("", "v1", "v1|HOME", "v1|HOME|", "v2|HOME|||", "v1|NOPE|||", "v1|HOME|a|b|c|d", "garbage", "v1|HOME|home;||")) {
            val back = VideoBackStack.decode(bad)
            // "v1|HOME|home;||" holds a root as a pushed route, which is dropped rather than refused: it still reads
            if (bad == "v1|HOME|home;||") assertEquals(VideoRoute.Home, back?.top) else assertNull(back, "'$bad' is not a stack")
        }
    }

    @Test
    fun aRouteThatNoLongerExistsIsLeftOutAndTheRestStays() {
        val back = VideoBackStack.decode("v1|HOME|history;futurepage:x;downloads||")!!
        assertEquals(listOf(VideoRoute.Home, VideoRoute.History, VideoRoute.Downloads), back.routesOf(VideoTab.HOME))
    }

    @Test
    fun aStackThatIsTooDeepIsCutToItsNewestEntries() {
        val text = "v1|HOME|" + (1..500).joinToString(";") { "watch:v$it:~" } + "||"
        val back = VideoBackStack.decode(text)!!
        val routes = back.routesOf(VideoTab.HOME)
        assertEquals(VideoRoute.Home, routes.first())
        assertEquals(VideoBackStack.MAX_DEPTH + 1, routes.size)
        assertEquals(VideoRoute.Watch("v500"), back.top, "the newest stay")
        assertNotEquals(VideoRoute.Watch("v1"), routes[1])
    }

    @Test
    fun theStackStopsGrowingAtItsLimit() {
        val stack = VideoBackStack()
        repeat(VideoBackStack.MAX_DEPTH + 40) { stack.push(VideoRoute.Watch("v$it")) }
        assertEquals(VideoBackStack.MAX_DEPTH + 1, stack.routesOf(VideoTab.HOME).size)
        assertEquals(VideoRoute.Watch("v${VideoBackStack.MAX_DEPTH + 39}"), stack.top, "the newest is on top")
    }
}
