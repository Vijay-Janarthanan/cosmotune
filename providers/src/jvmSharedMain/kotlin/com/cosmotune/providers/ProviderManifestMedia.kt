package com.cosmotune.providers

import java.io.File
import java.net.URI
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Finite audio manifests are fetched by the package's transport, never by native FFmpeg. */
internal class ProviderManifestMedia(private val network: ProviderNetwork, private val maximumBytes: Long) {
    private data class Part(val url: String, val range: String? = null)
    private data class Plan(val parts: List<Part>, val extension: String)
    private var requests = 0
    private val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(4)
    private fun ready() {
        check(!network.isCancelled && !Thread.currentThread().isInterrupted) { "Provider manifest cancelled" }
        check(System.nanoTime() < deadline && ++requests <= 1_024) { "Provider manifest work limit exceeded" }
    }
    private fun sameOrigin(a: String, b: String): Boolean {
        val x = network.address(a); val y = network.address(b)
        return x.scheme == y.scheme && x.host == y.host && x.port == y.port
    }
    private fun headers(root: String, target: String, supplied: JsonObject): JsonObject =
        if (sameOrigin(root, target)) supplied else JsonObject(emptyMap())

    fun download(url: String, targetBase: File, supplied: JsonObject, suppliedAudioKey: Boolean): File {
        require(maximumBytes in 1..512L * 1024 * 1024)
        fun manifest(address: String): Pair<String, String> {
            ready()
            val response = network.request(address, buildJsonObject { put("headers", headers(url, address, supplied)) })
            require(response["ok"]?.jsonPrimitive?.boolean == true) { "Provider manifest request failed" }
            return response.getValue("url").jsonPrimitive.content to response.getValue("body").jsonPrimitive.content.removePrefix("\uFEFF")
        }
        var (base, text) = manifest(url)
        val plan = if (text.trimStart().startsWith("#EXTM3U")) {
            // Follow at most one master to one finite audio rendition. No recursive playlist graph.
            val lines = text.lines().map(String::trim)
            val audio = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA:") && attributes(it.substringAfter(':'))["TYPE"] == "AUDIO" && attributes(it.substringAfter(':'))["URI"] != null }
            val variant = audio?.let { attributes(it.substringAfter(':'))["URI"] }
                ?: lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }.takeIf { it >= 0 }?.let { i -> lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') } }
            if (variant != null) { val child = resolve(base, variant); val fetched = manifest(child); base = fetched.first; text = fetched.second }
            hls(base, text)
        } else dash(base, text, suppliedAudioKey)
        require(plan.parts.isNotEmpty() && plan.parts.size <= 1_000)
        val output = File(targetBase.parentFile, targetBase.name + ".${plan.extension}")
        var bytes = 0L
        try {
            output.outputStream().use { sink ->
                output.ownerOnly()
                for (part in plan.parts) {
                    ready()
                    val options = buildJsonObject { putJsonObject("headers") {
                        headers(url, part.url, supplied).forEach { (name, value) -> if (!name.equals("Range", true) && !name.equals("If-Range", true)) put(name, value) }
                        part.range?.let { put("Range", "bytes=$it") }
                    } }
                    network.withResponse(part.url, options, media = true) { response ->
                        require(response.isSuccessful) { "Provider audio segment request failed" }
                        if (part.range != null) {
                            require(response.code == 206 && response.header("Content-Range").orEmpty().startsWith("bytes ${part.range}/")) { "Provider ignored segment byte range" }
                        } else require(response.code == 200) { "Provider returned an incomplete audio segment" }
                        val expected = response.body.contentLength()
                        require(expected <= maximumBytes - bytes)
                        var received = 0L
                        val buffer = ByteArray(64 * 1024)
                        response.body.byteStream().use { source ->
                            while (true) {
                                check(!network.isCancelled && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline) { "Provider manifest cancelled" }
                                val count = source.read(buffer); if (count < 0) break
                                require(count.toLong() <= maximumBytes - bytes) { "Provider manifest exceeds workspace quota" }
                                sink.write(buffer, 0, count); bytes += count; received += count
                            }
                        }
                        require(received > 0 && (expected < 0 || received == expected)) { "Provider audio segment is incomplete" }
                        part.range?.let { require(received == it.substringAfter('-').toLong() - it.substringBefore('-').toLong() + 1) }
                    }
                }
                sink.fd.sync()
            }
            require(bytes > 0); return output
        } catch (error: Throwable) { output.delete(); throw error }
    }

    private fun resolve(base: String, value: String): String {
        require(value.isNotBlank() && value.length <= 16_384)
        val source = network.address(base); val target = network.address(URI(base).resolve(value).toString())
        require(!source.isHttps || target.isHttps) { "Provider manifest cannot downgrade HTTPS" }
        return target.toString()
    }
    private fun hls(base: String, text: String): Plan {
        val lines = text.lines().map(String::trim)
        require(lines.firstOrNull() == "#EXTM3U" && "#EXT-X-ENDLIST" in lines) { "Only finite HLS audio is supported" }
        require(lines.none { it.startsWith("#EXT-X-STREAM-INF:") || it == "#EXT-X-DISCONTINUITY" || it.startsWith("#EXT-X-BYTERANGE:") }) { "Unsupported HLS audio layout" }
        require(lines.filter { it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-SESSION-KEY:") }.all { attributes(it.substringAfter(':'))["METHOD"] == "NONE" }) { "HLS key acquisition is not supported" }
        val parts = ArrayList<Part>(); var initialized = false
        for (line in lines) {
            if (line.startsWith("#EXT-X-MAP:")) {
                require(!initialized) { "Changing HLS initialization is unsupported" }
                val map = attributes(line.substringAfter(':')); require(map["BYTERANGE"] == null)
                parts += Part(resolve(base, requireNotNull(map["URI"]))); initialized = true
            } else if (line.isNotBlank() && !line.startsWith('#')) parts += Part(resolve(base, line))
            require(parts.size <= 1_000)
        }
        return Plan(parts, if (initialized) "mp4" else "ts")
    }

    private fun dash(base: String, text: String, suppliedAudioKey: Boolean): Plan {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false; isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(InputSource(text.reader())).documentElement
        require(root.localName == "MPD" && root.getAttribute("type") != "dynamic") { "Only static DASH audio is supported" }
        require(suppliedAudioKey || root.getElementsByTagNameNS("*", "ContentProtection").length == 0) { "DASH requires a provider-supplied audio key" }
        val periods = root.children("Period"); require(periods.size == 1) { "Multi-period DASH is unsupported" }
        val period = periods.single()
        val adaptation = period.children("AdaptationSet").firstOrNull { it.getAttribute("contentType") == "audio" || it.getAttribute("mimeType").startsWith("audio/") || it.children("Representation").any { r -> r.getAttribute("mimeType").startsWith("audio/") } } ?: error("DASH contains no audio adaptation")
        val representation = adaptation.children("Representation").maxByOrNull { it.getAttribute("bandwidth").toLongOrNull() ?: 0 } ?: error("DASH contains no audio representation")
        var address = base
        for (element in listOf(root, period, adaptation, representation)) element.children("BaseURL").firstOrNull()?.textContent?.trim()?.let { address = resolve(address, it) }
        val list = representation.children("SegmentList").firstOrNull() ?: adaptation.children("SegmentList").firstOrNull()
        fun range(value: String): String? = value.takeIf(String::isNotBlank)?.also { require(it.matches(Regex("[0-9]{1,12}-[0-9]{1,12}")) && it.substringBefore('-').toLong() <= it.substringAfter('-').toLong()) }
        if (list != null) {
            val parts = ArrayList<Part>()
            list.children("Initialization").firstOrNull()?.let { parts += Part(resolve(address, it.getAttribute("sourceURL").ifBlank { address }), range(it.getAttribute("range"))) }
            list.children("SegmentURL").forEach { parts += Part(resolve(address, it.getAttribute("media").ifBlank { address }), range(it.getAttribute("mediaRange"))) }
            return Plan(parts, "mp4")
        }
        val template = representation.children("SegmentTemplate").firstOrNull() ?: adaptation.children("SegmentTemplate").firstOrNull()
        if (template == null) { require(address != base) { "DASH audio has no segment addressing" }; return Plan(listOf(Part(address)), "mp4") }
        val id = representation.getAttribute("id"); require(id.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
        val bandwidth = representation.getAttribute("bandwidth").takeIf { it.matches(Regex("[0-9]{1,12}")) }.orEmpty()
        val media = template.getAttribute("media"); val init = template.getAttribute("initialization")
        fun expand(pattern: String, number: Long, time: Long): String {
            var result = pattern.replace("\$RepresentationID\$", id).replace("\$Bandwidth\$", bandwidth).replace("\$Time\$", time.toString()).replace("\$Number\$", number.toString())
            result = Regex("\\\$Number%0([1-9])d\\\$").replace(result) { number.toString().padStart(it.groupValues[1].toInt(), '0') }
            require('$' !in result); return resolve(address, result)
        }
        val parts = ArrayList<Part>(); if (init.isNotBlank()) parts += Part(expand(init, 0, 0))
        var number = template.getAttribute("startNumber").toLongOrNull() ?: 1L
        require(number in 0..1_000_000_000)
        val timeline = template.children("SegmentTimeline").firstOrNull()
        if (timeline != null) {
            var time = 0L
            for (segment in timeline.children("S")) {
                val duration = segment.getAttribute("d").toLongOrNull() ?: error("Invalid DASH duration")
                val repeat = segment.getAttribute("r").toIntOrNull() ?: 0
                require(duration in 1..1_000_000_000 && repeat in 0..999)
                time = segment.getAttribute("t").toLongOrNull() ?: time; require(time >= 0)
                repeat(repeat + 1) { require(parts.size < 1_000); parts += Part(expand(media, number++, time)); time += duration }
            }
        } else {
            val seconds = runCatching { Duration.parse(period.getAttribute("duration").ifBlank { root.getAttribute("mediaPresentationDuration") }).toMillis() / 1000.0 }.getOrElse { error("DASH requires a finite duration") }
            val scale = template.getAttribute("timescale").toLongOrNull() ?: 1L
            val duration = template.getAttribute("duration").toLongOrNull() ?: error("DASH segment duration is missing")
            require(seconds > 0 && seconds <= 86_400 && scale in 1..1_000_000_000 && duration in 1..1_000_000_000)
            val count = kotlin.math.ceil(seconds * scale / duration).toInt(); require(count in 1..999)
            repeat(count) { parts += Part(expand(media, number++, it * duration)) }
        }
        return Plan(parts, "mp4")
    }
    private fun Element.children(name: String): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.localName == name }
    companion object {
        fun isManifest(raw: JsonObject): Boolean {
            val format = ((raw["transport"] ?: raw["format"]) as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
            val path = runCatching { URI(raw.getValue("url").jsonPrimitive.content).path.orEmpty().lowercase() }.getOrDefault("")
            return format in setOf("hls", "dash", "m3u8", "mpd") || path.endsWith(".m3u8") || path.endsWith(".mpd")
        }
        private fun attributes(text: String): Map<String, String> = Regex("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))").findAll(text).associate { it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] } }
    }
}
