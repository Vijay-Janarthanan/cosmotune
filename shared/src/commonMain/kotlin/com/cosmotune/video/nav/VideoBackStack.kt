package com.cosmotune.video.nav

/**
 * The three tabs of the phone's capsule (spec 5.1), each the root of its own stack: Home, Subscriptions and Library. On the
 * desktop they are the sidebar's Home, Subscriptions and Playlists rows.
 */
enum class VideoTab(val root: VideoRoute) {
    HOME(VideoRoute.Home),
    SUBSCRIPTIONS(VideoRoute.Subscriptions),
    LIBRARY(VideoRoute.Library),
    ;

    companion object {
        /** The tab whose root is [route], or null when [route] is not a root. */
        fun rootedAt(route: VideoRoute): VideoTab? = entries.firstOrNull { it.root == route }
    }
}

/**
 * Video mode's back stack (spec 4.4): one stack per tab, pure and in plain memory (the snapshot-state wrapper the screens
 * observe is `VideoNavigator`, in `:video`).
 *
 * - [push] lands on the current tab. Pushing the route that is already on top changes nothing; a [VideoRoute.Watch] on top of
 *   a [VideoRoute.Watch] of the same video is replaced by the newer request (a new start time), and a [VideoRoute.Search] on
 *   top of a [VideoRoute.Search] is replaced, so a stack never holds the same video or two searches one after the other; a
 *   [VideoRoute.LocalPlayer] on top of another is replaced too (opening a second file takes the player's place).
 *   A tab root pushed shows that root (the tab is selected and its stack dropped): a root is never stacked on something.
 * - [pop] removes the top and answers whether it did: it refuses at a tab root.
 * - [selectTab] of another tab shows that tab's own stack as it was left; of the current tab it pops this one to its root.
 * - [replaceTop] swaps the top for another route. The root is not swapped: on a root the route is pushed.
 *
 * A stack keeps at most [MAX_DEPTH] pages above each root; the oldest go first. [encode] and [decode] turn the whole stack into
 * one line of text for the phone's `Saver`.
 */
class VideoBackStack {
    private var current = VideoTab.HOME
    private val pushed: Map<VideoTab, MutableList<VideoRoute>> = VideoTab.entries.associateWith { mutableListOf<VideoRoute>() }

    /** The tab on show. */
    val tab: VideoTab get() = current

    /** The page on show: the last route pushed on the current tab, else its root. */
    val top: VideoRoute get() = pushed.getValue(current).lastOrNull() ?: current.root

    /** Whether [pop] would remove something: there is a page above the current tab's root. */
    val canPop: Boolean get() = pushed.getValue(current).isNotEmpty()

    /** The routes of [tab] from its root up to the page on top. */
    fun routesOf(tab: VideoTab): List<VideoRoute> = listOf(tab.root) + pushed.getValue(tab)

    /** Opens [route] on the current tab (see the class comment for what replaces and what is ignored). */
    fun push(route: VideoRoute) {
        VideoTab.rootedAt(route)?.let { showRoot(it); return }
        val list = pushed.getValue(current)
        val last = list.lastOrNull()
        when {
            last == route -> Unit
            last is VideoRoute.Watch && route is VideoRoute.Watch && last.videoId == route.videoId -> list[list.lastIndex] = route
            last is VideoRoute.Search && route is VideoRoute.Search -> list[list.lastIndex] = route
            last is VideoRoute.LocalPlayer && route is VideoRoute.LocalPlayer -> list[list.lastIndex] = route
            else -> {
                list += route
                if (list.size > MAX_DEPTH) list.removeAt(0)
            }
        }
    }

    /** Takes the top page off the current tab; false (and nothing changes) at the tab's root. */
    fun pop(): Boolean {
        val list = pushed.getValue(current)
        if (list.isEmpty()) return false
        list.removeAt(list.lastIndex)
        return true
    }

    /** Shows [tab]; when it is the tab on show already, pops it to its root. */
    fun selectTab(tab: VideoTab) {
        if (tab == current) pushed.getValue(tab).clear() else current = tab
    }

    /** Shows the root of [tab]: the tab is selected and whatever was stacked on it is dropped. */
    private fun showRoot(tab: VideoTab) {
        current = tab
        pushed.getValue(tab).clear()
    }

    /** Puts [route] in place of the top page of the current tab (a root is not replaced: [route] is pushed on it). */
    fun replaceTop(route: VideoRoute) {
        VideoTab.rootedAt(route)?.let { showRoot(it); return }
        val list = pushed.getValue(current)
        if (list.isEmpty()) push(route) else list[list.lastIndex] = route
    }

    /** The stack as one line: `v1|<tab>|<routes of Home>|<routes of Subscriptions>|<routes of Library>`, routes joined by `;`. */
    fun encode(): String =
        "$VERSION|${current.name}|" + VideoTab.entries.joinToString("|") { tab -> pushed.getValue(tab).joinToString(";") { it.encode() } }

    override fun toString(): String = "VideoBackStack(${encode()})"

    companion object {
        /** The most pages kept above one tab's root. */
        const val MAX_DEPTH = 100

        private const val VERSION = "v1"

        /**
         * The stack [text] was made from by [encode], or null when it is not one (never throws). A route this version does not
         * know, or a root where a page should be, is left out and the rest stays; a stack deeper than [MAX_DEPTH] keeps its
         * newest pages.
         */
        fun decode(text: String): VideoBackStack? {
            val parts = text.split('|')
            if (parts.size != 2 + VideoTab.entries.size || parts[0] != VERSION) return null
            val tab = VideoTab.entries.firstOrNull { it.name == parts[1] } ?: return null
            val stack = VideoBackStack()
            VideoTab.entries.forEachIndexed { index, each ->
                val routes = parts[2 + index].split(';')
                    .filter { it.isNotEmpty() }
                    .mapNotNull { VideoRoute.decode(it) }
                    .filter { VideoTab.rootedAt(it) == null }
                stack.pushed.getValue(each).addAll(routes.takeLast(MAX_DEPTH))
            }
            stack.current = tab
            return stack
        }
    }
}
