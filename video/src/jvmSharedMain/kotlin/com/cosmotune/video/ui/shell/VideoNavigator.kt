package com.cosmotune.video.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.cosmotune.video.nav.VideoBackStack
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.nav.VideoTab

/**
 * Video mode's back stack as the screens see it (spec 4.4): [VideoBackStack] behind snapshot state, so whatever reads [top],
 * [tab] or [canPop] in composition is redrawn when a page is pushed, popped or replaced. The stack is the only thing it owns; the
 * rules (what replaces what, what a tab root does) are the stack's.
 *
 * On the phone it lives in [rememberVideoNavigator], which keeps it across a configuration change and while the mode is Music
 * ([Saver]: the stack as one line of text); on the desktop one navigator lives for the process.
 */
@Stable
class VideoNavigator(private val stack: VideoBackStack = VideoBackStack()) {
    /** Bumped by every change of [stack], which is not itself snapshot state; every reader reads it first. */
    private var revision by mutableIntStateOf(0)

    /** The page on show. */
    val top: VideoRoute
        get() {
            revision
            return stack.top
        }

    /** The tab on show. */
    val tab: VideoTab
        get() {
            revision
            return stack.tab
        }

    /** Whether there is a page above the tab's root to go back to. */
    val canPop: Boolean
        get() {
            revision
            return stack.canPop
        }

    /** Opens [route] on the current tab (the stack's rules: the same video or a second search replaces, a tab root shows the root). */
    fun push(route: VideoRoute) {
        stack.push(route)
        revision++
    }

    /** Goes back one page; false at a tab root, where there is nowhere to go. */
    fun pop(): Boolean {
        val popped = stack.pop()
        if (popped) revision++
        return popped
    }

    /** Shows [tab]; the tab already on show is popped to its root. */
    fun selectTab(tab: VideoTab) {
        stack.selectTab(tab)
        revision++
    }

    /** Puts [route] in place of the top page. */
    fun replaceTop(route: VideoRoute) {
        stack.replaceTop(route)
        revision++
    }

    /**
     * Every page of every tab, each with a key that tells one entry of the stacks from another (its tab, its place and the route
     * itself), for the nav host to keep a page's saved state apart from the next one's.
     */
    fun pageKeys(): Set<String> {
        revision
        return buildSet { for (each in VideoTab.entries) stack.routesOf(each).forEachIndexed { index, route -> add(pageKey(each, index, route)) } }
    }

    /** The key of the page on show (see [pageKeys]). */
    fun topPageKey(): String {
        revision
        return pageKey(stack.tab, stack.routesOf(stack.tab).lastIndex, stack.top)
    }

    /** The stack as one line of text (see [VideoBackStack.encode]). */
    fun encode(): String {
        revision
        return stack.encode()
    }

    private fun pageKey(tab: VideoTab, index: Int, route: VideoRoute) = "${tab.name}/$index/${route.encode()}"

    companion object {
        /** Keeps a navigator across a configuration change: the stack as text, read back by [VideoBackStack.decode] (a fresh stack when it cannot be). */
        val Saver: Saver<VideoNavigator, String> = Saver(
            save = { it.encode() },
            restore = { VideoNavigator(VideoBackStack.decode(it) ?: VideoBackStack()) },
        )
    }
}

/** The phone's navigator: saved with the screen, so a rotation or a visit to Music keeps the pages where they were. */
@Composable
fun rememberVideoNavigator(): VideoNavigator = rememberSaveable(saver = VideoNavigator.Saver) { VideoNavigator() }

/** The navigator of the screen being drawn; provided by the shell. */
val LocalVideoNavigator = staticCompositionLocalOf<VideoNavigator> { error("LocalVideoNavigator: no VideoNavigator is provided here") }
