package com.cosmotune.manga.extensions

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.cosmotune.manga.data.*
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.ConfigurableSource
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.JavaScriptEngine
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.serialization.XML

data class MangaExtensionReview(
    val packageName: String, val name: String, val version: String, val sha256: String,
    val signers: List<String>, val classes: List<String>, val stagedFile: File, val versionCode: Long,
)

/** Importing reads metadata only. No extension class is initialized before the explicit trust action. */
class MangaExtensions(private val application: Context, private val store: MangaStore, private val context: ParserContext) {
    private val directory = File(store.root, "extensions").apply { mkdirs() }
    private val loaded = LinkedHashMap<String, List<Source>>()

    suspend fun inspect(open: () -> InputStream): MangaExtensionReview = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("review-", ".apk", directory)
        try {
            open().use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var size = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= 64L * 1024 * 1024) { "Extension exceeds the 64 MB limit" }
                    output.write(buffer, 0, count)
                }
            } }
            val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS or
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val info = application.packageManager.getPackageArchiveInfo(temporary.path, flags) ?: error("This is not a readable Android extension APK")
            val app = info.applicationInfo ?: error("Extension application metadata is missing")
            val metadata = app.metaData ?: error("Extension source metadata is missing")
            require(info.reqFeatures.orEmpty().any { it.name == "tachiyomi.extension" }) { "This APK is not a manga source extension" }
            val classes = listOfNotNull(metadata.getString("tachiyomi.extension.class"), metadata.getString("tachiyomi.extension.factory"))
                .flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require(classes.isNotEmpty() && classes.size <= 128) { "No compatible source entry point was found" }
            val version = info.versionName.orEmpty()
            val libraryVersion = if (metadata.containsKey("tachiyomix.extensionLib"))
                String.format(java.util.Locale.ROOT, "%.1f", metadata.getFloat("tachiyomix.extensionLib"))
                else version.split('.').take(2).joinToString(".")
            require(libraryVersion in setOf("1.4", "1.6")) { "Unsupported extension API version: $libraryVersion" }
            val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let { signing ->
                if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
            }.orEmpty().toList()
                else @Suppress("DEPRECATION") info.signatures.orEmpty().toList()
            require(signers.isNotEmpty()) { "The APK has no readable signer certificate" }
            app.sourceDir = temporary.path; app.publicSourceDir = temporary.path
            val name = runCatching { application.packageManager.getApplicationLabel(app).toString() }.getOrDefault(info.packageName)
            MangaExtensionReview(info.packageName, metadata.getString("tachiyomix.name") ?: name, version, digest(temporary),
                signers.map { hash(it.toByteArray()) }, classes, temporary, androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info))
        } catch (failure: Throwable) { temporary.delete(); throw failure }
    }

    suspend fun trust(review: MangaExtensionReview): List<MangaSourceConfig> = withContext(Dispatchers.IO) { sourceApi {
        require(review.stagedFile.canonicalFile.parentFile == directory.canonicalFile)
        require(digest(review.stagedFile) == review.sha256) { "The reviewed APK changed; import it again" }
        val previous = store.state.value.sources.firstOrNull { it.kind == "mihon" && it.settings["package"] == review.packageName }
        if (previous != null) {
            require(previous.settings["signers"].orEmpty().split(',').all { it in review.signers }) {
                "The signer differs from the installed extension. Remove it before switching publishers."
            }
            require(review.versionCode >= (previous.settings["versionCode"]?.toLongOrNull() ?: 0L)) { "A newer extension version is already installed" }
        }
        val target = File(directory, "${review.sha256}.apk")
        if (target.exists()) review.stagedFile.delete() else MangaArchive.move(review.stagedFile, target)
        check(target.setReadOnly()) { "Cannot protect the imported APK from changes" }
        val settings = mapOf("package" to review.packageName, "sha256" to review.sha256,
            "signers" to review.signers.joinToString(","), "classes" to review.classes.joinToString(";"), "version" to review.version,
            "versionCode" to review.versionCode.toString())
        val prototypes = load(settings)
        require(prototypes.isNotEmpty()) { "The extension contains no supported catalogue sources" }
        val configs = prototypes.map { raw -> MangaSourceConfig("mihon:${review.packageName}:${raw.id}", "mihon", raw.name, raw.lang,
            settings = settings + mapOf("sourceId" to raw.id.toString(), "configurable" to (raw is ConfigurableSource).toString())) }
        val installed = configs.map { config -> config.copy(enabled = store.state.value.sources.find { it.id == config.id }?.enabled ?: true) }
        store.update { old -> old.copy(sources = old.sources.filterNot { it.kind == "mihon" && it.settings["package"] == review.packageName } + installed) }
        installed
    } }

    suspend fun inspectDownload(listing: MangaExtensionListing, publisher: MangaExtensionStore): MangaExtensionReview = withContext(Dispatchers.IO) {
        val address = listing.apkUrl.toHttpUrl()
        require(address.isHttps && address.username.isEmpty() && address.password.isEmpty())
        transferClient.newCall(okhttp3.Request.Builder().url(address).build()).execute().use { response ->
            currentCoroutineContext().ensureActive()
            check(response.isSuccessful) { "Extension download returned HTTP ${response.code}" }
            require(response.body.contentLength() <= 64L * 1024 * 1024) { "Extension exceeds the 64 MB limit" }
            val reviewed = inspect { response.body.byteStream() }
            try {
                require(reviewed.packageName == listing.packageName && reviewed.versionCode == listing.versionCode) { "Downloaded APK identity/version differs from the store entry" }
                require(publisher.signingKey in reviewed.signers) { "Downloaded APK is not signed by this store's publisher" }
                reviewed
            } catch (failure: Throwable) { reviewed.stagedFile.delete(); throw failure }
        }
    }

    fun source(config: MangaSourceConfig): MangaSource {
        val wanted = config.settings.getValue("sourceId").toLong()
        val source = load(config.settings).firstOrNull { it.id == wanted }
            ?: error("This source is no longer provided by the installed extension")
        return MihonSource(config, source, context.httpClient)
    }

    fun preferences(config: MangaSourceConfig): ConfigurableSource = load(config.settings)
        .filterIsInstance<ConfigurableSource>().firstOrNull { it.id.toString() == config.settings["sourceId"] }
        ?: error("This extension does not provide source settings")

    @Synchronized private fun load(settings: Map<String, String>): List<Source> {
        val sha = settings.getValue("sha256")
        require(sha.matches(Regex("[0-9a-f]{64}")))
        loaded[sha]?.let { return it }
        val file = File(directory, "$sha.apk")
        require(file.isFile && digest(file) == sha) { "Extension is missing or changed; import and review it again" }
        initializeBridge(application, context)
        val loader = PathClassLoader(file.path, MangaExtensions::class.java.classLoader)
        val packageName = settings.getValue("package")
        val sources = settings.getValue("classes").split(';').flatMap { name ->
            val full = if (name.startsWith('.')) packageName + name else name
            require(full.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) { "Invalid extension entry point" }
            try {
                val type = loader.loadClass(full)
                // R8 may rename the entry outside the Android application ID. Its code must still
                // come from the reviewed APK; a parent-first lookup must not instantiate host classes.
                require(type.classLoader === loader) { "Extension entry point is not defined by its reviewed APK" }
                val instance = try { type.getConstructor().newInstance() }
                    catch (_: NoSuchMethodException) { type.getField("INSTANCE").get(null) }
                when (instance) { is Source -> listOf(instance); is SourceFactory -> instance.createSources(); else -> error("Unrecognized extension entry point") }
            } catch (error: LinkageError) {
                throw IllegalStateException("This extension requires an unsupported source API: ${error.javaClass.simpleName}", error)
            } catch (error: ReflectiveOperationException) {
                throw IllegalStateException("The extension could not be initialized", error)
            }
        }
        require(sources.size <= 512) { "Extension declares too many sources" }
        loaded[sha] = sources
        while (loaded.size > 8) loaded.remove(loaded.keys.first())
        return sources
    }

    suspend fun remove(packageName: String) = withContext(Dispatchers.IO) {
        val old = store.state.value.sources.filter { it.kind == "mihon" && it.settings["package"] == packageName }
        store.update { it.copy(sources = it.sources - old.toSet()) }
        old.mapNotNull { it.settings["sha256"] }.distinct().forEach { sha ->
            loaded.remove(sha)
            if (sha.matches(Regex("[0-9a-f]{64}"))) File(directory, "$sha.apk").delete()
        }
    }

    companion object {
        private val transferClient = OkHttpClient.Builder().followSslRedirects(false)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
        private var bridgeReady = false
        private lateinit var active: ParserContext
        private lateinit var extensionClient: OkHttpClient
        @Synchronized private fun initializeBridge(context: Context, parser: ParserContext) {
            active = parser
            extensionClient = parser.httpClient.newBuilder().apply {
                cookieJar(AndroidCookieJar(parser.cookieJar))
                interceptors().add(0, eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor())
                interceptors().add(1, eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor { ParserContext.USER_AGENT })
                interceptors().add(2, eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor())
            }.build()
            AndroidCookieJar.defaultSourceJar = parser.cookieJar
            if (bridgeReady) return
            val helper = object : NetworkHelper() {
                override val client get() = extensionClient
                override fun defaultUserAgentProvider() = ParserContext.USER_AGENT
            }
            val preferenceContext = SourcePreferenceApplication(context.applicationContext as Application)
            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    addSingleton(preferenceContext as Application)
                    addSingletonFactory<Context> { preferenceContext }
                    addSingletonFactory<NetworkHelper> { helper }
                    addSingletonFactory<OkHttpClient> { extensionClient }
                    addSingletonFactory<CookieJar> { active.cookieJar }
                    addSingletonFactory<JavaScriptEngine> { JavaScriptEngine(context.applicationContext) }
                    addSingletonFactory<ProtoBuf> { ProtoBuf }
                    addSingletonFactory<XML> { XML { defaultPolicy { ignoreUnknownChildren() } } }
                    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
                    addSingletonFactory<Json> { json }; addSingletonFactory<StringFormat> { json }; addSingletonFactory<SerialFormat> { json }
                }
            })
            bridgeReady = true
        }
        private fun digest(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
