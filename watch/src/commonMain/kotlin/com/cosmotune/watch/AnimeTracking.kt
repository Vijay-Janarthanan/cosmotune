package com.cosmotune.watch

import kotlinx.coroutines.flow.StateFlow

/**
 * The anime list services a viewer can keep up to date. Each needs the app registered with it once, which gives a public
 * client id (no secret); [redirect] is the return address that registration names.
 */
enum class AnimeTracker(val label: String, val registerUrl: String, val redirect: String) {
    ANILIST("AniList", "https://anilist.co/settings/developer", "cosmotune://watch/tracker/anilist"),
    MYANIMELIST("MyAnimeList", "https://myanimelist.net/apiconfig", "cosmotune://watch/tracker/myanimelist"),
}

data class AnimeTrackingStatus(
    val service: AnimeTracker,
    /** The public client id saved on this device; sign-in is offered once there is one. */
    val clientId: String? = null,
    val connected: Boolean = false,
    /** The account's name on the service. */
    val user: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * AniList and MyAnimeList for anime: the viewer signs in in their browser (MyAnimeList with PKCE, AniList with its
 * secret-free implicit sign-in), and each anime episode they finish moves that show's progress on their list forward. It
 * never moves a list backwards, and an episode MyAnimeList's entry cannot be matched to is left alone.
 */
interface AnimeTracking {
    val statuses: StateFlow<List<AnimeTrackingStatus>>
    suspend fun setClientId(service: AnimeTracker, clientId: String)
    /** The sign-in page to open in the browser, or null without a client id. Each call starts a new sign-in. */
    fun signInUrl(service: AnimeTracker): String?
    /** True when [link] is the service sending the viewer back (the [AnimeTracker.redirect] address). */
    fun isReturn(link: String): Boolean = AnimeTracker.entries.any { link.trim().startsWith(it.redirect) }
    /** Finishes the sign-in [link] returns from; says how it went in the listener's words. */
    suspend fun finish(link: String): String
    suspend fun disconnect(service: AnimeTracker)
}
