package com.cosmotune.music.data

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.cosmotune.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.cosmotune.ReleaseHighlight
import com.cosmotune.ReleaseHighlights
import okhttp3.Request
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread

/**
 * Cosmotune ships as a sideloaded APK off GitHub Releases rather than through
 * a store, so there's nothing to push an update notice on its own — this
 * polls the repo's "latest release" once per launch and compares its tag
 * against the running build.
 *
 * The update itself is also handled here: the release's `.apk` asset is
 * downloaded into the app's cache and handed to the system package installer,
 * so the whole round trip stays inside the app instead of bouncing out to a
 * browser.
 */
object AppUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        /** The release's own Markdown body: the full notes, which the update card links to rather than shows. */
        val notes: String?,
        /** What the update card shows of [notes]: their Highlights, or an older release's first lines ([ReleaseHighlights]). */
        val highlights: List<ReleaseHighlight> = ReleaseHighlights.of(notes),
        /** The size in bytes of the APK at [apkUrl], when the release says. */
        val apkSize: Long? = null,
    )

    /** The release the running build came from, for the "what's new" card on the first launch after an update. */
    data class WelcomeInfo(
        val version: String,
        val releaseUrl: String,
        val highlights: List<ReleaseHighlight>,
    )

    private const val CACHE_SUBDIR = "updates"

    private const val LATEST_RELEASE_URL = com.cosmotune.CosmoConfig.LATEST_RELEASE_API

    /** The release published for one tag (`v1.0.21`): where the welcome card reads the running build's own notes. */
    private fun releaseForTagUrl(version: String) =
        "https://api.github.com/repos/${com.cosmotune.CosmoConfig.PROJECT_REPO}/releases/tags/v$version"

    /** The welcome card's own small store: whether the first launch after an update still owes the listener one. */
    private const val WELCOME_PREFS = "cosmotune_whats_new"
    private const val KEY_WELCOME_PENDING = "welcome_pending"

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    /** Where this update's APK download currently stands, for the dialog's progress row. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _welcome = MutableStateFlow<WelcomeInfo?>(null)

    /** The running build's "what's new", set once per update on its first launch (see [markUpdated]) until it is seen. */
    val welcome = _welcome.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    /** Set from the UI thread when the user cancels; polled between network reads. */
    @Volatile
    private var downloadCancelled = false

    suspend fun check() = withContext(Dispatchers.IO) {
        lastAttemptMs = System.currentTimeMillis()
        runCatching {
            val request = Request.Builder().url(LATEST_RELEASE_URL).build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            } ?: return@runCatching
            val release = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
            val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
            val apkUrl = apkAssetUrl(release)
            val notes = release["body"]?.jsonPrimitive?.contentOrNull
            val latest = tag.removePrefix("v")
            if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                _available.value = UpdateInfo(latest, url, apkUrl, notes, apkSize = apkUrl?.let { assetSize(release, it) })
            }
        }
    }

    /**
     * Called from the application's start when the installed versionCode has risen since the last launch (an update, never a
     * fresh install): the next [checkWelcome] owes the listener the new build's "what's new".
     */
    fun markUpdated(context: Context) {
        context.getSharedPreferences(WELCOME_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WELCOME_PENDING, true).apply()
    }

    /**
     * When an update is owed its welcome, reads the running build's own release and sets [welcome] to its highlights. The debt
     * stays until [welcomeSeen], so a launch without the network tries again next time; a build that has no release (a beta, a
     * local build) or whose notes give nothing to show settles it quietly.
     */
    suspend fun checkWelcome(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(WELCOME_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_WELCOME_PENDING, false)) return@withContext
        val version = BuildConfig.VERSION_NAME
        runCatching {
            val request = Request.Builder().url(releaseForTagUrl(version)).build()
            Http.client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    prefs.edit().putBoolean(KEY_WELCOME_PENDING, false).apply()
                    return@runCatching
                }
                if (!response.isSuccessful) return@runCatching
                val release = json.parseToJsonElement(response.body?.string() ?: return@runCatching) as? JsonObject ?: return@runCatching
                val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                val highlights = ReleaseHighlights.of(release["body"]?.jsonPrimitive?.contentOrNull)
                if (highlights.isEmpty()) prefs.edit().putBoolean(KEY_WELCOME_PENDING, false).apply()
                else _welcome.value = WelcomeInfo(version, url, highlights)
            }
        }
        Unit
    }

    /** The welcome card was closed: it is not shown again for this build. */
    fun welcomeSeen(context: Context) {
        _welcome.value = null
        context.getSharedPreferences(WELCOME_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WELCOME_PENDING, false).apply()
    }

    /** The size in bytes the release gives for the asset downloaded from [url]. */
    private fun assetSize(release: JsonObject, url: String): Long? = runCatching {
        release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it["browser_download_url"]?.jsonPrimitive?.contentOrNull == url }
            ?.get("size")?.jsonPrimitive?.longOrNull
    }.getOrNull()

    /**
     * Wipes any APK left over from a previous run. Called once at cold start
     * so a downloaded update is only ever "Install Now" for the session that
     * downloaded it — the next launch starts clean rather than trying to work
     * out whether a leftover file is still good.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * A Cosmotune release carries four `.apk` files (one per CPU family and a
     * universal one), an older release may carry a single one. Take the direct
     * download URL of the one that fits this phone: [com.cosmotune.ApkPicker]
     * walks `Build.SUPPORTED_ABIS` in the phone's own order, then falls back to
     * the universal file, then to the first `.apk`. A release without any
     * (source-only draft, renamed asset) leaves [UpdateInfo.apkUrl] null and
     * the UI falls back to opening the releases page as before.
     */
    private fun apkAssetUrl(release: JsonObject): String? = runCatching {
        val apks = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.filter { asset ->
                asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
            }
            ?.mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                if (name != null && url != null) name to url else null
            }
            .orEmpty()
        com.cosmotune.ApkPicker.choose(apks, Build.SUPPORTED_ABIS.toList(),
            target = if (BuildConfig.APPLICATION_ID.startsWith("com.cosmotune.tv")) com.cosmotune.ApkPicker.Target.TV else com.cosmotune.ApkPicker.Target.PHONE)
    }.getOrNull()

    /**
     * Streams the current update's APK into the app cache, reporting progress
     * through [download]. A finished file survives a cancelled dialog: until
     * the state is reset, "Install Now" comes straight back without a second
     * download.
     */
    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = _available.value ?: return@withContext
        val url = info.apkUrl ?: return@withContext
        downloadCancelled = false
        _download.value = DownloadState.Downloading(0f)

        runCatching {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            // Drop anything left over from an earlier attempt.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "cosmotune-${info.version}.apk")

            val request = Request.Builder().url(url).build()
            Http.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            if (downloadCancelled) {
                                _download.value = DownloadState.Idle
                                return@withContext
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            total?.let {
                                _download.value =
                                    DownloadState.Downloading((readTotal.toFloat() / it).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            _download.value = if (downloadCancelled) {
                DownloadState.Idle
            } else {
                DownloadState.Failed(error.message ?: "Download failed")
            }
        }
    }

    /** Stops an in-flight download; the next read loop sees this and bails. */
    fun cancelDownload() {
        downloadCancelled = true
    }

    /** Back to square one after a failure, so the dialog offers Download again. */
    fun resetDownload() {
        _download.value = DownloadState.Idle
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Sideloaded apps need the user's blessing per app ("install unknown apps");
     * without it the installer intent silently does nothing on most ROMs, so
     * the user is sent to that one switch first and taps Install again after.
     */
    /**
     * Hands the downloaded APK to Android through a [PackageInstaller] session. From Android 12 the session asks for no user action,
     * so once Cosmotune has installed itself this way, later updates go in without the "Install?" screen; the first one (and any
     * the system still wants confirmed) shows it through [UpdateInstallReceiver]. A failure comes back as [DownloadState.Failed].
     */
    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val app = context.applicationContext
        // The copy into the session is the APK's whole size: off the UI thread.
        thread(name = "cosmotune-update-install") {
            runCatching {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    if (sessionRequiresNoUserAction(Build.VERSION.SDK_INT)) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("cosmotune.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val status = PendingIntent.getBroadcast(
                        app, id,
                        Intent(app, UpdateInstallReceiver::class.java).setPackage(app.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(status.intentSender)
                }
            }.onFailure { reportInstallFailure(it.message ?: "The update was not installed.") }
        }
    }

    /** Android 12 (API 31) is the first that lets an app update itself without the confirmation screen. */
    internal fun sessionRequiresNoUserAction(apiLevel: Int): Boolean = apiLevel >= 31

    /** The words for a finished session's [status], or null when it worked or is waiting for the person. */
    internal fun installStatusFailure(status: Int, message: String?): String? = when (status) {
        PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_PENDING_USER_ACTION -> null
        else -> message?.takeIf { it.isNotBlank() } ?: "The update was not installed."
    }

    /** The install did not go through: the card shows why, with Try again. */
    fun reportInstallFailure(message: String) {
        _download.value = DownloadState.Failed(message)
    }

    /** When the last check was tried; a failed one counts (see [com.cosmotune.update.UpdateCheckSchedule]). */
    @Volatile
    internal var lastAttemptMs: Long? = null

    internal fun isCheckDue(nowMs: Long, trigger: com.cosmotune.update.UpdateTrigger): Boolean =
        com.cosmotune.update.UpdateCheckSchedule.shouldCheck(nowMs, lastAttemptMs, trigger)

    /**
     * Looks again whenever one of the app's screens comes back to the front, when the schedule says so (at most once an hour).
     * Registered once from the Application, so the activities themselves stay untouched.
     */
    fun watchForeground(app: android.app.Application) {
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch {
                    checkIfDue(com.cosmotune.update.UpdateTrigger.RETURNED)
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    /** The app came back to the front: look again when the schedule says so. */
    suspend fun checkIfDue(trigger: com.cosmotune.update.UpdateTrigger) {
        if (isCheckDue(System.currentTimeMillis(), trigger)) check()
    }

    /**
     * Numeric, dot-separated comparison — "1.10" outranks "1.9" — with one
     * extra rule: a "-betaN" build (see the debug build type's
     * `versionNameSuffix` in app/build.gradle.kts) is treated as older than a
     * plain release of the same numbers, since the beta by definition predates
     * the tag it was testing toward. Without this, a beta and the release it
     * matches compare equal and testers never get nudged onto the real build.
     * The rule is shared with the desktop's checker: [com.cosmotune.ReleaseVersion], tested by `ReleaseVersionTest`.
     */
    private fun isNewer(latest: String, current: String): Boolean = com.cosmotune.ReleaseVersion.isNewer(latest, current)
}
