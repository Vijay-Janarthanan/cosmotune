package com.cosmotune.music.desktop

import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.innertube.StreamResolver
import com.cosmotune.youtube.account.CredentialVault
import com.cosmotune.youtube.account.SapisidAuth
import com.cosmotune.youtube.account.SessionRecovery
import com.cosmotune.youtube.account.YouTubeAccountInfo
import com.cosmotune.youtube.account.YouTubeProfileInfo
import com.cosmotune.youtube.account.YouTubeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Who this app is acting as when it talks to YouTube, and how it proves it. */
internal object DesktopYouTubeAuth {

    const val MUSIC_ORIGIN = "https://music.youtube.com"
    const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    /**
     * The session in force, or null to browse as a guest.
     * @param authUser which entry in the cookie jar, as `X-Goog-AuthUser`.
     * @param pageId the brand channel, as `X-Goog-PageId`. Null while a
     * @param dataSyncId the account, as `context.user.onBehalfOfUser`. Belongs
     */
    data class Session(
        val cookie: String,
        val authUser: String = "0",
        val pageId: String? = null,
        val dataSyncId: String? = null,
    )

    @Volatile
    var session: Session? = null
        private set

    /** Whether requests will be made as somebody rather than as nobody. */
    val isSignedIn: Boolean get() = session != null

    /** The cookie a headless run can be given without an accounts screen. */
    fun environmentCookie(): String? =
        System.getenv("COSMOTUNE_YTMUSIC_COOKIE")?.trim()?.takeIf { it.isNotBlank() }

    /**
     * The desktop's side of the one Google session ([YouTubeSession.shared]): a renewed jar is written back to the keyring
     * ([DesktopAccounts.rotateCookie]), the browser the login came from is the last way back before an expiry
     * ([DesktopAccounts.freshCookieFromBrowser]), and the session is refreshed every ten minutes while the app runs.
     */
    init {
        val shared = YouTubeSession.shared
        shared.vault = CredentialVault { cookie -> runCatching { DesktopAccounts.rotateCookie(cookie) } }
        shared.recovery = SessionRecovery { current ->
            DesktopAccounts.freshCookieFromBrowser(current)?.let { SessionRecovery.Recovered(it) }
        }
        shared.startPeriodicRefresh(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    fun adopt(value: Session?) {
        if (session == value) return
        session = value
        // The YouTube Music client is the phone's, shared; it signs with this
        // cookie and addresses this channel.
        Innertube.cookie = value?.cookie
        Innertube.selectChannel(value?.pageId, value?.dataSyncId, value?.authUser)
        // A new session means a new player config and PoToken binding, as on the phone.
        StreamResolver.onSessionChanged()
        // Video mode's account shows who this is, and so does the shared session (the account sheet, the expiry banner).
        DesktopVideoAccountHost.refresh()
        describeActive()
        DesktopTrackLog.log(
            if (value == null) {
                "youtube: signed out; requests are anonymous again"
            } else {
                "youtube: signed in, acting as authUser=${value.authUser}" +
                    (value.pageId?.let { " pageId=$it" } ?: " on the personal channel")
            },
        )
    }

    /** Tells the shared session who the active account and channel are. */
    fun describeActive() {
        val described = runCatching {
            val account = DesktopAccounts.active()
            val profile = DesktopAccounts.activeProfile()
            if (session == null || account == null) null to null
            else YouTubeAccountInfo(account.accountId, account.name, account.email.ifBlank { null }, account.avatar) to
                profile?.let { YouTubeProfileInfo(it.profileId, it.name, it.handle.takeIf { h -> h.startsWith("@") }, it.avatar, it.isBrandAccount) }
        }.getOrDefault(null to null)
        YouTubeSession.shared.describe(described.first, described.second)
    }

    /** The headers an authenticated request carries, or nothing when signed out. */
    fun headers(origin: String = MUSIC_ORIGIN): Map<String, String> {
        val current = session ?: return emptyMap()
        // The jar in force, which a rotation may have moved past the stored one.
        val cookie = Innertube.cookie ?: current.cookie
        val authorization = SapisidAuth.authorization(cookie, origin) ?: return emptyMap()
        return buildMap {
            put("Cookie", cookie)
            put("Authorization", authorization)
            put("X-Goog-AuthUser", current.authUser)
            put("X-Origin", origin)
            current.pageId?.takeIf { it.isNotBlank() }?.let { put("X-Goog-PageId", it) }
        }
    }

    /**
     * The `Authorization` value for [cookie] at [origin], or null when the jar holds no signing
     * secret.
     */
    fun signatureFor(cookie: String, origin: String): String? = SapisidAuth.authorization(cookie, origin)

    /** `onBehalfOfUser`, which rides in the request body rather than a header. */
    fun onBehalfOfUser(): String? = session?.dataSyncId?.takeIf { it.isNotBlank() }
}
