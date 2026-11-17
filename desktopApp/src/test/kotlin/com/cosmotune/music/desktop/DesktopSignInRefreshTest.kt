package com.cosmotune.music.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Signing in again to the account that was signed in before (its sign-in had run out) changes neither the account nor the profile
 * id, which is what the library reload was keyed on: Home kept its "Sign in to YouTube Music" card and the playlists stayed empty
 * (1.0.26, Vijay's log of 2026-11-17: a sign-in at 13:12 and no library read after it). A sign-in now asks for the reload itself.
 */
class DesktopSignInRefreshTest {
    private val app = File("src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt").readText().replace(Regex("""\s+"""), " ")

    @Test fun aSignInReloadsTheLibraryAndTheSignedInStateEvenForTheSameAccount() {
        val signIn = app.substringAfter("suspend fun signIn(cookie: String, label: String").substringBefore("fun playSongs(")
        assertTrue("sessionRevision++" in signIn, "the sign-in bumps the session revision the library reload is keyed on")
        assertTrue("youtubeSignedIn = DesktopYouTubeAuth.isSignedIn" in signIn, "and Home learns at once that it is signed in")
        assertTrue("LaunchedEffect(activeAccountId, activeProfileId, sessionRevision)" in app, "the library reload is keyed on that revision")
    }
}
