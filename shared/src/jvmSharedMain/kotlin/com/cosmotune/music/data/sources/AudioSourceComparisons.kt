package com.cosmotune.music.data.sources

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** In-memory, bounded diagnostics. Never contains stream URLs, headers, credentials or raw exceptions. */
object AudioSourceComparisons {
    enum class Status(val label: String) {
        SEARCHING("Checking recording and stream"), AVAILABLE("Stream offer resolved"), NO_MATCH("No compatible recording returned"),
        ACCESS_REQUIRED("Sign-in, verification or provider access required"),
        UNSUPPORTED("Required provider feature is not available"), QUALITY_LIMIT("No stream within the requested quality limit"),
        REQUEST_FAILED("Provider request failed"), UNAVAILABLE("Could not obtain a playable stream"), TIMEOUT("Lookup timed out"), CANCELLED("Lookup stopped"),
    }
    data class Offer(val id: String, val name: String, val status: Status, val formats: List<StreamFormat> = emptyList(), val httpStatus: Int? = null)
    data class Report(val offers: List<Offer> = emptyList())
    class Ticket internal constructor(val key: String, val sourceId: String, val generation: Long)
    private val lock = Any()
    private var generation = 0L
    private val tickets = mutableMapOf<Pair<String, String>, Long>()
    private val reports = MutableStateFlow<Map<String, Report>>(emptyMap())
    val state = reports.asStateFlow()

    /** Native YouTube and a YouTube provider package can share a display name but are different sources. */
    fun playingOffer(report: Report?, sourceId: String?, sourceName: String?): Offer? {
        val offers = report?.offers.orEmpty()
        if (!sourceId.isNullOrBlank()) return offers.firstOrNull { it.id == sourceId }
        return offers.filter { it.id == sourceName || it.name == sourceName }.singleOrNull()
    }

    fun key(target: TrackMatcher.Target): String = listOf(target.title, target.artist, target.album.orEmpty(),
        target.isExplicit?.toString().orEmpty(), target.isVideo.toString()).joinToString("\u001f") {
        it.trim().lowercase(Locale.ROOT).take(2_000)
    }

    fun start(target: TrackMatcher.Target, sourceId: String, sourceName: String): Ticket = synchronized(lock) {
        val key = key(target)
        val ticket = Ticket(key, sourceId.take(200), ++generation)
        tickets[key to ticket.sourceId] = ticket.generation
        val previous = reports.value[key]?.offers.orEmpty().filterNot { it.id == ticket.sourceId }
        val report = Report((previous + Offer(ticket.sourceId, sourceName.take(200), Status.SEARCHING)).takeLast(100))
        val next = reports.value.toMutableMap().apply { remove(key); put(key, report) }.entries.toList().takeLast(12).associate { it.toPair() }
        reports.value = next
        tickets.keys.removeAll { it.first !in next }
        ticket
    }

    fun failed(ticket: Ticket, error: Throwable) {
        val message = error.message.orEmpty().lowercase(Locale.ROOT)
        val status = when {
            Regex("sign.?in|auth|verif|\\b40[13]\\b|token|session").containsMatchIn(message) -> Status.ACCESS_REQUIRED
            Regex("manifest-aware|ffmpeg|runtime features|native_processing_required|unsupported|not supported|does not expose").containsMatchIn(message) -> Status.UNSUPPORTED
            Regex("bitrate limit|requested bitrate|quality within|capped audio").containsMatchIn(message) -> Status.QUALITY_LIMIT
            else -> Status.REQUEST_FAILED
        }
        val httpStatus = Regex("\\bhttp\\s+(\\d{3})\\b").find(message)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 400..599 }
        finish(ticket, status, httpStatus = httpStatus)
    }

    fun finish(ticket: Ticket, status: Status, formats: List<StreamFormat> = emptyList(), httpStatus: Int? = null) = synchronized(lock) {
        if (tickets[ticket.key to ticket.sourceId] != ticket.generation) return@synchronized
        val report = reports.value[ticket.key] ?: return@synchronized
        reports.value = reports.value + (ticket.key to report.copy(offers = report.offers.map {
            if (it.id != ticket.sourceId) it else it.copy(
                status = if (status in setOf(Status.UNAVAILABLE, Status.NO_MATCH) && it.status in setOf(Status.ACCESS_REQUIRED, Status.UNSUPPORTED, Status.QUALITY_LIMIT, Status.REQUEST_FAILED)) it.status else status,
                httpStatus = if (status == Status.AVAILABLE) null else httpStatus ?: it.httpStatus,
                formats = formats.distinct().take(4).map { format -> format.copy(
                    codec = format.codec?.takeIf { name -> name.matches(Regex("[A-Za-z0-9_./-]{1,60}")) },
                    kbps = format.kbps?.takeIf { rate -> rate in 1..100_000 },
                    sampleRateHz = format.sampleRateHz?.takeIf { rate -> rate in 1..25_000_000 },
                    bitDepth = format.bitDepth?.takeIf { depth -> depth in 1..64 }) })
        }))
    }
}

/** Technical offer ordering, never a claim that bitrate ranks different lossy codecs or masters. */
object StreamQualityPolicy {
    fun better(candidate: StreamFormat, current: StreamFormat?, preferAtmos: Boolean = false): Boolean {
        current ?: return true
        if (preferAtmos && candidate.isDolbyAtmos != current.isDolbyAtmos) return candidate.isDolbyAtmos
        if (candidate.isLossless != current.isLossless) return candidate.isLossless == true
        if (candidate.isLossless == true) {
            if (candidate.bitDepth != current.bitDepth) return (candidate.bitDepth ?: 0) > (current.bitDepth ?: 0)
            return (candidate.sampleRateHz ?: 0) > (current.sampleRateHz ?: 0)
        }
        // An immersive mix is different from stereo, not an automatic fidelity upgrade.
        if (family(candidate.codec) != family(current.codec)) return false
        return (candidate.kbps ?: 0) > (current.kbps ?: 0)
    }
    private fun family(codec: String?): String? = when (val name = codec?.lowercase(Locale.ROOT)?.substringAfterLast('/')) {
        "mp4", "m4a", "aac", "mp4a", "mp4a-latm" -> "aac"
        "mp3", "mpeg" -> "mp3"
        else -> name
    }
}
