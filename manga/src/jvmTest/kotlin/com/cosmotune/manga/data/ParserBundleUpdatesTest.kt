package com.cosmotune.manga.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.koitharu.kotatsu.parsers.*
import org.koitharu.kotatsu.parsers.model.*

/** Used only by locally compiled, signed fixture code; no website or external process is contacted. */
object ParserUpdateTestBridge { @JvmField val bundle: ParsersBundle = bundledParsers() }

class ParserBundleUpdatesTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val builtIn = bundledParsers()
    private val revision = "cccccccccc"

    private fun envelope(archive: ByteArray, version: Long = builtIn.version + 1, runtime: String = "jvm", extra: Map<String, JsonElement> = emptyMap()): String {
        val payload = buildJsonObject {
            put("format", 1); put("api", 1); put("version", version); put("revision", revision)
            put("runtime", runtime); put("minSdk", 26); put("file", if (runtime == "jvm") "fixture.jar" else "fixture.zip")
            put("size", archive.size); put("sha256", ParserBundleVerifier.hash(archive))
            extra.forEach { (name, value) -> put(name, value) }
        }.toString().toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(payload); sign() }
        return buildJsonObject { put("payload", Base64.getEncoder().encodeToString(payload)); put("signature", Base64.getEncoder().encodeToString(signature)) }.toString()
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        out.toByteArray()
    }

    @Test fun damagedReplayHistoryStopsExecutableUpdatesWithoutOverwritingRecoveryData() = runBlocking {
        val root = Files.createTempDirectory("parser-history-corrupt").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val history = File(root, "history.json").apply { writeText("{interrupted") }
            var requests = 0
            val updater = ParserBundleUpdates(root, ParserUpdateChannel("https://fixtures.invalid", publicKey), scope = scope,
                fetch = { _, _ -> requests++; error("No network request may bypass damaged replay history") })
            updater.awaitReady()
            assertFalse(updater.check(force = true))
            assertEquals(0, requests)
            assertEquals("{interrupted", history.readText())
            assertEquals(builtIn.version, updater.bundle.value.version)
            updater.rollback()
            assertEquals("{interrupted", history.readText())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun signatureApiPlatformVersionSizeAndArchiveBoundariesAreCheckedBeforeLoading() {
        val bytes = zip("org/koitharu/kotatsu/parsers/core/ParsersBundleImpl.class" to byteArrayOf(1))
        val verifier = ParserBundleVerifier(publicKey, "jvm", Int.MAX_VALUE, 1)
        assertEquals(revision, verifier.manifest(envelope(bytes)).revision)
        val signed = Json.parseToJsonElement(envelope(bytes)).jsonObject
        val corrupted = JsonObject(signed + ("payload" to JsonPrimitive(Base64.getEncoder().encodeToString("{}".toByteArray())))).toString()
        assertFailsWith<IllegalArgumentException> { verifier.manifest(corrupted) }
        assertFailsWith<IllegalArgumentException> { verifier.manifest(envelope(bytes, extra = mapOf("api" to JsonPrimitive(2)))) }
        assertFailsWith<IllegalArgumentException> { verifier.manifest(envelope(bytes, runtime = "android")) }
        assertFailsWith<IllegalArgumentException> { verifier.manifest(envelope(bytes, extra = mapOf("file" to JsonPrimitive("../code.jar")))) }
        assertFailsWith<IllegalArgumentException> { verifier.manifest(envelope(bytes, extra = mapOf("size" to JsonPrimitive(ParserBundleVerifier.MAX_ARCHIVE + 1)))) }
        val root = Files.createTempDirectory("parser-verify").toFile()
        try {
            val file = File(root, "fixture.jar").apply { writeBytes(bytes + byteArrayOf(2)) }
            assertFailsWith<IllegalArgumentException> { verifier.archive(file, verifier.manifest(envelope(bytes))) }
            val contract = zip("org/koitharu/kotatsu/parsers/core/ParsersBundleImpl.class" to byteArrayOf(1), "org/koitharu/kotatsu/parsers/ParsersBundle.class" to byteArrayOf(2))
            file.writeBytes(contract)
            assertFailsWith<IllegalArgumentException> { verifier.archive(file, verifier.manifest(envelope(contract))) }
            val external = zip("org/koitharu/kotatsu/parsers/core/ParsersBundleImpl.class" to byteArrayOf(1), "outside/Injected.class" to byteArrayOf(2))
            file.writeBytes(external)
            assertFailsWith<IllegalArgumentException> { verifier.archive(file, verifier.manifest(envelope(external))) }
        } finally { root.deleteRecursively() }
    }

    @Test fun signedJvmImplementationLoadsChildFirstRestoresAndRejectsRollbackReplay() = runBlocking {
        val root = Files.createTempDirectory("parser-install").toFile()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val bytes = fixture(root, builtIn.version + 10)
            val manifest = envelope(bytes, builtIn.version + 10)
            val channel = ParserUpdateChannel("https://fixtures.invalid", publicKey)
            val fetch: suspend (String, Long) -> ByteArray = { url, _ -> if (url.endsWith(".json")) manifest.toByteArray() else bytes }
            val first = ParserBundleUpdates(File(root, "installed"), channel, scope = firstScope, fetch = fetch)
            assertTrue(first.check(force = true))
            assertEquals(builtIn.version + 10, first.bundle.value.version)
            assertNotSame(builtIn.javaClass.classLoader, first.bundle.value.javaClass.classLoader)
            assertTrue(first.status.value.downloaded)
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            val restored = ParserBundleUpdates(File(root, "installed"), channel, scope = secondScope, fetch = fetch)
            restored.awaitReady()
            assertEquals(builtIn.version + 10, restored.bundle.value.version)
            assertFalse(restored.check(force = true), "same signed version cannot be replayed")
            restored.rollback()
            assertEquals(builtIn.version, restored.bundle.value.version)
            assertFalse(restored.check(force = true), "manual rollback preserves the independent version floor")
        } finally {
            firstScope.coroutineContext[Job]!!.cancelAndJoin(); secondScope.coroutineContext[Job]!!.cancelAndJoin()
            root.walkBottomUp().forEach { it.setWritable(true) }; root.deleteRecursively()
        }
    }

    @Test fun incompatibleCodeAndTwoInterruptedParserSessionsReturnToTheBundledParsers() = runBlocking {
        val root = Files.createTempDirectory("parser-rollback").toFile()
        val scopes = mutableListOf<CoroutineScope>()
        fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        try {
            val bytes = fixture(root, builtIn.version + 20)
            var manifest = envelope(bytes, builtIn.version + 21) // signed metadata disagrees with code
            val channel = ParserUpdateChannel("https://fixtures.invalid", publicKey)
            val fetch: suspend (String, Long) -> ByteArray = { url, _ -> if (url.endsWith(".json")) manifest.toByteArray() else bytes }
            val install = File(root, "installed")
            val first = ParserBundleUpdates(install, channel, scope = scope(), fetch = fetch)
            assertFalse(first.check(force = true))
            assertEquals(builtIn.version, first.bundle.value.version)
            // A correctly signed, newer fixture can still replace the rejected publication.
            val goodBytes = fixture(root, builtIn.version + 30)
            manifest = envelope(goodBytes, builtIn.version + 30)
            val goodFetch: suspend (String, Long) -> ByteArray = { url, _ -> if (url.endsWith(".json")) manifest.toByteArray() else goodBytes }
            val good = ParserBundleUpdates(install, channel, scope = scope(), fetch = goodFetch)
            assertTrue(good.check(force = true))
            File(install, "running").writeText((builtIn.version + 30).toString())
            val once = ParserBundleUpdates(install, channel, scope = scope(), fetch = goodFetch)
            once.awaitReady()
            assertEquals(builtIn.version + 30, once.bundle.value.version)
            File(install, "running").writeText((builtIn.version + 30).toString())
            val twice = ParserBundleUpdates(install, channel, scope = scope(), fetch = goodFetch)
            twice.awaitReady()
            assertEquals(builtIn.version, twice.bundle.value.version)
            assertFalse(twice.check(force = true))
        } finally {
            scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
            root.walkBottomUp().forEach { it.setWritable(true) }; root.deleteRecursively()
        }
    }

    @Test fun everyActiveCatalogueAppliesAdultAndNameExclusions() {
        fun source(name: String, title: String, type: ContentType) = object : ParserMangaSource(name, title, "en", type, false) {}
        val sources = listOf(source("SAFE", "Safe", ContentType.MANGA), source("ADULT", "Adult catalogue", ContentType.HENTAI), source("EXCLUDED", "Hènt library", ContentType.MANGA))
        val bundle = object : ParsersBundle by builtIn { override val sources = sources }
        assertEquals(listOf("kotatsu:SAFE"), KotatsuSource.catalogue(bundle).map { it.id })
    }

    @Test fun androidDexDefinitionsAreVerifiedAndDesktopCannotConsumeTheSameArchive() {
        val descriptor = "Lorg/koitharu/kotatsu/parsers/core/ParsersBundleImpl;".toByteArray()
        val dex = ByteArray(154 + descriptor.size)
        "dex\n035\u0000".toByteArray().copyInto(dex)
        fun uint(at: Int, value: Int) { java.nio.ByteBuffer.wrap(dex).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(at, value) }
        uint(32, dex.size); uint(36, 112); uint(40, 0x12345678)
        uint(56, 1); uint(60, 112); uint(64, 1); uint(68, 116); uint(96, 1); uint(100, 120)
        uint(112, 152); uint(116, 0); uint(120, 0)
        dex[152] = descriptor.size.toByte(); descriptor.copyInto(dex, 153)
        assertEquals(setOf(ParserBundleVerifier.IMPLEMENTATION), DexParserClasses.names(dex))
        val archive = zip("classes.dex" to dex)
        val extra = mapOf("dex" to buildJsonArray { add(buildJsonObject { put("name", "classes.dex"); put("size", dex.size); put("sha256", ParserBundleVerifier.hash(dex)) }) })
        val signed = envelope(archive, runtime = "android", extra = extra)
        val android = ParserBundleVerifier(publicKey, "android", 26, 1)
        val manifest = android.manifest(signed)
        val root = Files.createTempDirectory("parser-dex").toFile()
        try {
            val file = File(root, "fixture.zip").apply { writeBytes(archive) }
            assertEquals(setOf(ParserBundleVerifier.IMPLEMENTATION), android.archive(file, manifest))
            assertFailsWith<IllegalArgumentException> { ParserBundleVerifier(publicKey, "jvm", Int.MAX_VALUE, 1).manifest(signed) }
            assertFailsWith<IllegalArgumentException> { ParserBundleVerifier(publicKey, "android", 25, 1).manifest(signed) }
            dex[100] = 127
            assertFailsWith<IllegalArgumentException> { DexParserClasses.names(dex) }
        } finally { root.deleteRecursively() }
    }

    private fun fixture(root: File, version: Long): ByteArray {
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "Parser update regression requires the build JDK's compiler")
        val directory = File(root, "fixture-$version").apply { mkdirs() }
        val source = File(directory, "ParsersBundleImpl.java").apply { writeText("""
            package org.koitharu.kotatsu.parsers.core;
            public final class ParsersBundleImpl implements org.koitharu.kotatsu.parsers.ParsersBundle {
                public static final ParsersBundleImpl INSTANCE = new ParsersBundleImpl();
                private final org.koitharu.kotatsu.parsers.ParsersBundle delegate = com.cosmotune.manga.data.ParserUpdateTestBridge.bundle;
                public int getApiVersion() { return 1; }
                public long getVersion() { return ${version}L; }
                public String getRevision() { return "$revision"; }
                public java.util.List<org.koitharu.kotatsu.parsers.model.ParserMangaSource> getSources() { return delegate.getSources(); }
                public org.koitharu.kotatsu.parsers.model.ParserMangaSource findSource(String name) { return delegate.findSource(name); }
                public org.koitharu.kotatsu.parsers.MangaParser newParser(org.koitharu.kotatsu.parsers.model.ParserMangaSource source, org.koitharu.kotatsu.parsers.MangaLoaderContext context) { return delegate.newParser(source, context); }
                public Object resolveLink(org.koitharu.kotatsu.parsers.MangaLoaderContext context, okhttp3.HttpUrl link, kotlin.coroutines.Continuation<? super org.koitharu.kotatsu.parsers.model.Manga> continuation) { return null; }
            }
        """.trimIndent()) }
        val classpath = listOf(ParsersBundle::class.java, okhttp3.HttpUrl::class.java, kotlin.coroutines.Continuation::class.java, ParserUpdateTestBridge::class.java, kotlin.Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        assertEquals(0, compiler.run(null, null, null, "-classpath", classpath, "-d", directory.path, source.path))
        val name = "org/koitharu/kotatsu/parsers/core/ParsersBundleImpl.class"
        return zip(name to File(directory, name).readBytes())
    }
}
