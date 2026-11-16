package com.cosmotune.manga.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.ParsersApi
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.ParserMangaSource

@OptIn(DirectParsersAccess::class)
internal fun bundledParsers(): ParsersBundle = ParsersBundleImpl

data class ParserUpdateStatus(val busy: Boolean = false, val revision: String, val version: Long, val downloaded: Boolean = false, val message: String? = null)
internal data class ParserUpdateChannel(val url: String, val publicKey: String)
internal expect val parserRuntime: String
internal expect val parserRuntimeSdk: Int
internal expect fun parserClassLoader(file: File, classes: Set<String>, parent: ClassLoader): ClassLoader

/** Two verified slots and an independent version floor. Falling back never authorizes a signed downgrade. */
class ParserBundleUpdates internal constructor(
    private val root: File,
    private val channel: ParserUpdateChannel = ParserUpdateChannel(BundledParserChannel.URL, BundledParserChannel.PUBLIC_KEY),
    private val builtIn: ParsersBundle = bundledParsers(),
    private val runtime: String = parserRuntime,
    private val sdk: Int = parserRuntimeSdk,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: suspend (String, Long) -> ByteArray = ::fetchParserBytes,
    private val loadClasses: (File, Set<String>, ClassLoader) -> ClassLoader = ::parserClassLoader,
) {
    @Serializable private data class History(val highest: Long = 0, val rejected: Set<Long> = emptySet(), val crashes: Map<Long, Int> = emptyMap())
    private val json = Json { ignoreUnknownKeys = true }
    private val verifier = ParserBundleVerifier(channel.publicKey, runtime, sdk, ParsersApi.VERSION)
    private val lock = Mutex()
    private val activeFile = File(root, "active.json")
    private val previousFile = File(root, "previous.json")
    private val historyFile = File(root, "history.json")
    private val runningFile = File(root, "running")
    private var historyDamaged = false
    private var history = if (!historyFile.exists()) History() else try {
        json.decodeFromString(History.serializer(), readRecord(historyFile)).also {
            require(it.highest >= 0 && it.rejected.size <= 2_000 && it.crashes.size <= 2_000)
            require(it.rejected.all { version -> version > 0 && version <= it.highest } && it.crashes.all { (version, count) -> version > 0 && count >= 0 })
        }
    } catch (_: Exception) { historyDamaged = true; History() }
    private val current = MutableStateFlow(builtIn)
    val bundle = current.asStateFlow()
    private val statusState = MutableStateFlow(ParserUpdateStatus(revision = builtIn.revision, version = builtIn.version))
    val status = statusState.asStateFlow()
    private var lastCheck = 0L
    private var lastFailureCheck = 0L
    private var calls = 0
    private var callVersion = 0L
    private val loaders = mutableListOf<ClassLoader>()
    private val ready = scope.async { lock.withLock {
        try { restore() } catch (_: Exception) { statusState.value = statusState.value.copy(message = "Saved parser update could not be opened. Built-in parsers were kept.") }
    } }
    init { scope.coroutineContext[Job]?.invokeOnCompletion {
        synchronized(loaders) { loaders.forEach { runCatching { (it as? java.io.Closeable)?.close() } }; loaders.clear() }
    } }

    suspend fun awaitReady() { ready.await() }

    suspend fun check(force: Boolean = false, afterFailure: Boolean = false): Boolean {
        awaitReady()
        return lock.withLock {
            if (historyDamaged) {
                statusState.value = statusState.value.copy(message = "Saved parser update history is damaged. Updates were stopped and its original file was kept for recovery.")
                return@withLock false
            }
            val time = now()
            val previousCheck = if (afterFailure) lastFailureCheck else lastCheck
            val interval = if (afterFailure) 5 * 60_000L else 6 * 60 * 60_000L
            if (!force && previousCheck > 0 && time - previousCheck in 0 until interval) return@withLock false
            if (afterFailure) lastFailureCheck = time else lastCheck = time
            if (channel.url.isBlank() || channel.publicKey.isBlank()) {
                statusState.value = statusState.value.copy(message = "Parser updates are not configured in this build.")
                return@withLock false
            }
            statusState.value = statusState.value.copy(busy = true, message = "Checking signed parser updates…")
            try {
                require(channel.url.startsWith("https://") || channel.url.startsWith("http://127.0.0.1:") || channel.url.startsWith("http://localhost:"))
                val base = channel.url.trimEnd('/')
                val envelope = fetch("$base/parsers-$runtime-v${ParsersApi.VERSION}.json", ParserBundleVerifier.MAX_MANIFEST.toLong()).toString(Charsets.UTF_8)
                val manifest = verifier.manifest(envelope)
                if (manifest.version <= maxOf(builtIn.version, history.highest)) {
                    statusState.value = statusState.value.copy(message = if (manifest.version in history.rejected) "That parser update was rolled back. Waiting for a newer signed update." else "Parsers are up to date.")
                    return@withLock false
                }
                statusState.value = statusState.value.copy(message = "Downloading verified parsers…")
                root.mkdirs()
                require(root.usableSpace > manifest.size + 8L * 1024 * 1024) { "Free storage before downloading parser updates." }
                val bytes = fetch("$base/${manifest.file}", manifest.size)
                require(bytes.size.toLong() == manifest.size && ParserBundleVerifier.hash(bytes) == manifest.sha256) { "Parser update size or SHA-256 does not match." }
                root.mkdirs()
                val destination = artifact(manifest)
                val staging = File(root, "download.part")
                staging.outputStream().use { it.write(bytes); it.fd.sync() }
                val classes = verifier.archive(staging, manifest)
                atomicMove(staging, destination)
                check(destination.setReadOnly()) { "Could not protect parser code from modification." }
                // A known bad update is never downloaded repeatedly or bypassed with an older signature.
                history = history.copy(highest = manifest.version)
                saveHistory()
                val loaded = try { load(destination, classes, manifest) } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    history = history.copy(rejected = history.rejected + manifest.version); saveHistory()
                    throw IllegalStateException("The signed parsers could not load. Existing parsers were kept.", failure)
                }
                if (activeFile.isFile) atomicWrite(previousFile, readRecord(activeFile))
                atomicWrite(activeFile, envelope)
                activate(loaded, "Parsers updated to ${manifest.revision}.")
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                statusState.value = statusState.value.copy(message = failure.message?.take(240) ?: "Parser update failed. Existing parsers were kept.")
                false
            } finally { statusState.value = statusState.value.copy(busy = false) }
        }
    }

    suspend fun rollback() { awaitReady(); lock.withLock {
        if (historyDamaged) { statusState.value = statusState.value.copy(message = "Saved parser update history is damaged. Built-in parsers were kept and the original history was preserved."); return@withLock }
        rollbackLocked("Previous parsers restored. The rejected version will not be installed again.")
    } }

    internal fun newParser(selected: ParsersBundle, source: ParserMangaSource, context: MangaLoaderContext): MangaParser = try {
        selected.newParser(source, context)
    } catch (failure: LinkageError) {
        if (selected !== builtIn) scope.launch { lock.withLock { if (current.value === selected) rollbackLocked("An incompatible parser update was rolled back. Retry this source.") } }
        throw MangaSourceFailure("This parser requires an unavailable API. Retry after updating sources.", failure)
    }

    /** Marker survives a process crash inside downloaded code, while normal errors/cancellation always release it. */
    internal suspend fun <T> using(selected: ParsersBundle, block: suspend () -> T): T {
        if (selected === builtIn) return block()
        synchronized(this) {
            if (calls == 0 || selected.version > callVersion) { callVersion = selected.version; atomicWrite(runningFile, selected.version.toString()) }
            calls++
        }
        try { return block() }
        catch (failure: LinkageError) {
            lock.withLock { if (current.value.version == selected.version) rollbackLocked("An incompatible parser update was rolled back. Retry this source.") }
            throw MangaSourceFailure("The parser update was rolled back because it needs an unavailable API. Retry this source.", failure)
        } finally {
            synchronized(this) { calls--; if (calls == 0) runningFile.delete() }
        }
    }

    private fun restore() {
        check(!historyDamaged) { "Saved parser update history is damaged." }
        if (channel.publicKey.isBlank()) return
        root.mkdirs()
        val interrupted = runningFile.takeIf { it.isFile }?.let { readRecord(it, 32).toLongOrNull() }
        if (interrupted != null) {
            history = history.copy(crashes = history.crashes + (interrupted to (history.crashes.getOrDefault(interrupted, 0) + 1)))
            saveHistory(); runningFile.delete()
        }
        val loaded = runCatching {
            if (!activeFile.isFile) return@runCatching null
            val envelope = readRecord(activeFile)
            val manifest = verifier.manifest(envelope)
            if (manifest.version <= builtIn.version) { activeFile.delete(); return@runCatching null }
            if (manifest.version in history.rejected || history.crashes.getOrDefault(manifest.version, 0) >= 2) error("The downloaded parsers repeatedly stopped unexpectedly.")
            history = history.copy(highest = maxOf(history.highest, manifest.version)); saveHistory()
            load(artifact(manifest), verifier.archive(artifact(manifest), manifest), manifest)
        }
        if (loaded.isFailure) rollbackLocked("The downloaded parsers could not start. Previous parsers restored.")
        else loaded.getOrNull()?.let { activate(it, "Signed parser update restored.") }
    }

    private fun rollbackLocked(message: String) {
        val rejected = current.value.takeIf { it !== builtIn }?.version ?: runCatching { verifier.manifest(readRecord(activeFile)).version }.getOrNull()
        if (rejected != null) { history = history.copy(highest = maxOf(history.highest, rejected), rejected = history.rejected + rejected); saveHistory() }
        val previous = runCatching {
            val envelope = readRecord(previousFile)
            val manifest = verifier.manifest(envelope)
            require(manifest.version !in history.rejected && manifest.version > builtIn.version)
            val loaded = load(artifact(manifest), verifier.archive(artifact(manifest), manifest), manifest)
            atomicWrite(activeFile, envelope)
            loaded
        }.getOrNull()
        if (previous == null) activeFile.delete()
        previousFile.delete()
        activate(previous ?: builtIn, message)
    }

    private fun load(file: File, classes: Set<String>, manifest: ParserBundleManifest): ParsersBundle {
        val parent = requireNotNull(ParsersBundle::class.java.classLoader)
        val loader = loadClasses(file, classes, parent)
        try {
        val implementation = Class.forName(ParserBundleVerifier.IMPLEMENTATION, true, loader)
        require(implementation.classLoader === loader) { "The app's bundled parsers shadowed the update." }
        val parsers = implementation.getField("INSTANCE").get(null) as ParsersBundle
        require(parsers.apiVersion == manifest.api && parsers.version == manifest.version && parsers.revision == manifest.revision) { "Parser code identity does not match its signed manifest." }
        val sources = parsers.sources
        require(sources.size in 1..5_000 && sources.map { it.name }.distinct().size == sources.size)
        require(sources.all { it.name.matches(Regex("[A-Z0-9_]{1,100}")) && it.title.length in 1..200 && it.locale.length <= 35 && parsers.findSource(it.name) == it })
        synchronized(loaders) { loaders += loader }
        return parsers
        } catch (failure: Throwable) {
            runCatching { (loader as? java.io.Closeable)?.close() }
            throw failure
        }
    }

    private fun activate(parsers: ParsersBundle, message: String) {
        current.value = parsers
        statusState.value = ParserUpdateStatus(revision = parsers.revision, version = parsers.version, downloaded = parsers !== builtIn, message = message)
    }
    private fun artifact(manifest: ParserBundleManifest) = File(root, "bundle-${manifest.version}-${manifest.sha256.take(16)}.${if (runtime == "jvm") "jar" else "zip"}")
    private fun saveHistory() = atomicWrite(historyFile, json.encodeToString(History.serializer(), history))

    internal companion object {
        private fun readRecord(file: File, maximum: Int = ParserBundleVerifier.MAX_MANIFEST): String {
            require(file.length() <= maximum)
            return file.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4 * 1024)
                while (true) { val read = input.read(buffer); if (read < 0) break; require(out.size() + read <= maximum); out.write(buffer, 0, read) }
                out.toString(Charsets.UTF_8.name())
            }
        }
        fun atomicWrite(file: File, text: String) {
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            atomicMove(tmp, file)
        }
        private fun atomicMove(from: File, to: File) {
            try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}

private val parserHttp = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followSslRedirects(false)
    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()

private suspend fun fetchParserBytes(url: String, limit: Long): ByteArray = suspendCancellableCoroutine { continuation ->
    require(limit in 1..ParserBundleVerifier.MAX_ARCHIVE)
    val call = parserHttp.newCall(Request.Builder().url(url).get().build())
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : okhttp3.Callback {
      override fun onFailure(call: okhttp3.Call, failure: java.io.IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(failure)) }
      override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
        val result = runCatching { response.use {
            check(response.isSuccessful) { "Parser channel returned HTTP ${response.code}." }
            val body = response.body
            require(body.contentLength() <= limit)
            body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) { if (!continuation.isActive) throw java.io.IOException("Parser download cancelled."); val read = input.read(buffer); if (read < 0) break; require(out.size().toLong() + read <= limit) { "Parser download exceeds its declared size." }; out.write(buffer, 0, read) }
                out.toByteArray()
            }
        } }
        if (continuation.isActive) continuation.resumeWith(result)
      }
    })
}
