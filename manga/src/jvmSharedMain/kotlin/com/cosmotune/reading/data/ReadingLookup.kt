package com.cosmotune.reading.data

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** One meaning of a looked-up word: where it came from, the part of speech and the definition, with an example when there is one. */
data class ReadingDefinition(val source: String, val heading: String, val text: String, val example: String = "")
/** A Wikipedia article's opening. */
data class ReadingArticle(val title: String, val extract: String, val url: String)
/** A dictionary the reader added (a StarDict pack), kept on this device. */
@Serializable data class ReadingDictionary(val id: String, val name: String, val words: Int)

/**
 * Looking words up: offline StarDict packs the reader added first, then (when allowed) Wiktionary, Wikipedia and MyMemory's free
 * translation, none of which needs a key. Only the chosen words are sent, and only when the reader asks.
 */
class ReadingLookup internal constructor(client: OkHttpClient, private val wiktionary: HttpUrl, private val wikipedia: (String) -> HttpUrl,
    private val translator: HttpUrl) {
    constructor(client: OkHttpClient = OkHttpClient()) : this(client, "https://en.wiktionary.org/api/rest_v1/page/definition/".toHttpUrl(),
        { language -> "https://$language.wikipedia.org/api/rest_v1/page/summary/".toHttpUrl() }, "https://api.mymemory.translated.net/get".toHttpUrl())
    private val http = client.newBuilder().followRedirects(true).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", AGENT).build()).awaitReading { response ->
            if (response.code == 404) null else response.catalogueText(2 * 1024 * 1024)
        }
    }

    /** Wiktionary's meanings of [word], those in [language] (a code such as "en") first. */
    suspend fun define(word: String, language: String = "en"): List<ReadingDefinition> {
        val clean = word.trim().take(100)
        if (clean.isEmpty()) return emptyList()
        val body = get(wiktionary.newBuilder().addPathSegment(clean).build()) ?: get(wiktionary.newBuilder().addPathSegment(clean.lowercase()).build()) ?: return emptyList()
        val root = json.parseToJsonElement(body).jsonObject
        val code = language.substringBefore('-').lowercase()
        return root.entries.sortedBy { if (it.key == code) 0 else if (it.key == "en") 1 else 2 }.flatMap { (_, value) ->
            (value as? JsonArray).orEmpty().flatMap { usage ->
                val entry = usage.jsonObject
                val heading = listOfNotNull(entry["language"]?.jsonPrimitive?.contentOrNull, entry["partOfSpeech"]?.jsonPrimitive?.contentOrNull).joinToString(" · ")
                (entry["definitions"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val definition = item.jsonObject["definition"]?.jsonPrimitive?.contentOrNull?.let { Jsoup.parse(it).text() }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val example = (item.jsonObject["examples"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull?.let { Jsoup.parse(it).text() }.orEmpty()
                    ReadingDefinition("Wiktionary", heading, definition, example)
                }
            }
        }.take(30)
    }

    /** The opening of the Wikipedia article on [title], in [language]'s Wikipedia (English when it has none). */
    suspend fun article(title: String, language: String = "en"): ReadingArticle? {
        val clean = title.trim().take(200).ifEmpty { return null }
        val code = language.substringBefore('-').lowercase().takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        val body = get(wikipedia(code).newBuilder().addPathSegment(clean.replace(' ', '_')).build())
            ?: if (code != "en") get(wikipedia("en").newBuilder().addPathSegment(clean.replace(' ', '_')).build()) else null
        val root = json.parseToJsonElement(body ?: return null).jsonObject
        if (root["type"]?.jsonPrimitive?.contentOrNull == "disambiguation" && root["extract"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return null
        val extract = root["extract"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val url = root["content_urls"]?.jsonObject?.get("desktop")?.jsonObject?.get("page")?.jsonPrimitive?.contentOrNull.orEmpty()
        return ReadingArticle(root["title"]?.jsonPrimitive?.contentOrNull ?: clean, extract, url)
    }

    /** [text] from [from] into [to] (language codes), by MyMemory's free, keyless service. */
    suspend fun translate(text: String, from: String, to: String): String {
        val clean = text.trim().take(450)
        require(clean.isNotEmpty()) { "Choose some words to translate." }
        val source = from.substringBefore('-').lowercase().takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        val target = to.substringBefore('-').lowercase()
        val body = get(translator.newBuilder().addQueryParameter("q", clean).addQueryParameter("langpair", "$source|$target").build())
            ?: error("The translation service did not answer.")
        val root = json.parseToJsonElement(body).jsonObject
        val status = root["responseStatus"]?.jsonPrimitive?.contentOrNull
        val translated = root["responseData"]?.jsonObject?.get("translatedText")?.jsonPrimitive?.contentOrNull
        check(status == "200" && !translated.isNullOrBlank()) { "The translation service could not translate this now." }
        return Jsoup.parse(translated).text()
    }

    companion object { private const val AGENT = "Cosmotune/Reading (https://github.com/Vijay-Janarthanan/cosmotune)" }
}

/**
 * A StarDict dictionary on this device: the .ifo (its name and how entries are written), the .idx (sorted words with where their
 * entry is) and the .dict (the entries; a .dict.dz is unpacked once on import).
 */
internal class StarDict(private val directory: File) {
    val name: String
    private val types: String
    private val index: ByteArray
    private val starts: IntArray
    private val wide: Boolean
    init {
        val info = File(directory, "dictionary.ifo").readText().lines().associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
        name = info["bookname"]?.takeIf { it.isNotBlank() } ?: directory.name
        types = info["sametypesequence"].orEmpty()
        wide = info["idxoffsetbits"] == "64"
        index = File(directory, "dictionary.idx").readBytes()
        val list = ArrayList<Int>()
        var at = 0
        val tail = if (wide) 12 else 8
        while (at < index.size) {
            list += at
            var end = at
            while (end < index.size && index[end] != 0.toByte()) end++
            at = end + 1 + tail
        }
        starts = list.toIntArray()
    }
    val words get() = starts.size

    private fun word(i: Int): String {
        var end = starts[i]
        while (end < index.size && index[end] != 0.toByte()) end++
        return String(index, starts[i], end - starts[i], Charsets.UTF_8)
    }

    /** The entries for [query]: the same word in any case, else its nearest form without a common ending. */
    fun lookup(query: String): List<String> {
        val forms = listOf(query.trim()) + listOf("'s", "s", "es", "ed", "d", "ing", "ly").mapNotNull { ending ->
            query.trim().takeIf { it.length > ending.length + 2 && it.endsWith(ending, true) }?.dropLast(ending.length)
        }
        for (form in forms) {
            val found = find(form)
            if (found.isNotEmpty()) return found.take(5).map(::entry)
        }
        return emptyList()
    }

    private fun find(form: String): List<Int> {
        val key = form.lowercase()
        var low = 0; var high = starts.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val compared = word(middle).lowercase().compareTo(key)
            if (compared < 0) low = middle + 1 else high = middle - 1
        }
        val hits = ArrayList<Int>()
        var i = low
        while (i < starts.size && word(i).lowercase() == key && hits.size < 10) { hits += i; i++ }
        if (hits.isEmpty()) for (j in starts.indices) if (word(j).equals(form, true)) { hits += j; if (hits.size >= 5) break }
        return hits
    }

    private fun entry(i: Int): String {
        var at = starts[i]
        while (index[at] != 0.toByte()) at++
        at++
        val offset = if (wide) u64(at) else u32(at)
        val size = u32(at + if (wide) 8 else 4).toInt().coerceIn(0, 1024 * 1024)
        val bytes = ByteArray(size)
        RandomAccessFile(File(directory, "dictionary.dict"), "r").use { it.seek(offset); it.readFully(bytes) }
        val parts = ArrayList<Pair<Char, String>>()
        if (types.isNotEmpty()) {
            var from = 0
            types.forEachIndexed { k, type ->
                val last = k == types.lastIndex
                val end = if (last) bytes.size else (from until bytes.size).firstOrNull { bytes[it] == 0.toByte() } ?: bytes.size
                parts += type to String(bytes, from, end - from, Charsets.UTF_8)
                from = (end + 1).coerceAtMost(bytes.size)
            }
        } else {
            var from = 0
            while (from < bytes.size) {
                val type = bytes[from].toInt().toChar(); from++
                val end = (from until bytes.size).firstOrNull { bytes[it] == 0.toByte() } ?: bytes.size
                parts += type to String(bytes, from, end - from, Charsets.UTF_8)
                from = end + 1
            }
        }
        return parts.filter { it.first.lowercaseChar() in "mhgxtly" }.joinToString("\n") { (type, value) ->
            if (type.lowercaseChar() in "hgx") Jsoup.parse(value.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")).wholeText().trim() else value.trim()
        }.trim()
    }

    private fun u32(at: Int): Long = ((index[at].toLong() and 0xFF) shl 24) or ((index[at + 1].toLong() and 0xFF) shl 16) or
        ((index[at + 2].toLong() and 0xFF) shl 8) or (index[at + 3].toLong() and 0xFF)
    private fun u64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)

    companion object {
        /** Unpacks a StarDict pack (a ZIP or tar.gz holding .ifo, .idx or .idx.gz, .dict or .dict.dz) into [target]. */
        fun install(archive: File, target: File): StarDict {
            target.mkdirs()
            val found = HashMap<String, File>()
            fun keep(name: String, open: () -> java.io.InputStream) {
                val lower = name.lowercase()
                val kind = when {
                    lower.endsWith(".ifo") -> "ifo"; lower.endsWith(".idx") -> "idx"; lower.endsWith(".idx.gz") -> "idx.gz"
                    lower.endsWith(".dict") -> "dict"; lower.endsWith(".dict.dz") -> "dict.dz"; else -> return
                }
                if (kind in found) return
                val plain = kind.removeSuffix(".gz").removeSuffix(".dz")
                val file = File(target, "dictionary.$plain")
                open().use { raw ->
                    val input = if (kind.endsWith("gz") || kind.endsWith("dz")) GZIPInputStream(raw) else raw
                    file.outputStream().use { output -> copyLimited(input, output) }
                }
                found[kind] = file; found[plain] = file
            }
            val head = archive.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) ZipFile(archive).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry -> keep(entry.name) { zip.getInputStream(entry) } }
            } else if (head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) GZIPInputStream(archive.inputStream().buffered()).use { tar -> readTar(tar, ::keep) }
            else throw IllegalArgumentException("Choose a StarDict dictionary as a ZIP or .tar.gz file.")
            require(File(target, "dictionary.ifo").isFile && File(target, "dictionary.idx").isFile && File(target, "dictionary.dict").isFile) {
                "This file is not a StarDict dictionary (it needs its .ifo, .idx and .dict)."
            }
            return StarDict(target)
        }

        private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream) {
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 512L * 1024 * 1024) { "This dictionary is too large." }; output.write(buffer, 0, count) }
        }

        /** The files of a plain tar stream, each handed to [each] with a stream of just its bytes. */
        private fun readTar(input: java.io.InputStream, each: (String, () -> java.io.InputStream) -> Unit) {
            val header = ByteArray(512)
            while (true) {
                var read = 0
                while (read < 512) { val count = input.read(header, read, 512 - read); if (count < 0) return; read += count }
                if (header.all { it == 0.toByte() }) return
                val name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
                val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLongOrNull(8) ?: return
                val type = header[156].toInt().toChar()
                val bounded = object : java.io.InputStream() {
                    var left = size
                    override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (left <= 0) return -1
                        val count = input.read(b, off, minOf(len.toLong(), left).toInt()); if (count > 0) left -= count; return count
                    }
                    override fun close() {}
                }
                if (type == '0' || type == '\u0000') each(name) { bounded }
                while (bounded.left > 0) { if (bounded.skip(bounded.left) <= 0 && bounded.read() < 0) return }
                val padding = (512 - size % 512) % 512
                var skipped = 0L
                while (skipped < padding) { val n = input.skip(padding - skipped); if (n <= 0) { if (input.read() < 0) return; skipped++ } else skipped += n }
            }
        }
    }
}
