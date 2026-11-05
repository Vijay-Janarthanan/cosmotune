package com.cosmotune.music

import com.cosmotune.music.data.settings.SecretMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BC-21: the Spotify sign-in follows the upstream fix (a root overlay WebView, not one inside the blurred page tree) and the
 * Cosmotune credential policy (the sp_dc cookie lives in the encrypted store that device backups exclude, never in exports).
 */
class SpotifyAccountTest {
    @Test fun aTokenFromAnEarlierBuildMovesToTheEncryptedStoreAndLeavesThePlainSettings() {
        val moved = SecretMove.resolve(plain = "abc", encrypted = null)
        assertEquals("abc", moved.value)
        assertEquals("abc", moved.writeEncrypted)
        assertTrue(moved.removePlain)
    }

    @Test fun theEncryptedCopyWinsAndAStalePlainOneIsStillRemoved() {
        val kept = SecretMove.resolve(plain = "old", encrypted = "new")
        assertEquals("new", kept.value)
        assertNull(kept.writeEncrypted)
        assertTrue(kept.removePlain)
    }

    @Test fun nothingStoredIsNothingToDo() {
        val none = SecretMove.resolve(plain = null, encrypted = null)
        assertEquals("", none.value)
        assertNull(none.writeEncrypted)
        assertFalse(none.removePlain)
        val blank = SecretMove.resolve(plain = "", encrypted = null)
        assertEquals("", blank.value)
        assertNull(blank.writeEncrypted)
        assertTrue("an empty plain entry is cleaned up too", blank.removePlain)
        assertEquals("only encrypted", "e", SecretMove.resolve(plain = null, encrypted = "e").value)
    }

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun read(path: String) = File(root, path).readText().replace("\r\n", "\n").replace(Regex("\\s+"), " ")

    @Test fun theTokenIsWrittenToTheEncryptedStoreWhichBackupsExclude() {
        val settings = read("app/src/main/java/com/cosmotune/music/data/settings/AppSettings.kt")
        assertTrue("authStore.setSecret(key, value.ifEmpty { null }) prefs.edit().remove(key).apply()" in settings)
        assertTrue("spotifySpdcToken.value = readSecret(KEY_SPOTIFY_SPDC_TOKEN)" in settings)
        assertTrue("writeSecret(KEY_SPOTIFY_SPDC_TOKEN, value)" in settings)
        // Every credential the settings carry goes the same way: none is read from or written to the plain, backed-up file.
        val secrets = Regex("""private val SECRETS = setOf\(([^)]*)\)""").find(settings)!!.groupValues[1]
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(6, secrets.size)
        assertTrue("KEY_SPOTIFY_SPDC_TOKEN" in secrets)
        for (key in secrets) {
            assertFalse("$key is written in plain", "putString($key" in settings)
            assertFalse("$key is read in plain", "getString($key" in settings)
            assertTrue("$key is read through the move", "readSecret($key)" in settings)
        }
        val store = read("app/src/main/java/com/cosmotune/music/auth/AuthStore.kt")
        assertTrue("EncryptedPrefs.open(context, \"cosmotune_auth\", \"cosmotune_auth_plain\")" in store)
        assertTrue("fun secret(key: String): String? = prefs.getString(SECRET_PREFIX + key, null)" in store)
        for (rules in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = read("app/src/main/res/xml/$rules")
            assertTrue("$rules excludes the encrypted store", "path=\"cosmotune_auth.xml\"" in xml && "path=\"cosmotune_auth_plain.xml\"" in xml)
        }
    }

    @Test fun signInIsARootOverlayAndDisconnectSignsTheWebViewOut() {
        val host = read("app/src/main/java/com/cosmotune/music/MainActivity.kt")
        assertTrue("onOpenLogin = { showSpotifyLogin = true }, onOpenTokenSetup = { showSpotifyCanvasAuth = true }," in host)
        assertTrue("if (showSpotifyLogin) { BackHandler { showSpotifyLogin = false }" in host)
        assertTrue("SpotifyLoginScreen( modifier = Modifier.navigationBarsPadding(), onConnected = { token -> AppSettings.setSpotifySpdcToken(token) showSpotifyLogin = false }," in host)
        val page = read("app/src/main/java/com/cosmotune/music/ui/screens/SpotifyLibraryScreen.kt")
        assertFalse("the page itself hosts no WebView any more", "WebView" in page)
        assertTrue("Button(onClick = onOpenLogin)" in page && "OutlinedButton(onClick = onOpenTokenSetup)" in page)
        val account = read("app/src/main/java/com/cosmotune/music/ui/screens/AccountAndScrobblingScreen.kt")
        assertTrue("clearSpotifyWebSession() AppSettings.setSpotifySpdcToken(\"\")" in account)
        val login = read("app/src/main/java/com/cosmotune/music/auth/SpotifyLoginScreen.kt")
        assertTrue("navigation is kept to the sign-in hosts", "override fun shouldOverrideUrlLoading( view: WebView, request: WebResourceRequest, ): Boolean = !isLoginHost(request.url)" in login)
        assertTrue("only https hosts match", "if (uri?.scheme != \"https\") return false" in login)
        assertTrue("the browser's own user agent, not a desktop one", "settings.userAgentString = browserUserAgent(settings.userAgentString)" in login)
    }
}
