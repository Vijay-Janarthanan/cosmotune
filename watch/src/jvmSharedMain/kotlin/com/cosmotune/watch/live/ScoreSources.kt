package com.cosmotune.watch.live

import com.cosmotune.watch.model.LiveEvent
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * One free score service, keyless. [events] answers what it knows now; [fresh] asks it to read its source again rather than
 * use a recent answer (the person asked to refresh). It throws when its source fails, so the last good answer can stand.
 */
interface ScoreSource {
    val name: String
    suspend fun events(nowMs: Long, fresh: Boolean = false): List<LiveEvent>
}

/** Reads `2026-11-06T14:00Z`, `2026-11-06T14:00:00Z`, `2026-11-06T14:00:00+00:00` or `2026-11-06T14:00:00` (UTC) as epoch ms. */
internal fun utcMs(text: String?): Long? {
    val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(t.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

/** Which countries a match belongs to, for ranking, and which cricket is big: national sides by name, leagues by name. */
internal object SportPlaces {
    val NATIONS = mapOf(
        "india" to "in", "australia" to "au", "england" to "gb", "pakistan" to "pk", "south africa" to "za", "new zealand" to "nz",
        "sri lanka" to "lk", "bangladesh" to "bd", "west indies" to "", "afghanistan" to "af", "ireland" to "ie", "zimbabwe" to "zw",
        "netherlands" to "nl", "scotland" to "gb", "nepal" to "np", "united arab emirates" to "ae", "uae" to "ae", "oman" to "om",
        "united states of america" to "us", "usa" to "us", "namibia" to "na", "canada" to "ca",
        // football's nations beyond cricket's
        "brazil" to "br", "argentina" to "ar", "france" to "fr", "germany" to "de", "spain" to "es", "italy" to "it", "portugal" to "pt",
        "belgium" to "be", "croatia" to "hr", "uruguay" to "uy", "mexico" to "mx", "united states" to "us", "japan" to "jp",
        "south korea" to "kr", "morocco" to "ma", "wales" to "gb", "denmark" to "dk", "switzerland" to "ch", "colombia" to "co",
    )

    /** A cricket league (or ICC event) by a word in its name, and the country it is played in ("" for several). */
    val CRICKET_LEAGUES = listOf(
        "indian premier league" to "in", "ipl" to "in", "women's premier league" to "in", "wpl" to "in", "big bash" to "au",
        "pakistan super league" to "pk", "caribbean premier league" to "", "sa20" to "za", "the hundred" to "gb",
        "lanka premier league" to "lk", "bangladesh premier league" to "bd", "international league t20" to "ae", "ilt20" to "ae",
        "major league cricket" to "us", "icc" to "", "world cup" to "", "champions trophy" to "", "asia cup" to "", "world test championship" to "",
    )

    /** Domestic competitions of the big cricket countries: listed under Cricket, not counted as big. */
    val CRICKET_DOMESTIC = listOf("ranji trophy" to "in", "syed mushtaq ali" to "in", "vijay hazare" to "in", "duleep trophy" to "in",
        "sheffield shield" to "au", "county championship" to "gb", "vitality blast" to "gb", "plunket shield" to "nz", "super smash" to "nz")

    /** A national side's country ("India Women" is India's), null for a club, a franchise, an A side or an under-19 side. */
    fun nation(team: String): String? {
        val t = team.lowercase().trim().removeSuffix(" women").removeSuffix(" w").trim()
        return NATIONS[t]
    }

    fun cricketLeague(name: String): Pair<String, String>? = match(CRICKET_LEAGUES, name)

    fun cricketDomestic(name: String): Pair<String, String>? = match(CRICKET_DOMESTIC, name)

    private fun match(list: List<Pair<String, String>>, name: String): Pair<String, String>? {
        val n = name.lowercase()
        return list.firstOrNull { (word, _) -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(n) }
    }
}
