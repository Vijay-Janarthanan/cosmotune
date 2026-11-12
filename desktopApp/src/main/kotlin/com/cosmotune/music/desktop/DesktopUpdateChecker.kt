package com.cosmotune.music.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.cosmotune.ReleaseHighlight
import com.cosmotune.ReleaseHighlights

/**
 * Looks at the repo's "latest release" on GitHub once per launch and says whether it is newer
 * than the running build. The installer is not run from here: the dialog opens the asset in the
 * browser, so the user installs it the way they installed the first one.
 *
 * GitHub's "latest" skips pre-releases and drafts, so a `-beta` desktop build is only ever
 * nudged toward a published release.
 */
internal object DesktopUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        /** The installer for this platform, or null when the release has none. */
        val downloadUrl: String?,
        /** The release's whole notes, which the update card links to rather than shows. */
        val notes: String?,
        /** What the update card shows of [notes]: their Highlights, or an older release's first lines. */
        val highlights: List<ReleaseHighlight> = ReleaseHighlights.of(notes),
        /** The installer's size in bytes, when the release says. */
        val downloadSize: Long? = null,
        /** Every file of the release, for the one-click package (`DesktopUpdater.packageOf`). */
        val assets: List<ReleaseAsset> = emptyList(),
    )

    /** One uploaded file of a release: its [name], its download [url] and its [size] in bytes. */
    data class ReleaseAsset(val name: String, val url: String, val size: Long?)

    /** The running build's own release, for the "what's new" card on the first launch after an update. */
    data class WelcomeInfo(val version: String, val releaseUrl: String, val highlights: List<ReleaseHighlight>)

    private const val LATEST_RELEASE_URL = com.cosmotune.CosmoConfig.LATEST_RELEASE_API

    val currentVersion: String = System.getProperty("cosmotune.version") ?: "1.0.23"

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        engine { com.cosmotune.music.desktop.DesktopExtras.httpProxyUrl?.let { proxy = io.ktor.client.engine.ProxyBuilder.http(io.ktor.http.Url(it)) } }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun check(): UpdateInfo? = runCatching {
        val response = http.get(LATEST_RELEASE_URL) {
            header("Accept", "application/vnd.github+json")
        }
        if (!response.status.isSuccess()) return@runCatching null
        val release = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: return@runCatching null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val latest = tag.removePrefix("v")
        if (!isNewer(latest, currentVersion)) return@runCatching null
        val installer = installerUrl(release)
        UpdateInfo(
            version = latest,
            releaseUrl = url,
            downloadUrl = installer,
            notes = release["body"]?.jsonPrimitive?.contentOrNull,
            downloadSize = installer?.let { assetSize(release, it) },
            assets = assetsOf(release),
        )
    }.getOrNull()

    /** Where the last launched version is kept, so the first launch of a newer one can say what is new. */
    private const val KEY_SEEN_VERSION = "whats_new_seen_version"

    /**
     * The running build's "what's new", once per update: when the version last launched ([persistence]) is older than this one,
     * reads this version's own release and gives its highlights. A first launch with nothing recorded (a fresh install, or the
     * build before this card existed) only records the version. Null as well with no network, which leaves the record alone so
     * the next launch tries again; [welcomeSeen] records the version once the card is closed.
     */
    suspend fun checkWelcome(persistence: DesktopPersistence): WelcomeInfo? = runCatching {
        val seen = persistence.string(KEY_SEEN_VERSION)
        if (seen.isEmpty()) {
            persistence.saveString(KEY_SEEN_VERSION, currentVersion)
            return@runCatching null
        }
        if (!isNewer(currentVersion, seen)) return@runCatching null
        val response = http.get("https://api.github.com/repos/${com.cosmotune.CosmoConfig.PROJECT_REPO}/releases/tags/v$currentVersion") {
            header("Accept", "application/vnd.github+json")
        }
        if (response.status.value == 404) {
            persistence.saveString(KEY_SEEN_VERSION, currentVersion)
            return@runCatching null
        }
        if (!response.status.isSuccess()) return@runCatching null
        val release = json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return@runCatching null
        val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val highlights = ReleaseHighlights.of(release["body"]?.jsonPrimitive?.contentOrNull)
        if (highlights.isEmpty()) {
            persistence.saveString(KEY_SEEN_VERSION, currentVersion)
            return@runCatching null
        }
        WelcomeInfo(currentVersion, url, highlights)
    }.getOrNull()

    /** The welcome card was closed: this version is not welcomed again. */
    fun welcomeSeen(persistence: DesktopPersistence) = persistence.saveString(KEY_SEEN_VERSION, currentVersion)

    /** The release's uploaded files. */
    private fun assetsOf(release: JsonObject): List<ReleaseAsset> =
        release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
            .filter { it["state"]?.jsonPrimitive?.contentOrNull == "uploaded" }
            .mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ReleaseAsset(name, url, asset["size"]?.jsonPrimitive?.longOrNull)
            }

    /** The size in bytes the release gives for the asset downloaded from [url]. */
    private fun assetSize(release: JsonObject, url: String): Long? =
        release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it["browser_download_url"]?.jsonPrimitive?.contentOrNull == url }
            ?.get("size")?.jsonPrimitive?.longOrNull

    /**
     * The release names its files `Cosmotune-<version>-windows-x64-setup.exe` and
     * `Cosmotune-<version>-linux-x86_64.AppImage` (see .github/workflows/release.yml); match on the
     * platform part so a version change does not matter.
     */
    private fun installerUrl(release: JsonObject): String? {
        val suffixes = when {
            DesktopPlatform.isWindows -> listOf("-windows-x64-setup.exe")
            DesktopPlatform.isLinux -> listOf(".AppImage", "-linux-amd64.deb")
            else -> return null
        }
        val assets = release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        for (suffix in suffixes) {
            val hit = assets.firstOrNull { asset ->
                asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(suffix, ignoreCase = true) == true &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
            }
            hit?.get("browser_download_url")?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        return null
    }

    /**
     * Numeric comparison, with a `-betaN` build counted as older than the plain release it leads up to; the rule
     * is shared with the phone's `AppUpdateChecker` and tested by `ReleaseVersionTest`.
     */
    internal fun isNewer(latest: String, current: String): Boolean = com.cosmotune.ReleaseVersion.isNewer(latest, current)
}
