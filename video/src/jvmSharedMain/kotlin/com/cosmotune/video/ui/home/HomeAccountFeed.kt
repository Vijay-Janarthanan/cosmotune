package com.cosmotune.video.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cosmotune.video.account.VideoAccount
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The signed-in account's own Home ("what to watch"), kept apart from the screen: the first page, then more at the end of the list.
 * A failure (the session ran out, no connection) leaves [items] empty, and Home falls back to its kiosk and the local library.
 * Calls return at once; the work runs in [scope], the page's own.
 */
@Stable
internal class AccountHomeFeed(private val account: VideoAccount, private val scope: CoroutineScope) {
    /** The videos so far, without repeats (the account's Shorts among them: the Shorts shelf takes those). */
    var items: List<VideoItem> by mutableStateOf(emptyList())
        private set

    /** Whether the first page has been answered (with videos or without). */
    var loaded: Boolean by mutableStateOf(false)
        private set

    /** Whether the next page is on its way. */
    var loadingMore: Boolean by mutableStateOf(false)
        private set

    private var next: PageToken? = null

    /** Asks for the first page. */
    fun start() {
        scope.launch {
            val page = account.home(null).getOrNull()
            if (page != null) {
                items = page.items.distinctBy { it.id }
                next = page.next
            }
            loaded = true
        }
    }

    /** Asks for the next page when there is one and none is on its way. A failure keeps the token for the next try. */
    fun loadMore() {
        val token = next ?: return
        if (!loaded || loadingMore) return
        loadingMore = true
        scope.launch {
            try {
                account.home(token).getOrNull()?.let { page ->
                    items = (items + page.items).distinctBy { it.id }
                    next = page.next
                }
            } finally {
                loadingMore = false
            }
        }
    }
}
