package com.cosmotune.youtube.account

/**
 * The few `ytcfg` values a YouTube page (www.youtube.com or music.youtube.com) states about itself, read by regex: the config is
 * one script assignment among hundreds of kilobytes of JavaScript, and these are flat values in it. A key that moved reads as null.
 *
 * @property loggedIn `LOGGED_IN`, or null when the page did not say (a consent page, an error page).
 * @property clientVersion `INNERTUBE_CLIENT_VERSION`: the client version the site's own page uses right now.
 */
data class YouTubePageConfig(
    val loggedIn: Boolean?,
    val clientVersion: String?,
    val visitorData: String?,
    val dataSyncId: String?,
    val delegatedSessionId: String?,
    val sessionIndex: String?,
) {
    companion object {
        fun parse(html: String): YouTubePageConfig = YouTubePageConfig(
            loggedIn = LOGGED_IN.find(html)?.groupValues?.get(1)?.let { it == "true" },
            clientVersion = CLIENT_VERSION.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            visitorData = VISITOR_DATA.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            dataSyncId = DATASYNC_ID.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            delegatedSessionId = PAGE_ID.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            sessionIndex = SESSION_INDEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
        )

        /**
         * Whether [version] is a WEB client version (`2.YYYYMMDD.NN.NN`). The page holds the versions of other clients too, and a
         * Music version (`1.…`) claimed as WEB would be a client Google has never shipped.
         */
        fun isWebVersion(version: String?): Boolean = version != null && WEB_VERSION.matches(version)

        private val LOGGED_IN = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
        private val CLIENT_VERSION = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")
        private val VISITOR_DATA = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
        private val DATASYNC_ID = Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""")
        private val PAGE_ID = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""")
        private val SESSION_INDEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""")
        private val WEB_VERSION = Regex("""2\.\d{8}\.\d{2}\.\d{2}""")
    }
}
