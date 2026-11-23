package com.cosmotune.watch

import com.cosmotune.watch.plugin.AddSourceResult
import com.cosmotune.watch.plugin.UserSourceSpec
import com.cosmotune.watch.store.UnsealedWatchFiles
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileSignInTest {
    @Test fun eachProfileCanSeeAServerAsItsOwnUser() = runBlocking<Unit> {
        TestServer().use { s ->
            s.on("/Users/AuthenticateByName") { r ->
                val user = Regex("\"Username\":\"(\\w+)\"").find(r.body.readUtf8())?.groupValues?.get(1)
                if (user == "nobody") MockResponse().setResponseCode(401)
                else MockResponse().setHeader("Content-Type", "application/json").setBody("""{"AccessToken":"tok-$user","User":{"Id":"id-$user"}}""")
            }
            s.json("/System/Info/Public", """{"ServerName":"Home"}""")
            s.json("/LiveTv/Channels", """{"Items":[{"Id":"ch1","Name":"One"}]}""")
            val dir = Files.createTempDirectory("watch-profile-signin").toFile().also { it.deleteOnExit() }
            val store = WatchStore(File(dir, "watch.json"), UnsealedWatchFiles)
            val repository = DefaultWatchRepository(WatchConfig(okhttp3.OkHttpClient(), dir, null), store, builtIns = { _, _ -> emptyList() })
            val added = assertIs<AddSourceResult.Added>(repository.addUserSource(UserSourceSpec.Jellyfin(s.base, "parent", "pw")))
            val id = added.plugin.id
            assertTrue(added.plugin.perProfileSignIn)
            val kid = repository.addProfile("Kid")!!
            assertTrue(repository.selectProfile(kid))
            assertTrue(repository.signInForProfile(id, "nobody", "x") != null, "a refused sign-in says why")
            assertNull(repository.signInForProfile(id, "kid", "pw"))
            assertEquals("Signed in as kid for this profile", repository.plugins.value.first { it.id == id }.profileSignIn)
            repository.channels()
            assertEquals("tok-kid", s.requests.last { it.path!!.startsWith("/LiveTv/Channels") }.getHeader("X-Emby-Token"))
            assertTrue(store.state.value.sources.single().profileSessions.values.none { "pw" in it.toString() }, "the password is not kept")
            assertTrue(repository.selectProfile("default"))
            assertNull(repository.plugins.value.first { it.id == id }.profileSignIn)
            repository.channels()
            assertTrue(s.requests.filter { it.path!!.startsWith("/LiveTv/Channels") }.any { it.getHeader("X-Emby-Token") == "tok-parent" })
            assertTrue(repository.selectProfile(kid))
            repository.useSharedSignIn(id)
            assertNull(repository.plugins.value.first { it.id == id }.profileSignIn)
        }
    }
}
