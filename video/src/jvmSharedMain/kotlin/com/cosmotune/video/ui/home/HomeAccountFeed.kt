package com.cosmotune.video.ui.home

import com.cosmotune.cache.CacheCodec
import com.cosmotune.cache.CachedLoad
import com.cosmotune.cache.FEED_FRESH_MS
import com.cosmotune.cache.PageCache
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.account.InnerTubeVideoAccount
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

    /**
     * The first page this account's Home showed last (memory for the process, and the disk for the app's own account): shown at
     * once when Home opens again, kept while it refreshes, and not fetched again for ten minutes.
     */
    private val first: CachedLoad<SavedAccountPage> = run {
        val signedIn = account.state.value as? AccountState.SignedIn
        val who = signedIn?.active?.id ?: signedIn?.displayName.orEmpty()
        PageCache.of(account, persistent = account === InnerTubeVideoAccount.shared)
            .entry("video:account-home:$who", FEED_FRESH_MS, ACCOUNT_PAGE_CODEC) { error("filled by start") }
    }

    init {
        first.state.value.data?.let { saved -> items = saved.items; next = saved.next?.let(::PageToken); loaded = true }
    }

    /** Fetches the first page, unless it is fresh; [force] (Refresh, Try again) always fetches. */
    fun start(force: Boolean = false) {
        job?.cancel(); val version = ++generation
        firstError = false; moreError = false; repeatedPage = false; sessionExpired = false
        // Cards already on screen stay while the first page is fetched again: no placeholders over them.
        loaded = items.isNotEmpty(); loadingMore = false
        job = scope.launch {
            try {
                first.restore()
                first.state.value.data?.let { saved -> if (items.isEmpty()) { items = saved.items; next = saved.next?.let(::PageToken); loaded = true } }
                if (!force && first.isFresh) return@launch
                val page = account.home(null).getOrThrow()
                ensureActive(); if (version != generation) return@launch
                items = page.items.distinctBy { it.id }.take(MAX_ITEMS)
                first.set(SavedAccountPage(items.take(60), page.next?.value))
                consumed.clear(); emptyPages = 0; next = page.next
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (version == generation) { firstError = true; sessionExpired = failure is AccountFailure.SessionExpired || failure is AccountFailure.NotSignedIn }
            } finally { if (version == generation) loaded = true }
        }
    }
    fun retry() { account.invalidate(); if (firstError || !loaded) start(force = true) else loadMore(retry = true) }
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
    companion object {
        private const val MAX_ITEMS = 600
        private val ACCOUNT_PAGE_CODEC = CacheCodec.json(SavedAccountPage.serializer())
    }
}

/** The first page of the account's Home, as [AccountHomeFeed] keeps it. */
@kotlinx.serialization.Serializable
internal class SavedAccountPage(val items: List<VideoItem>, val next: String? = null)
