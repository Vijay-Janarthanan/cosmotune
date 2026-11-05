package com.cosmotune.video.account

import com.cosmotune.music.data.innertube.Innertube
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * Reads the signed-in lists of a real account and prints how many items each gave. Skipped unless `COSMOTUNE_YT_ACCOUNT_LIVE=1`
 * and the web session cookie header of a test account is in `COSMOTUNE_YT_COOKIE` (an environment variable only; it is never
 * written anywhere and never printed). This is the only check of the signed-in endpoints against the real service.
 */
class YoutubeAccountLiveTest {
    @Test fun printsTheCountsOfTheAccountsOwnLists() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_YT_ACCOUNT_LIVE") == "1")
        val cookie = System.getenv("COSMOTUNE_YT_COOKIE")?.trim()
        assumeTrue(!cookie.isNullOrBlank())
        Innertube.cookie = cookie
        try {
            val account = InnerTubeVideoAccount()
            println("account state: ${account.state.value::class.simpleName}")
            fun <T> show(name: String, result: Result<T>, count: (T) -> Int) =
                println("$name: " + result.fold({ "${count(it)} items" }, { "failed with ${it::class.simpleName}: ${it.message}" }))
            show("home", account.home()) { it.items.size }
            show("subscriptions feed", account.subscriptionsFeed()) { it.items.size }
            show("subscribed channels", account.subscribedChannels()) { it.size }
            show("playlists", account.playlists()) { it.size }
            show("watch later", account.watchLater()) { it.items.size }
            show("liked", account.liked()) { it.items.size }
            show("history", account.history()) { it.items.size }
        } finally {
            Innertube.cookie = null
        }
    }
}
