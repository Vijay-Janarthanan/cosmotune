package com.cosmotune.music.data.downloads

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** Shared bounded SegmentTemplate parser, adapted from the native offline DASH path. */
data class OfflineDashManifest(val initialization: String, val media: List<String>, val seconds: List<Double>) {
    companion object {
        fun parse(manifest: String): OfflineDashManifest {
            require(manifest.toByteArray(Charsets.UTF_8).size <= ManifestDownloadTransport.MAX_MANIFEST_BYTES) { "DASH manifest is too large" }
            if (Regex("<ContentProtection", RegexOption.IGNORE_CASE).containsMatchIn(manifest)) {
                error("Encrypted DASH cannot be saved")
            }
            if (Regex("<Period[\\s>]").findAll(manifest).count() > 1) {
                error("Multi-period DASH cannot be saved")
            }
            // Tidal lists every rendition of the track in one manifest — HE-AAC,
            // then AAC-LC, then FLAC — and reading the first SegmentTemplate in the
            // file took HE-AAC every time: 97kbps saved under a FLAC badge. The
            // player never showed it because Media3 picks the best one itself.
            val rendition = best(manifest)
            val scope = rendition?.body?.takeIf { Regex("<SegmentTemplate", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                ?: manifest
            val template = Regex("<SegmentTemplate([^>]*)>", RegexOption.IGNORE_CASE).find(scope)?.groupValues?.get(1)
                ?: error("DASH manifest has no segment template")
            fun attr(name: String) =
                Regex("""\b$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(template)?.groupValues?.get(1)

            val initialization = attr("initialization")?.let(::unescape)
                ?: error("DASH manifest has no initialization segment")
            val mediaTemplate = attr("media")?.let(::unescape)
                ?: error("DASH manifest has no media template")
            // Only `$Number$` is supported. `$Time$` would need the timeline's
            // running offsets rather than its durations, and no module serves it.
            if (!mediaTemplate.contains("\$Number\$")) error("Unsupported DASH media template")
            val rawTimescale = attr("timescale")
            val timescale = if (rawTimescale == null) 1.0 else requireNotNull(rawTimescale.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }) { "Invalid DASH timescale" }
            val startNumber = attr("startNumber")?.let { requireNotNull(it.toLongOrNull()) { "Invalid DASH start number" } } ?: 1L
            require(startNumber >= 0 && startNumber <= Long.MAX_VALUE - ManifestDownloadTransport.MAX_SEGMENTS)

            // A SegmentTimeline states each segment's real length, including the
            // short final one, which is what makes the EXTINF values below exact
            // rather than a division that leaves the last segment overstated.
            val ticks = mutableListOf<Long>()
            // From the chosen rendition's own template: each one ends on a
            // different final segment length.
            Regex("<SegmentTimeline>(.*?)</SegmentTimeline>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(scope)?.groupValues?.get(1)
                ?.let { timeline ->
                    Regex("<S\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(timeline).forEach { entry ->
                        val body = entry.groupValues[1]
                        fun of(name: String) =
                            Regex("""\b$name\s*=\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
                        val d = requireNotNull(of("d")?.toLongOrNull()?.takeIf { it > 0 }) { "Invalid DASH segment duration" }
                        // `r` is how many times the entry *repeats*, so r="56" is
                        // 57 segments. Reading it as a count drops one segment and
                        // truncates every download by a few seconds.
                        val rawRepeat = of("r")
                        val repeat = if (rawRepeat == null) 0L else rawRepeat.toLongOrNull() ?: error("Invalid DASH repeat count")
                        require(repeat >= 0 && repeat < ManifestDownloadTransport.MAX_SEGMENTS && ticks.size.toLong() + repeat + 1 <= ManifestDownloadTransport.MAX_SEGMENTS) { "DASH has too many or unsupported segments" }
                        repeat(repeat.toInt() + 1) { ticks += d }
                    }
                }
            if (ticks.isEmpty()) {
                // No timeline: fall back to a fixed duration repeated across the
                // presentation, which is the other legal way to write this.
                val d = attr("duration")?.toLongOrNull()?.takeIf { it > 0 }
                    ?: error("DASH manifest has neither a timeline nor a segment duration")
                val total = Regex("""mediaPresentationDuration\s*=\s*"([^"]*)"""").find(manifest)
                    ?.groupValues?.get(1)?.let(::isoSeconds)
                    ?: error("DASH manifest states no duration")
                val count = max(1, ceil(total / (d / timescale)).toInt())
                require(count <= ManifestDownloadTransport.MAX_SEGMENTS) { "DASH has too many segments" }
                repeat(count) { ticks += d }
            }

            val media = ticks.indices.map { at ->
                mediaTemplate.replace("\$Number\$", (startNumber + at).toString())
            }
            if (media.isEmpty()) error("DASH manifest has no segments")
            return OfflineDashManifest(initialization, media, ticks.map { it / timescale })
        }

        private class Rendition(val bandwidth: Long, val codecs: String, val body: String)

        /**
         * The rendition to keep: lossless if there is one, else the highest
         * bandwidth. Null for a manifest with no `Representation` at all, which
         * leaves the template wherever the manifest put it.
         */
        private fun best(manifest: String): Rendition? {
            val renditions = Regex(
                """<Representation\b([^>]*?)(?:/>|>(.*?)</Representation>)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).findAll(manifest).map { match ->
                val attrs = match.groupValues[1]
                fun attr(name: String) =
                    Regex("""\b$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
                Rendition(
                    bandwidth = attr("bandwidth")?.toLongOrNull() ?: 0L,
                    codecs = attr("codecs").orEmpty().lowercase(Locale.ROOT),
                    body = match.groupValues[2],
                )
            }.toList()
            return renditions.maxWithOrNull(
                compareBy<Rendition>({ it.codecs.isLossless() }, { it.bandwidth }),
            )
        }

        private fun String.isLossless() = startsWith("flac") || startsWith("alac")

        /** The five entities an MPD's URLs can arrive escaped with — `&amp;` being the one that always does. */
        private fun unescape(value: String) = value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

        /** `PT3M50.461S` → `230.461`. Hours, minutes and seconds, which is all a track ever needs. */
        private fun isoSeconds(value: String): Double? {
            val match = Regex("""PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?""")
                .find(value) ?: return null
            val (h, m, s) = match.destructured
            val seconds = (h.toDoubleOrNull() ?: 0.0) * 3600 +
                (m.toDoubleOrNull() ?: 0.0) * 60 +
                (s.toDoubleOrNull() ?: 0.0)
            return seconds.takeIf { it.isFinite() && it > 0 }
        }
    }
}
