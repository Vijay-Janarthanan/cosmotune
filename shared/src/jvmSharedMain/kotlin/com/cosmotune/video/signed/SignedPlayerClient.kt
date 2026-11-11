package com.cosmotune.video.signed

private const val TV_OLD_VERSION = "5.20260707"

/**
 * One client the player endpoint can be asked as for a signed Video request, with what the request and the media fetches must say
 * for it. The order and the notes on which clients broke follow Prism's `InnerTube.PlayerClient` and YTXTube's `PlayerFallback`
 * (both GPL-3.0), which track what yt-dlp sends for signed-in users.
 *
 * @param takesCookies false for an app client, which cannot use a cookie session: it is sent with no credential at all.
 * @param needsPoToken its links stop after the first megabyte without the session-bound token.
 * @param browser native apps send no Origin and no Referer; sending them anyway is as wrong as omitting them from a web client.
 * @param lastResort asked only when every other client of its list has failed, never raced with them.
 */
internal enum class SignedPlayerClient(
    val clientName: String,
    val nameId: Int,
    val version: String?,
    val userAgent: String,
    val extra: Map<String, String> = emptyMap(),
    val takesCookies: Boolean = true,
    val needsPoToken: Boolean = false,
    val embedded: Boolean = false,
    val browser: Boolean = true,
    val lastResort: Boolean = false,
) {
    /**
     * The older TV app as a Samsung TV: the first TV client for signed-in users since 2026-10, when the plain older one began to answer
     * "The page needs to be reloaded" for most videos. Ciphered links, no PoToken.
     */
    TV_SAMSUNG(
        clientName = "TVHTML5",
        nameId = 7,
        version = TV_OLD_VERSION,
        userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
        extra = mapOf("deviceMake" to "Samsung", "deviceModel" to "UKS9800", "osName" to "Tizen", "osVersion" to "2.4.0"),
    ),

    /** The embedded player: takes the cookies and needs no PoToken. A video whose owner turned embedding off answers no, and the next client asks. */
    WEB_EMBEDDED(
        clientName = "WEB_EMBEDDED_PLAYER",
        nameId = 56,
        version = "2.20260708.00.00",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)",
        embedded = true,
    ),

    /** The older TV app: plain ciphered links for age-restricted and members' videos, no PoToken. */
    TV_OLD(
        clientName = "TVHTML5",
        nameId = 7,
        version = TV_OLD_VERSION,
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
    ),

    /** The site itself, at the live version: answers everything the account may see, but its links need the session-bound token. */
    WEB(
        clientName = "WEB",
        nameId = 1,
        version = null,
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
        needsPoToken = true,
    ),

    /** The iPhone app, no credential: direct links. Only for what NewPipe could not read. */
    IOS(
        clientName = "IOS",
        nameId = 5,
        version = "21.29.1",
        userAgent = "com.google.ios.youtube/21.29.1 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)",
        extra = mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.5.0.22F76"),
        takesCookies = false,
        browser = false,
    ),

    /**
     * The VR app, no credential: direct links, but in 2026-10 googlevideo refuses them past the first megabyte for some sessions, so
     * it is the very last thing tried.
     */
    ANDROID_VR(
        clientName = "ANDROID_VR",
        nameId = 28,
        version = "1.65.10",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        extra = mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android", "osVersion" to "12L", "androidSdkVersion" to "32"),
        takesCookies = false,
        browser = false,
        lastResort = true,
    );

    companion object {
        /** What a signed-in request tries before anonymous NewPipe, in order. */
        val SIGNED: List<SignedPlayerClient> = listOf(TV_SAMSUNG, WEB_EMBEDDED, TV_OLD, WEB)

        /** What is tried, in order, when the signed clients and NewPipe have all failed. */
        val RESCUE: List<SignedPlayerClient> = listOf(IOS, ANDROID_VR)
    }
}
