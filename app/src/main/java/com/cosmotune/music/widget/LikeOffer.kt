package com.cosmotune.music.widget

/**
 * Whether Like is offered for the current track on the surfaces the playback
 * service draws (the notification, the home-screen widgets and the watch), by
 * the player's own rule so no surface shows a heart the player does not.
 *
 * A track a source package owns is rated in that source's local library, so it
 * needs no account. Anything else needs a signed-in YouTube account to record
 * the rating against, and a local file or a download has no YouTube identity to
 * rate. Signing out therefore hides the heart everywhere at once.
 */
internal object LikeOffer {
    fun offered(ownedBySource: Boolean, signedIn: Boolean, localFile: Boolean): Boolean =
        ownedBySource || (signedIn && !localFile)
}
