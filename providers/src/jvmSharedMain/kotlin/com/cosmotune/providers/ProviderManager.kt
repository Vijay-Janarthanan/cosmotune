package com.cosmotune.providers

import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Pinned package lifecycle and provider metadata, independent of platform UI and audio decoders. */
class ProviderManager(val store: ProviderStore, private val artwork: CatalogueArtwork? = null,
    private val remember: (com.cosmotune.music.data.model.Song) -> Unit = {}) : AutoCloseable {
    val packages = ProviderPackages(File(store.root, "staging"))
    val repositories = ProviderRepositories(packages)
    private val archiveRoot = File(store.root, "packages").apply { check(isDirectory || mkdirs()); ownerOnly() }
    private val installation = Mutex()
    private val providerLocks = ConcurrentHashMap<String, Mutex>()
    private val capacity = Semaphore(MAX_RUNTIMES)
    private val cacheLock = Any()
    private data class Runtime(val key: String, val session: ProviderScriptSession, val host: ProviderMetadataHost, var busy: Boolean = false)
    private val runtimes = LinkedHashMap<String, Runtime>(4, .75f, true)
    private var creating = 0
    private class EnableIntent(val enabled: Boolean)
    private val enableIntents = ConcurrentHashMap<String, EnableIntent>()
    private val revokedNames = ConcurrentHashMap.newKeySet<String>()
    private val startingHosts = ConcurrentHashMap<String, ProviderMetadataHost>()
    @Volatile private var closed = false
    private val mutableErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = mutableErrors.asStateFlow()
    private val mutableProgress = MutableStateFlow<Map<String, ProviderWorkProgress>>(emptyMap())
    val progress = mutableProgress.asStateFlow()
    private data class HomeCache(val sections: List<ProviderHomeSection>, val fetchedAt: Long, val bytes: Int)
    private val homes = LinkedHashMap<String, HomeCache>(16, .75f, true)
    private val mediaDelegate = lazy { ProviderMediaProxy() }
    private val media by mediaDelegate
    private val browserCallbacks = ProviderLoopbackAuth()
    @Volatile private var nativeCallbackAdmission: (String) -> Boolean = { false }
    fun configureNativeAuthentication(admitted: (String) -> Boolean, defaultCallback: String? = null) {
        nativeCallbackAdmission = admitted
        if (defaultCallback != null) {
            require(admitted(defaultCallback)) { "The host must register its default provider callback" }
            store.defaultSessionCallback = defaultCallback
        }
    }

    suspend fun inspect(open: () -> InputStream) = packages.inspect(open)

    fun discard(review: ProviderPackageReview) {
        val staging = File(store.root, "staging").canonicalFile
        if (review.file.canonicalFile.parentFile == staging && review.file.name.startsWith("review-") && review.file.extension == "sflx") review.file.delete()
    }

    /** The UI must show identity/hash/network permissions and obtain the user's trust before calling this. */
    suspend fun trust(review: ProviderPackageReview, repositoryUrl: String? = null, allowArtwork: Boolean? = null): ProviderInstall = installation.withLock {
        withContext(Dispatchers.IO) {
            val verified = packages.verify(review)
            check(!closed) { "Provider manager is closed" }
            providerLocks.computeIfAbsent(verified.manifest.name) { Mutex() }.withLock {
            val previous = store.state.value.installed.find { it.manifest.name == verified.manifest.name }
            require(previous == null || compareVersions(verified.manifest.version, previous.manifest.version) >= 0) { "A newer provider version is installed" }
            val target = archive(verified.sha256)
            val created = !target.exists()
            if (target.exists()) require(ProviderPackages.digest(target) == verified.sha256) { "Installed package is damaged" }
            else {
                val temporary = File.createTempFile("package-", ".part", archiveRoot)
                try {
                    temporary.ownerOnly()
                    verified.file.copyTo(temporary, overwrite = true)
                    require(ProviderPackages.digest(temporary) == verified.sha256) { "The reviewed provider package changed; import it again" }
                    ProviderStore.move(temporary, target)
                } finally { temporary.delete() }
            }
            check(target.setReadOnly()) { "Cannot protect installed provider code" }
            val installed = ProviderInstall(verified.manifest, verified.sha256, previous?.enabled ?: true, repositoryUrl,
                previous?.installedAtMs ?: System.currentTimeMillis(), previous?.allowLocalNetwork ?: false,
                allowArtwork ?: previous?.allowArtwork ?: false)
            var accepted = installed
            try { store.update { old ->
                val latest = old.installed.find { it.manifest.name == installed.manifest.name }
                accepted = installed.copy(enabled = (latest?.enabled ?: installed.enabled) && installed.manifest.name !in revokedNames)
                old.copy(installed = old.installed.filterNot { it.manifest.name == installed.manifest.name } + accepted)
            } }
            catch (error: Throwable) { if (created) removeUnusedArchive(verified.sha256); throw error }
            verified.file.takeIf { it != target }?.delete()
            browserCallbacks.stop(installed.manifest.name)
            retire(installed.manifest.name)
            previous?.sha256?.takeIf { it != installed.sha256 }?.let { removeUnusedArchive(it) }
            accepted
            }
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean) {
        check(!closed) { "Provider manager is closed" }
        val intent = EnableIntent(enabled)
        synchronized(cacheLock) {
            enableIntents[name] = intent
            if (!enabled) revokedNames.add(name)
        }
        if (!enabled) {
            browserCallbacks.stop(name)
            retire(name, onlyIf = intent)
            // Revoke first: waiting for a long provider call would leave its sockets and writes live.
            store.update { old -> if (enableIntents[name] !== intent) old else old.copy(
                installed = old.installed.map { if (it.manifest.name == name) it.copy(enabled = false) else it }) }
            return
        }
        providerLocks.computeIfAbsent(name) { Mutex() }.withLock {
            store.update { old -> if (enableIntents[name] !== intent) old else old.copy(
                installed = old.installed.map { if (it.manifest.name == name) it.copy(enabled = true) else it }) }
            synchronized(cacheLock) { if (enableIntents[name] === intent) revokedNames.remove(name) }
            retire(name, onlyIf = intent)
        }
    }

    suspend fun saveSettings(name: String, values: JsonObject, allowLocal: Boolean? = null, allowArtwork: Boolean? = null) {
        providerLocks.computeIfAbsent(name) { Mutex() }.withLock {
            browserCallbacks.stop(name)
            val installed = requireNotNull(store.state.value.installed.find { it.manifest.name == name })
            val effective = installed.manifest.effectiveSettings(values)
            val storeSettingsBeforeSave = store.state.value.settings[name].orEmpty()
            store.update { old -> old.copy(settings = old.settings + (name to effective),
                installed = old.installed.map { if (it.manifest.name == name) it.copy(
                    allowLocalNetwork = allowLocal ?: it.allowLocalNetwork, allowArtwork = allowArtwork ?: it.allowArtwork) else it }) }
            if (effective != installed.manifest.effectiveSettings(storeSettingsBeforeSave) || allowLocal != null && allowLocal != installed.allowLocalNetwork) retire(name)
            else synchronized(cacheLock) { homes.keys.filter { it.startsWith("$name|") }.forEach { homes.remove(it) } }
            artwork?.cancel("provider:$name")
        }
    }

    suspend fun remove(name: String, forgetConnection: Boolean = true) = installation.withLock {
        providerLocks.computeIfAbsent(name) { Mutex() }.withLock {
            browserCallbacks.stop(name)
            val installed = store.state.value.installed.find { it.manifest.name == name } ?: return@withLock
            retire(name)
            if (forgetConnection) ProviderCookieJar.clear(store, name)
            store.update { old -> old.copy(installed = old.installed.filterNot { it.manifest.name == name },
                settings = old.settings - name, storage = old.storage - name,
                credentials = if (forgetConnection) old.credentials - name else old.credentials,
                auth = if (forgetConnection) old.auth - name else old.auth) }
            revokedNames.remove(name); enableIntents.remove(name)
            withContext(Dispatchers.IO) { removeUnusedArchive(installed.sha256); removeWorkspace(name) }
        }
    }

    suspend fun refreshRepository(address: String): ProviderRepositoryIndex {
        val index = repositories.fetch(address)
        store.update { old ->
            val title = index.name.ifBlank { java.net.URI(address).host.orEmpty() }
            old.copy(repositories = old.repositories.filterNot { it.address == address } + ProviderRepositoryConfig(address, title, index, System.currentTimeMillis()))
        }
        return index
    }

    fun authenticationUrl(name: String): String? {
        val installed = store.state.value.installed.find { it.manifest.name == name && it.enabled && name !in revokedNames } ?: return null
        val pending = store.state.value.auth[name]?.get("pending") as? JsonObject ?: return null
        val url = (pending["url"] as? JsonPrimitive)?.contentOrNull ?: return null
        val network = ProviderNetwork(installed.manifest.permissions, installed.allowLocalNetwork)
        return network.address(url).takeIf { it.isHttps }?.toString()
    }

    /** Opened only from a native user action; unsupported/busy redirects retain manual recovery. */
    suspend fun prepareBrowserAuthentication(name: String): ProviderBrowserAuthentication {
        var lease: ProviderBrowserAuthentication? = null
        try {
            return withContext(Dispatchers.IO) {
                check(!closed) { "Provider manager is closed" }
                val installed = requireNotNull(store.state.value.installed.find { it.manifest.name == name && it.enabled && name !in revokedNames })
                require(installed.manifest.permissions.storage) { "Provider credential storage permission denied" }
                val auth = requireNotNull(store.state.value.auth[name])
                val pending = requireNotNull(auth["pending"] as? JsonObject)
                require((pending["completed"] as? JsonPrimitive)?.booleanOrNull != true)
                val created = requireNotNull((pending["createdAt"] as? JsonPrimitive)?.longOrNull)
                val elapsed = System.currentTimeMillis() - created
                val lifetime = if (pending["kind"] == JsonPrimitive("signed-grant")) 180_000L else 600_000L
                require(elapsed in 0 until lifetime) { "Provider sign-in expired; cancel it and start again" }
                val address = requireNotNull(authenticationUrl(name))
                val epoch = auth["_epoch"]
                fun current(): Boolean {
                    val fresh = store.state.value
                    val selected = fresh.installed.find { it.manifest.name == name && it.enabled && name !in revokedNames }
                    val credentials = fresh.auth[name]
                    return !closed && selected != null && selected.sha256 == installed.sha256 && selected.manifest.permissions.storage &&
                        credentials?.get("_epoch") == epoch && credentials?.get("pending") == pending
                }
                val proof = pending["state"]?.jsonPrimitive?.content.orEmpty()
                val redirect = pending["callback"]?.jsonPrimitive?.content.orEmpty()
                val automatic = (pending["pkce"] == JsonPrimitive(true) || pending["kind"] == JsonPrimitive("signed-grant")) &&
                    (nativeCallbackAdmission(redirect) || browserCallbacks.prepare(name, redirect,
                        proof, lifetime - elapsed, ::current) { callback ->
                        check(current()) { "Provider sign-in changed" }
                        completeAuthentication(name, callback)
                    })
                ProviderBrowserAuthentication(address, automatic) { browserCallbacks.stop(name, proof) }.also { lease = it }
            }
        } catch (cancelled: CancellationException) { lease?.close(); throw cancelled }
    }

    /** Route an OS callback by one unpredictable pending state, then apply the full address/proof check. */
    suspend fun completeRoutedAuthentication(input: String): String? = withContext(Dispatchers.IO) {
        if (!nativeCallbackAdmission(input)) return@withContext null
        val proof = ProviderAuth.callbackState(input) ?: return@withContext null
        val current = store.state.value
        val candidates = current.installed.asSequence().filter { it.enabled && it.manifest.permissions.storage && it.manifest.name !in revokedNames }.filter { installed ->
            val pending = current.auth[installed.manifest.name]?.get("pending") as? JsonObject
            pending != null && pending["state"] == JsonPrimitive(proof) &&
                (pending["pkce"] == JsonPrimitive(true) || pending["kind"] == JsonPrimitive("signed-grant")) &&
                pending["completed"] != JsonPrimitive(true)
        }.take(2).toList()
        if (candidates.size != 1) return@withContext null
        val name = candidates.single().manifest.name
        try { completeAuthentication(name, input); name }
        catch (_: IllegalArgumentException) { null }
        catch (_: IllegalStateException) { null }
    }

    suspend fun completeAuthentication(name: String, callback: String) = withContext(Dispatchers.IO) {
        providerLocks.computeIfAbsent(name) { Mutex() }.withLock {
            check(!closed) { "Provider manager is closed" }
            val installed = requireNotNull(store.state.value.installed.find { it.manifest.name == name && it.enabled && name !in revokedNames }) { "Enable this provider first" }
            val network = ProviderNetwork(installed.manifest.permissions, installed.allowLocalNetwork)
            try { providerRuntimeWork(network::cancel) {
                if ((store.state.value.auth[name]?.get("pending") as? JsonObject)?.get("kind") == JsonPrimitive("signed-grant"))
                    ProviderSignedSession(installed, store, network).completeCallback(callback)
                else ProviderAuth(installed, store, network).completeCallback(callback)
            } }
            finally { network.cancel() }
            retire(name)
        }
    }
    suspend fun clearAuthentication(name: String) = withContext(Dispatchers.IO) {
        requireNotNull(store.state.value.installed.find { it.manifest.name == name })
        browserCallbacks.stop(name)
        // This is the native user's disconnect action, including after a package drops its storage grant.
        store.update { old -> old.copy(auth = old.auth + (name to buildJsonObject { put("_epoch", java.util.UUID.randomUUID().toString()) })) }
        ProviderCookieJar.clear(store, name)
        retire(name)
    }

    suspend fun search(name: String, query: String, limit: Int = 25): List<ProviderTrack> {
        val result = operation(name) { runtime ->
            val method = when {
                "searchTracks" in runtime.session.methods -> "searchTracks"
                "customSearch" in runtime.session.methods -> "customSearch"
                "search" in runtime.session.methods -> "search"
                else -> error("This provider does not offer search")
            }
            val args = buildJsonArray {
                add(query.take(500))
                if (method == "customSearch") add(buildJsonObject { put("limit", limit.coerceIn(1, 100)); put("filter", "tracks") })
                else add(limit.coerceIn(1, 100))
            }
            Json.parseToJsonElement(runtime.session.invoke(method, args))
        }
        return ProviderCatalogue.tracks(name, result).take(limit.coerceIn(1, 100)).map { artwork?.decorate("provider:$name", it) ?: it }
            .also { tracks -> tracks.forEach { remember(it.song()) } }
    }

    /**
     * The enabled metadata providers as sources of the Search tab's Add-ons source (BC-31). Each is asked through [search], so
     * the package's reviewed permissions, settings, sessions and network scope apply as to its other requests; a disabled or
     * revoked package is never asked.
     */
    fun addonSearchSources(): List<com.cosmotune.music.data.search.AddonSearchSource> =
        store.state.value.installed
            .filter { it.enabled && "metadata_provider" in it.manifest.type && it.manifest.name !in revokedNames }
            .map { installed ->
                val name = installed.manifest.name
                com.cosmotune.music.data.search.AddonSearchSource(installed.manifest.displayName.ifBlank { name }) { query, limit ->
                    search(name, query, limit).map { it.song() }
                }
            }

    suspend fun stream(name: String, trackId: String, maxKbps: Int? = null, lossless: Boolean = false, forDownload: Boolean = false): ProviderAudioStream = operation(name) { runtime ->
        require(trackId.isNotBlank() && trackId.length <= 8_192)
        val install = requireNotNull(store.state.value.installed.find { it.manifest.name == name && it.enabled && name !in revokedNames })
        require("download_provider" in install.manifest.type && "getDownloadUrl" in runtime.session.methods) {
            "This provider does not expose a direct audio stream"
        }
        val quality = ProviderAudioQuality.choose(install.manifest.qualityOptions, maxKbps, lossless)
        val raw = Json.parseToJsonElement(runtime.session.invoke("getDownloadUrl", buildJsonArray {
            add(trackId); add(quality); addJsonObject { put("purpose", if (forDownload) "download" else "playback") }
        })) as? JsonObject ?: error("Provider returned no direct audio stream")
        require(raw["success"] != JsonPrimitive(false)) { "Provider could not resolve this recording" }
        val url = (raw["url"] as? JsonPrimitive)?.contentOrNull ?: error("Provider returned no direct audio stream")
        val transport = ((raw["transport"] ?: raw["format"]) as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase(java.util.Locale.ROOT)
        val path = runCatching { java.net.URI(url).path.orEmpty().lowercase(java.util.Locale.ROOT) }.getOrDefault("")
        require(transport !in setOf("hls", "dash", "m3u8", "mpd") && !path.endsWith(".m3u8") && !path.endsWith(".mpd")) {
            "This provider requires a manifest-aware media transport"
        }
        val headers = raw["headers"] as? JsonObject ?: JsonObject(emptyMap())
        require(headers.size <= 100 && headers.values.all { it is JsonPrimitive && it.isString && it.content.length <= 8_192 })
        val codec = ((raw["audio_codec"] ?: raw["codec"] ?: raw["format"]) as? JsonPrimitive)?.contentOrNull
            ?.lowercase(java.util.Locale.ROOT)?.takeIf { it.matches(Regex("[a-z0-9_-]{1,40}")) }
        val kbps = (raw["quality"] as? JsonPrimitive)?.contentOrNull?.let(ProviderAudioQuality::bitrate)
        val container = ((raw["actual_extension"] ?: raw["format"]) as? JsonPrimitive)?.contentOrNull
            ?.lowercase(java.util.Locale.ROOT)?.takeIf { it in setOf("m4a", "mp4", "aac", "flac", "mp3", "ogg", "opus", "wav", "aiff", "dsf", "dff") }
        require(maxKbps == null || kbps != null && kbps <= maxKbps) { "Provider stream does not confirm the requested bitrate limit" }
        val network = runtime.host.network.fork()
        val local = try { synchronized(cacheLock) {
            check(!closed && name !in revokedNames && store.state.value.installed.any { it.manifest.name == name && it.enabled && it.sha256 == install.sha256 }) {
                "Provider media access changed"
            }
            media.register(name, url, headers, network)
        } } catch (error: Throwable) { network.cancel(); throw error }
        ProviderAudioStream(local, install.manifest.title, codec, kbps,
            (raw["track"] as? JsonObject)?.get("duration_ms")?.let { (it as? JsonPrimitive)?.longOrNull?.takeIf { duration -> duration in 1..86_400_000 } }, container)
    }

    suspend fun settingAction(name: String, key: String) {
        val installed = requireNotNull(store.state.value.installed.find { it.manifest.name == name })
        val field = installed.manifest.settings.firstOrNull { it.key == key && it.type == "button" } ?: error("Unknown provider action")
        val method = requireNotNull(field.action) { "Provider action is unavailable" }
        operation(name) { runtime ->
            require(method in runtime.session.methods) { "Provider action is unavailable" }
            runtime.session.invoke(method, JsonArray(emptyList()))
            retire(name)
        }
    }

    suspend fun catalogue(key: ProviderBrowseKey): ProviderCollection {
        require(key.namespace == "provider") { "This catalogue belongs to an HTTP add-on" }
        val result = operation(key.provider) { runtime ->
            val method = when (key.kind) { "artist" -> "getArtist"; "playlist" -> if ("getPlaylist" in runtime.session.methods) "getPlaylist" else "getAlbum"; else -> "getAlbum" }
            require(method in runtime.session.methods) { "This provider does not offer this catalogue page" }
            Json.parseToJsonElement(runtime.session.invoke(method, buildJsonArray { add(key.id) }))
        }
        val raw = (result as? JsonObject)?.let { (it[key.kind] as? JsonObject) ?: it } ?: error("Provider returned no catalogue page")
        val page = ProviderCatalogue.collection(key.provider, key.kind, raw, key.id) ?: error("Provider catalogue page is incomplete")
        return (artwork?.decorate("provider:${key.provider}", page) ?: page).also { it.tracks.forEach { track -> remember(track.song()) } }
    }

    suspend fun home(name: String, region: String, language: String, force: Boolean = false): List<ProviderHomeSection> {
        val installed = requireNotNull(store.state.value.installed.find { it.manifest.name == name && it.enabled && name !in revokedNames }) { "Enable this provider first" }
        require(installed.manifest.homeFeed) { "This provider does not offer a home feed" }
        val overrides = buildJsonObject {
            val setting = (installed.manifest.capabilities["homeFeedRegionSetting"] as? JsonPrimitive)?.contentOrNull
            if (setting != null) put(setting, region.uppercase(java.util.Locale.ROOT))
            installed.manifest.settings.firstOrNull { it.key in setOf("lang", "language", "locale") }?.let { put(it.key, language) }
        }
        val key = cacheKey(installed, overrides) + "|$region|$language"
        val cached = synchronized(cacheLock) { homes[key] }
        if (!force && cached != null && System.nanoTime() / 1_000_000 - cached.fetchedAt < HOME_TTL_MS) return cached.sections
        return try {
            val raw = operation(name, overrides) { runtime ->
                require("getHomeFeed" in runtime.session.methods) { "Provider advertises a home feed without its implementation" }
                runtime.session.invoke("getHomeFeed", JsonArray(emptyList()))
            }
            val sections = ProviderCatalogue.home(name, Json.parseToJsonElement(raw)).map { section -> section.copy(
                tracks = section.tracks.map { artwork?.decorate("provider:$name", it) ?: it },
                collections = section.collections.map { artwork?.decorate("provider:$name", it) ?: it }) }
                .also { pages -> pages.forEach { page -> page.tracks.forEach { remember(it.song()) } } }
            synchronized(cacheLock) {
                homes[key] = HomeCache(sections, System.nanoTime() / 1_000_000, raw.toByteArray().size)
                while (homes.size > 16 || homes.values.sumOf { it.bytes } > 4 * 1024 * 1024) homes.remove(homes.keys.first())
            }
            sections
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { cached?.sections ?: throw error }
    }

    private suspend fun <T> operation(name: String, overrides: JsonObject = JsonObject(emptyMap()), block: suspend (Runtime) -> T): T =
        withContext(Dispatchers.IO) { providerLocks.computeIfAbsent(name) { Mutex() }.withLock {
            capacity.withPermit {
                val installed = requireNotNull(store.state.value.installed.find { it.manifest.name == name && it.enabled && name !in revokedNames }) { "Enable this provider first" }
                check(!closed) { "Provider manager is closed" }
                val runtime = acquire(installed, overrides)
                kotlinx.coroutines.coroutineScope {
                    var observer: kotlinx.coroutines.Job? = null
                    try {
                        runtime.host.beginOperation()
                        observer = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            runtime.host.progress.collect { value -> mutableProgress.update { old ->
                                if (value == null) old - name else old + (name to value)
                            } }
                        }
                        block(runtime).also { mutableErrors.update { it - name } }
                    } catch (cancelled: CancellationException) { retire(name, clearHome = false); throw cancelled }
                    catch (error: Exception) {
                        mutableErrors.update { if (name in revokedNames) it - name else it + (name to "Provider request failed (${error.javaClass.simpleName})") }
                        retire(name, clearHome = false); throw error
                    } finally {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { observer?.cancel(); observer?.join() }
                        mutableProgress.update { it - name }
                        synchronized(cacheLock) { runtime.busy = false }
                    }
                }
            }
        } }

    private suspend fun acquire(installed: ProviderInstall, overrides: JsonObject): Runtime {
        val name = installed.manifest.name
        val key = cacheKey(installed, overrides)
        val evicted = ArrayList<Runtime>()
        synchronized(cacheLock) {
            check(name !in revokedNames && store.state.value.installed.find { it.manifest.name == name } == installed) { "Provider access changed" }
            runtimes[name]?.takeIf { it.key == key }?.let { it.busy = true; return it }
            runtimes.remove(name)?.let { evicted += it }
            while (runtimes.size + creating >= MAX_RUNTIMES) {
                val victim = runtimes.entries.firstOrNull { !it.value.busy } ?: error("Provider runtime capacity is busy")
                evicted += victim.value; runtimes.remove(victim.key)
            }
            creating++
        }
        evicted.forEach { it.session.close() }
        var session: ProviderScriptSession? = null
        var starting: ProviderMetadataHost? = null
        try {
            val unsupported = installed.manifest.requiredRuntimeFeatures.filter { it !in METADATA_FEATURES }
            require(unsupported.isEmpty()) { "Provider requires additional runtime features: ${unsupported.joinToString()}" }
            val archive = archive(installed.sha256)
            require(ProviderPackages.digest(archive) == installed.sha256) { "Installed provider package changed" }
            val code = ZipFile(archive).use { zip -> ProviderPackages.utf8(zip.getInputStream(zip.getEntry("index.js")).use { it.readBytes() }) }
            val host = ProviderMetadataHost(installed, store)
            starting = host
            synchronized(cacheLock) {
                check(!closed && name !in revokedNames && store.state.value.installed.find { it.manifest.name == name } == installed) { "Provider access changed" }
                startingHosts[name] = host
            }
            val settings = installed.manifest.effectiveSettings(store.state.value.settings[name].orEmpty() + overrides)
            val config = buildJsonObject {
                putJsonObject("permissions") {
                    putJsonArray("network") { installed.manifest.permissions.network.forEach { add(it) } }
                    put("storage", installed.manifest.permissions.storage)
                    put("file", installed.manifest.permissions.file)
                }
                put("settings", settings)
                put("signedSession", installed.manifest.signedSession != null)
                put("rawFfmpeg", false)
            }
            session = createProviderScriptSession(code, config, host::bridge, host::cancel)
            if ("initialize" in session.methods) {
                val initialized = Json.parseToJsonElement(session.invoke("initialize", buildJsonArray { add(settings) }))
                require(initialized != JsonPrimitive(false) && (initialized as? JsonObject)?.get("success") != JsonPrimitive(false)) {
                    "Provider could not initialize; review its settings"
                }
            }
            return Runtime(key, session, host, true).also { synchronized(cacheLock) {
                check(!closed && name !in revokedNames && store.state.value.installed.find { it.manifest.name == name } == installed) { "Provider access changed" }
                runtimes[name] = it
            } }
        } catch (error: Throwable) { starting?.cancel(); session?.close(); throw error }
        finally { synchronized(cacheLock) { starting?.let { startingHosts.remove(name, it) }; creating-- } }
    }

    private fun cacheKey(install: ProviderInstall, overrides: JsonObject): String {
        val settings = install.manifest.effectiveSettings(store.state.value.settings[install.manifest.name].orEmpty() + overrides)
        return install.manifest.name + "|" + install.sha256 + "|" + install.allowLocalNetwork + "|" + settings.toString()
    }
    private fun archive(hash: String): File {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return File(archiveRoot, "$hash.sflx")
    }
    private fun removeUnusedArchive(hash: String) {
        if (store.state.value.installed.none { it.sha256 == hash }) archive(hash).takeIf { it.canonicalFile.parentFile == archiveRoot.canonicalFile }?.let {
            it.setWritable(true, true); it.delete()
        }
    }
    private fun removeWorkspace(name: String) {
        require(name.matches(Regex("[a-z0-9][a-z0-9_-]{0,99}")))
        val parent = File(store.root, "workspaces").absoluteFile.toPath().normalize()
        require(!java.nio.file.Files.isSymbolicLink(parent)) { "Provider workspace root cannot be a link" }
        val target = parent.resolve(name).normalize()
        require(target.parent == parent && parent.startsWith(store.root.absoluteFile.toPath().normalize()))
        if (!java.nio.file.Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        // Files.walk does not follow links. Deleting a link inside this owned tree never touches its target.
        java.nio.file.Files.walk(target).use { paths -> paths.sorted(java.util.Comparator.reverseOrder()).forEach { item ->
            require(item.startsWith(target)) { "Provider workspace cleanup escaped its root" }
            java.nio.file.Files.deleteIfExists(item)
        } }
    }
    private fun retire(name: String, clearHome: Boolean = true, onlyIf: EnableIntent? = null) {
        if (clearHome) artwork?.cancel("provider:$name")
        val old = synchronized(cacheLock) {
            if (onlyIf != null && enableIntents[name] !== onlyIf) return
            if (clearHome && mediaDelegate.isInitialized()) media.revoke(name)
            if (clearHome) homes.keys.filter { it.startsWith("$name|") }.forEach { homes.remove(it) }
            runtimes.remove(name) to startingHosts.remove(name)
        }
        old.second?.cancel()
        old.first?.session?.close()
    }
    override fun close() {
        val old = synchronized(cacheLock) {
            closed = true
            (runtimes.values.toList() to startingHosts.values.toList()).also { runtimes.clear(); startingHosts.clear(); homes.clear() }
        }
        browserCallbacks.close()
        if (mediaDelegate.isInitialized()) media.close()
        old.second.forEach { it.cancel() }
        old.first.forEach { it.session.close() }
    }
    internal companion object {
        const val MAX_RUNTIMES = 2
        const val HOME_TTL_MS = 5 * 60 * 1000L
        // Prepared context is passed between media-resolution methods; it has no metadata host API.
        // Metadata compatibility here does not enable the package's media/download capabilities.
        private val METADATA_FEATURES = setOf("preparedContext@1", "signedSession@1", "signedSession@2", "signedSession@3", "sessionGrant@1", "patternedFileTransform@1")
        fun compareVersions(left: String, right: String): Int {
            fun core(value: String) = value.substringBefore('+').substringBefore('-').split('.').map { it.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO }
            val a = core(left); val b = core(right)
            for (i in 0 until maxOf(a.size, b.size)) {
                val comparison = (a.getOrNull(i) ?: java.math.BigInteger.ZERO).compareTo(b.getOrNull(i) ?: java.math.BigInteger.ZERO)
                if (comparison != 0) return comparison
            }
            fun suffix(value: String) = value.substringBefore('+').takeIf { '-' in it }?.substringAfter('-')?.split('.')
            val x = suffix(left); val y = suffix(right)
            if (x == null || y == null) return when { x == null && y != null -> 1; x != null && y == null -> -1; else -> 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val first = x.getOrNull(i) ?: return -1
                val second = y.getOrNull(i) ?: return 1
                val numericFirst = first.toBigIntegerOrNull(); val numericSecond = second.toBigIntegerOrNull()
                val comparison = when {
                    numericFirst != null && numericSecond != null -> numericFirst.compareTo(numericSecond)
                    numericFirst != null -> -1
                    numericSecond != null -> 1
                    else -> first.compareTo(second)
                }
                if (comparison != 0) return comparison
            }
            return 0
        }
    }
}
