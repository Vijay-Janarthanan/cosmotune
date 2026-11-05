package com.cosmotune.youtube.account

/**
 * Where the one Google sign-in of the app stands, for Music and Video alike. Read from [YouTubeSession.state].
 */
sealed interface YouTubeAuthState {
    /** No session: both modes browse as a guest and keep to their local data. */
    data object SignedOut : YouTubeAuthState

    /**
     * A session that signs requests. [account] and [profile] are who the platform says it is (null until it has said); the
     * session works without them.
     */
    data class SignedIn(val account: YouTubeAccountInfo?, val profile: YouTubeProfileInfo?) : YouTubeAuthState

    /**
     * The session was refused ([reason]: a 401/403 that kept coming, or a page that says it is signed out). The listener has to sign
     * in again; [account] and [profile] are who it was, for the banner.
     */
    data class Expired(val reason: String, val account: YouTubeAccountInfo?, val profile: YouTubeProfileInfo?) : YouTubeAuthState
}

/** The Google login as the platform knows it. [id] is the platform's own id for it. */
data class YouTubeAccountInfo(val id: String, val name: String, val email: String?, val avatarUrl: String?)

/** The channel (personal or brand) of the login that requests act as. [id] is the platform's own id for it. */
data class YouTubeProfileInfo(val id: String, val name: String, val handle: String?, val avatarUrl: String?, val isBrand: Boolean)

/**
 * Where a platform keeps the session's credential: the phone's encrypted store, the desktop's keyring. [YouTubeSession] hands it
 * the jar when a response rotated a cookie, so the next launch starts with the fresh one rather than the one captured at sign-in.
 */
fun interface CredentialVault {
    /**
     * Keeps [cookie], the current `Cookie` header of the login in force. The platform writes it over the stored credential of the
     * same login ([YouTubeCookieJar.sameLogin]) and leaves every other login alone.
     */
    fun store(cookie: String)

    /** Keeps [cookie], the current accounts.google.com jar of the same login, when the platform keeps one. */
    fun storeGoogle(cookie: String) {}
}
