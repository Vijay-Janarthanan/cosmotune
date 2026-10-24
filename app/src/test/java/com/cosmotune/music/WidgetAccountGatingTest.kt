package com.cosmotune.music

import com.cosmotune.music.widget.LikeOffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BC-18: the widgets, notification and watch offer Like by the player's rule, and signing in or out changes it everywhere.
 * The rule is pure; its wiring into the service and the 4x1 widget is pinned by reading the sources, since drawing a
 * RemoteViews widget needs a launcher (that check is listed in FEATURE_TRACKER.md as still to do on a device).
 */
class WidgetAccountGatingTest {
    @Test fun aYouTubeTrackOffersLikeOnlyWhileSignedIn() {
        assertTrue(LikeOffer.offered(ownedBySource = false, signedIn = true, localFile = false))
        assertFalse(LikeOffer.offered(ownedBySource = false, signedIn = false, localFile = false))
    }

    @Test fun aLocalFileOrDownloadNeverOffersYouTubeLike() {
        assertFalse(LikeOffer.offered(ownedBySource = false, signedIn = true, localFile = true))
        assertFalse(LikeOffer.offered(ownedBySource = false, signedIn = false, localFile = true))
    }

    @Test fun aSourceOwnedTrackIsRatedLocallyWithoutAnAccount() {
        assertTrue(LikeOffer.offered(ownedBySource = true, signedIn = false, localFile = false))
        assertTrue(LikeOffer.offered(ownedBySource = true, signedIn = false, localFile = true))
    }

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun read(path: String) = File(root, path).readText().replace("\r\n", "\n").replace(Regex("\\s+"), " ")

    @Test fun theServiceAndTheWidgetUseTheRuleAndFollowSignInChanges() {
        val service = read("app/src/main/java/com/cosmotune/music/playback/PlaybackService.kt")
        assertTrue("canLike goes through the shared rule", "LikeOffer.offered(" in service)
        assertTrue("the widget snapshot carries it", "canLike = canLike(exoPlayer.currentMediaItem)," in service)
        assertTrue("signing in or out republishes the widget and the session buttons",
            Regex("""SignedIn\.state\(this@PlaybackService\) \.drop\(1\) \.collectLatest \{ refreshCustomLayouts\(\) publishWidgetState\(\) \}""").containsMatchIn(service))

        val pill = read("app/src/main/java/com/cosmotune/music/widget/MediaWidgetPill.kt")
        assertTrue("the heart is hidden, not greyed, without it",
            "views.setViewVisibility(R.id.widget_like, if (snapshot.canLike) View.VISIBLE else View.GONE)" in pill)
        val snapshot = read("app/src/main/java/com/cosmotune/music/widget/MediaWidgetSnapshot.kt")
        assertTrue(".putBoolean(KEY_CAN_LIKE, snapshot.canLike)" in snapshot)
        assertTrue("a widget drawn before the service has said anything offers no Like", "canLike = prefs.getBoolean(KEY_CAN_LIKE, false)," in snapshot)

        val model = read("app/src/main/java/com/cosmotune/music/ui/MainViewModel.kt")
        assertTrue("the view model writes the process-wide flag", "private val _signedIn = SignedIn.state(app)" in model)
        assertTrue("sign-in sets it", "_signedIn.value = true" in model)
        assertTrue("sign-out clears it", "_signedIn.value = false" in model)
    }
}
