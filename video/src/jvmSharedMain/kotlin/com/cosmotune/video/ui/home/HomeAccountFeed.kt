package com.cosmotune.video.ui.home

import androidx.compose.runtime.*
import com.cosmotune.video.account.AccountFailure
import com.cosmotune.video.account.VideoAccount
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.*

/** Account pages are an independent pool: failures keep useful cards and require an explicit retry. */
@Stable
internal class AccountHomeFeed(private val account: VideoAccount, private val scope: CoroutineScope) {
    var items: List<VideoItem> by mutableStateOf(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var firstError by mutableStateOf(false); private set
    var moreError by mutableStateOf(false); private set
    var sessionExpired by mutableStateOf(false); private set
    var repeatedPage by mutableStateOf(false); private set
    val hasMore get() = next != null && items.size < MAX_ITEMS
    val exhausted get() = loaded && !firstError && !hasMore
    private var next: PageToken? = null
    private var generation = 0
    private var job: Job? = null
    private val consumed = HashSet<String>()
    private var emptyPages = 0

    fun start() {
        job?.cancel(); val version = ++generation
        firstError = false; moreError = false; repeatedPage = false; sessionExpired = false
        loaded = false; loadingMore = false
        job = scope.launch {
            try {
                val page = account.home(null).getOrThrow()
                ensureActive(); if (version != generation) return@launch
                items = page.items.distinctBy { it.id }.take(MAX_ITEMS)
                consumed.clear(); emptyPages = 0; next = page.next
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (version == generation) { firstError = true; sessionExpired = failure is AccountFailure.SessionExpired || failure is AccountFailure.NotSignedIn }
            } finally { if (version == generation) loaded = true }
        }
    }
    fun retry() { account.invalidate(); if (firstError || !loaded) start() else loadMore(retry = true) }
    fun loadMore(retry: Boolean = false) {
        val token = next ?: return
        if (!loaded || loadingMore || firstError || moreError && !retry || !hasMore) return
        loadingMore = true; moreError = false
        val version = generation
        job = scope.launch {
            try {
                val page = account.home(token).getOrThrow()
                ensureActive(); if (version != generation) return@launch
                val merged = (items + page.items).distinctBy { it.id }.take(MAX_ITEMS)
                emptyPages = if (merged.size == items.size) emptyPages + 1 else 0
                items = merged; consumed += token.value
                repeatedPage = page.next?.value in consumed || emptyPages >= 2
                next = page.next.takeUnless { repeatedPage || consumed.size >= 128 || items.size >= MAX_ITEMS }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (version == generation) { moreError = true; sessionExpired = failure is AccountFailure.SessionExpired || failure is AccountFailure.NotSignedIn }
            } finally { if (version == generation) loadingMore = false }
        }
    }
    fun close() { ++generation; job?.cancel(); loadingMore = false }
    companion object { private const val MAX_ITEMS = 600 }
}
