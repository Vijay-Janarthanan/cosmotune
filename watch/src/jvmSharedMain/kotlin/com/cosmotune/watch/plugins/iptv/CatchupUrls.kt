package com.cosmotune.watch.plugins.iptv

import com.cosmotune.watch.model.Catchup
import java.time.Instant
import java.time.ZoneOffset

/**
 * The address of a past programme on a channel with catch-up, the way Kodi's IPTV Simple builds it (ideas from its README,
 * GPL-2.0-or-later; written here): `default` fills the playlist's template, `append` adds the template to the live address,
 * `shift` adds `utc` and `lutc`, `flussonic` rewrites the path to the archive, and `xc` asks an Xtream server's timeshift.
 * Template fields: {utc} {start} {lutc} {now} {timestamp} {end} {utcend} {duration} {offset} and {Y} {m} {d} {H} {M} {S}
 * (UTC), each also as ${...}. Null when the mode needs a template the playlist did not give, or the live address does not fit.
 */
object CatchupUrls {
    fun build(live: String, catchup: Catchup, startMs: Long, endMs: Long, nowMs: Long): String? {
        if (endMs <= startMs || startMs >= nowMs) return null
        val start = startMs / 1000
        val end = endMs / 1000
        val now = nowMs / 1000
        return when (catchup.mode.lowercase()) {
            "default" -> catchup.source?.let { fill(it, start, end, now) }
            "append" -> catchup.source?.let { live + fill(it, start, end, now) }
            "shift", "timeshift" -> live + (if ('?' in live) "&" else "?") + "utc=$start&lutc=$now"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(live, start, end - start)
            "xc" -> xtream(live, start, end - start)
            else -> null
        }
    }

    private fun fill(template: String, start: Long, end: Long, now: Long): String {
        val t = Instant.ofEpochSecond(start).atOffset(ZoneOffset.UTC)
        val values = mapOf(
            "utc" to "$start", "start" to "$start", "timestamp" to "$now", "lutc" to "$now", "now" to "$now", "end" to "$end", "utcend" to "$end",
            "duration" to "${end - start}", "offset" to "${now - start}",
            "Y" to "%04d".format(t.year), "m" to "%02d".format(t.monthValue), "d" to "%02d".format(t.dayOfMonth),
            "H" to "%02d".format(t.hour), "M" to "%02d".format(t.minute), "S" to "%02d".format(t.second),
        )
        var out = template
        for ((key, value) in values) out = out.replace("\${$key}", value).replace("{$key}", value)
        return out
    }

    /** `.../channel/index.m3u8` → `.../channel/index-<start>-<duration>.m3u8`; `.../channel/mpegts` → `.../channel/timeshift_abs-<start>.ts`. */
    private fun flussonic(live: String, start: Long, duration: Long): String? {
        val base = live.substringBefore('?')
        val query = live.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val folder = base.substringBeforeLast('/')
        val last = base.substringAfterLast('/')
        return when {
            last.endsWith(".m3u8") -> "$folder/${last.removeSuffix(".m3u8")}-$start-$duration.m3u8$query"
            last == "mpegts" -> "$folder/timeshift_abs-$start.ts$query"
            else -> null
        }
    }

    /** `http://host/live/user/pass/123.ts` → `http://host/timeshift/user/pass/<minutes>/<Y-m-d:H-M>/123.ts`. */
    private fun xtream(live: String, start: Long, duration: Long): String? {
        val match = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(\\.[a-z0-9]+)?$").find(live.substringBefore('?')) ?: return null
        val (host, user, pass, id, ext) = match.destructured
        val t = Instant.ofEpochSecond(start).atOffset(ZoneOffset.UTC)
        val at = "%04d-%02d-%02d:%02d-%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
        return "$host/timeshift/$user/$pass/${(duration + 59) / 60}/$at/$id${ext.ifEmpty { ".ts" }}"
    }
}
