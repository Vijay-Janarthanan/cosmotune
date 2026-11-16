package com.cosmotune.manga.data

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable internal data class ParserBundleEntry(val name: String, val size: Long, val sha256: String)
@Serializable internal data class ParserBundleManifest(
    val format: Int,
    val api: Int,
    val version: Long,
    val revision: String,
    val runtime: String = "android",
    val minSdk: Int,
    val file: String,
    val size: Long,
    val sha256: String,
    val dex: List<ParserBundleEntry> = emptyList(),
)
@Serializable private data class ParserBundleEnvelope(val payload: String, val signature: String)

/** Executable updates are authenticated before any payload field is trusted or any class is inspected/loaded. */
internal class ParserBundleVerifier(private val publicKey: String, private val runtime: String, private val sdk: Int, private val api: Int) {
    private val json = Json { ignoreUnknownKeys = false }
    fun manifest(envelope: String): ParserBundleManifest {
        require(envelope.toByteArray().size <= MAX_MANIFEST) { "Parser manifest is too large." }
        val signed = json.decodeFromString(ParserBundleEnvelope.serializer(), envelope)
        require(signed.payload.length <= MAX_MANIFEST && signed.signature.length <= 256)
        val bytes = Base64.getDecoder().decode(signed.payload)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))) as ECPublicKey
        require(key.params.curve.field.fieldSize == 256) { "Unsupported parser signing key." }
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(bytes); verify(Base64.getDecoder().decode(signed.signature))
        }
        require(verified) { "Parser update signature is invalid." }
        val manifest = json.decodeFromString(ParserBundleManifest.serializer(), bytes.toString(Charsets.UTF_8))
        require(manifest.format == 1 && manifest.api == api) { "This parser update needs a different app API. Update Cosmotune first." }
        require(manifest.runtime == runtime) { "This parser update is for another platform." }
        require(manifest.minSdk in 26..sdk && manifest.version > 0) { "This parser update requires a newer operating system." }
        require(manifest.revision.matches(Regex("[a-fA-F0-9]{10,40}"))) { "Invalid parser revision." }
        require(manifest.file.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,150}")) && ".." !in manifest.file)
        require(manifest.file.endsWith(if (runtime == "jvm") ".jar" else ".zip"))
        require(manifest.size in 1..MAX_ARCHIVE && HASH.matches(manifest.sha256))
        if (runtime == "android") {
            require(manifest.dex.size in 1..16 && manifest.dex.map { it.name }.distinct().size == manifest.dex.size)
            require(manifest.dex.map { it.name }.toSet() == (1..manifest.dex.size).map { if (it == 1) "classes.dex" else "classes$it.dex" }.toSet()) { "Parser DEX files must form a complete sequence." }
            require(manifest.dex.all { it.name.matches(Regex("classes(?:[2-9]|[1-9][0-9])?\\.dex")) && it.size in 1..MAX_EXPANDED && HASH.matches(it.sha256) })
            require(manifest.dex.sumOf { it.size } <= MAX_EXPANDED)
        } else require(manifest.dex.isEmpty()) { "Desktop parser updates cannot contain Android DEX." }
        return manifest
    }

    fun archive(file: File, manifest: ParserBundleManifest): Set<String> {
        require(file.length() == manifest.size && sha256(file) == manifest.sha256) { "Parser update size or SHA-256 does not match." }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 1..30_000 && entries.map { it.name }.distinct().size == entries.size) { "Invalid parser archive." }
            val classes = linkedSetOf<String>()
            var expanded = 0L
            if (runtime == "android") {
                require(entries.none { it.isDirectory } && entries.map { it.name }.toSet() == manifest.dex.map { it.name }.toSet())
                manifest.dex.forEach { expected ->
                    val entry = requireNotNull(zip.getEntry(expected.name))
                    require(entry.size == expected.size)
                    val bytes = zip.getInputStream(entry).use { readBounded(it, expected.size) }
                    require(hash(bytes) == expected.sha256) { "Parser DEX hash does not match." }
                    classes += DexParserClasses.names(bytes)
                }
            } else {
                entries.forEach { entry ->
                    require(!entry.isDirectory && entry.name.startsWith(PREFIX) && !entry.name.contains("..") && !entry.name.contains('\\')) { "Parser archive contains an unexpected package." }
                    require(entry.size in 0..MAX_EXPANDED && entry.size <= MAX_EXPANDED - expanded)
                    expanded += entry.size
                    // Read every entry now, including resources, to enforce CRC and decompression bounds before executing code.
                    zip.getInputStream(entry).use { readBounded(it, entry.size) }
                    if (entry.name.endsWith(".class")) classes += entry.name.removeSuffix(".class").replace('/', '.')
                }
            }
            require(IMPLEMENTATION in classes && classes.none(::contractClass)) { "Parser update replaces the app contract or has no entry point." }
            require(classes.all { it.startsWith(PREFIX.replace('/', '.')) })
            return classes
        }
    }

    companion object {
        const val MAX_MANIFEST = 64 * 1024
        const val MAX_ARCHIVE = 32L * 1024 * 1024
        const val MAX_EXPANDED = 96L * 1024 * 1024
        const val PREFIX = "org/koitharu/kotatsu/parsers/"
        const val IMPLEMENTATION = "org.koitharu.kotatsu.parsers.core.ParsersBundleImpl"
        private val HASH = Regex("[a-f0-9]{64}")
        internal fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        internal fun readBounded(input: java.io.InputStream, expected: Long): ByteArray {
            require(expected in 0..MAX_EXPANDED)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; require(out.size().toLong() + read <= expected); out.write(buffer, 0, read) }
            require(out.size().toLong() == expected)
            return out.toByteArray()
        }

        /** Contract classes always come from the host. Nested contract classes must also stay parent-loaded. */
        internal fun contractClass(name: String): Boolean {
            val top = name.substringBefore('$')
            val relative = top.removePrefix("org.koitharu.kotatsu.parsers.")
            return relative in setOf("Broken", "ErrorMessages", "InternalParsersApi", "MangaLoaderContext", "MangaParser", "MangaParserAuthProvider", "MangaSourceParser", "ParsersBundle", "ParsersApi", "DirectParsersAccess") ||
                relative.startsWith("bitmap.") || relative.startsWith("config.") || relative.startsWith("exception.") || relative.startsWith("webview.") ||
                (relative.startsWith("model.") && relative != "model.MangaParserSource" && !relative.startsWith("model.MangaParserSourceKt"))
        }
    }
}

/** Reads class definitions (not references) from a standard DEX header; all bounds are checked before dereferencing. */
internal object DexParserClasses {
    fun names(bytes: ByteArray): Set<String> {
        require(bytes.size >= 112 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) { "Invalid DEX header." }
        fun uint(at: Int): Int {
            require(at >= 0 && at <= bytes.size - 4)
            val value = (bytes[at].toLong() and 255) or ((bytes[at + 1].toLong() and 255) shl 8) or ((bytes[at + 2].toLong() and 255) shl 16) or ((bytes[at + 3].toLong() and 255) shl 24)
            require(value <= Int.MAX_VALUE); return value.toInt()
        }
        require(uint(32) == bytes.size && uint(36) == 112 && uint(40) == 0x12345678)
        val strings = uint(56); val stringsAt = uint(60); val types = uint(64); val typesAt = uint(68)
        val count = uint(96); val at = uint(100)
        require(at % 4 == 0 && stringsAt % 4 == 0 && typesAt % 4 == 0)
        require(count in 1..30_000 && at.toLong() + count * 32L <= bytes.size)
        require(stringsAt.toLong() + strings * 4L <= bytes.size && typesAt.toLong() + types * 4L <= bytes.size)
        return (0 until count).map { index ->
            val type = uint(at + index * 32); require(type < types)
            val string = uint(typesAt + type * 4); require(string < strings)
            var textAt = uint(stringsAt + string * 4)
            var prefix = 0
            var next: Int
            do { require(textAt < bytes.size && prefix++ < 5); next = bytes[textAt++].toInt() and 255 } while (next and 128 != 0)
            val end = (textAt until minOf(bytes.size, textAt + 2_048)).firstOrNull { bytes[it] == 0.toByte() } ?: error("Invalid DEX class name.")
            val descriptor = bytes.copyOfRange(textAt, end).toString(Charsets.UTF_8)
            require(descriptor.startsWith('L') && descriptor.endsWith(';'))
            descriptor.substring(1, descriptor.lastIndex).replace('/', '.')
        }.toSet()
    }
}
