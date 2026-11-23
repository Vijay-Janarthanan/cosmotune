package com.cosmotune.ui.live

import androidx.compose.runtime.Composable
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.watch.live.EspnFootball
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A match the person asked to be told about before it starts ("Remind" on a "Coming up" row). */
data class LiveReminder(val id: String, val title: String, val startMs: Long)

/** A side the Spotlight has seen, offered in Settings to follow: its name, short name, crest and competition. */
data class KnownTeam(val name: String, val shortName: String?, val logoUrl: String?, val competition: String?, val kind: LiveEventKind)

/** A competition offered in Settings to follow: its source id ("eng.1", a cricket series id) and name. */
data class KnownCompetition(val id: String, val name: String, val kind: LiveEventKind)

/**
 * The Spotlight's choices, kept on this device only (SharedPreferences `cosmotune_live` on Android, the Preferences node
 * `com.cosmotune.desktop/live` on the desktop): whether the strip shows at all, which sports it shows, the teams and competitions
 * followed (listed first, and told about when they score), the reminders set on "Coming up" rows, and the teams and competitions
 * seen so far (the lists Settings searches). Main thread only.
 */
class LiveNowSettings(private val store: KeyValueStore) {
    private val pillFlow = MutableStateFlow(store.getBoolean(KEY_PILL, true))
    private val sportsFlow = MutableStateFlow(readSports())
    private val teamsFlow = MutableStateFlow(readList(KEY_TEAMS))
    private val competitionsFlow = MutableStateFlow(readList(KEY_COMPETITIONS))
    private val remindersFlow = MutableStateFlow(readReminders())
    private val knownTeamsFlow = MutableStateFlow(readKnownTeams())
    private val knownCompetitionsFlow = MutableStateFlow(BUILT_IN_COMPETITIONS + readKnownCompetitions().filter { k -> BUILT_IN_COMPETITIONS.none { it.id == k.id } })

    /** The strip in every mode; off hides it (and so the sheet) everywhere. */
    val pillOn: StateFlow<Boolean> = pillFlow.asStateFlow()

    /** The kinds shown: cricket, football and live shows, all three until the person drops one. */
    val sports: StateFlow<Set<LiveEventKind>> = sportsFlow.asStateFlow()

    /** The teams followed, by name ("India", "Arsenal"). */
    val teams: StateFlow<List<String>> = teamsFlow.asStateFlow()

    /** The competitions followed, by source id ("eng.1"). */
    val competitions: StateFlow<List<String>> = competitionsFlow.asStateFlow()

    val reminders: StateFlow<List<LiveReminder>> = remindersFlow.asStateFlow()

    /** The sides seen so far, for Settings' list. */
    val knownTeams: StateFlow<List<KnownTeam>> = knownTeamsFlow.asStateFlow()

    /** ESPN's football competitions and the cricket series seen so far, for Settings' list. */
    val knownCompetitions: StateFlow<List<KnownCompetition>> = knownCompetitionsFlow.asStateFlow()

    fun setPillOn(on: Boolean) { store.putBoolean(KEY_PILL, on); pillFlow.value = on }

    fun setSport(kind: LiveEventKind, on: Boolean) {
        val next = if (on) sportsFlow.value + kind else sportsFlow.value - kind
        store.putString(KEY_SPORTS, next.joinToString(SEP) { it.name })
        // An empty set is kept as "none", so it does not read back as the default (all).
        if (next.isEmpty()) store.putString(KEY_SPORTS, NONE)
        sportsFlow.value = next
    }

    fun setTeam(name: String, on: Boolean) = setIn(teamsFlow, KEY_TEAMS, name, on)

    fun setCompetition(id: String, on: Boolean) = setIn(competitionsFlow, KEY_COMPETITIONS, id, on)

    private fun setIn(flow: MutableStateFlow<List<String>>, key: String, value: String, on: Boolean) {
        val clean = value.trim().replace(SEP, " ")
        if (clean.isEmpty()) return
        val now = flow.value
        val next = if (on) (if (now.any { it.equals(clean, ignoreCase = true) }) now else now + clean) else now.filterNot { it.equals(clean, ignoreCase = true) }
        store.putString(key, next.joinToString(SEP))
        flow.value = next
    }

    /** Keeps the sides and competitions of [events] for Settings' lists (the most recent [MAX_KNOWN] sides). */
    fun learn(events: List<LiveEvent>) {
        val matches = events.filter { it.isMatch }
        if (matches.isEmpty()) return
        val seen = matches.flatMap { e -> e.teams.map { t -> KnownTeam(t.name, t.shortName, t.logoUrl, e.competition, e.kind) } }
        val teams = (seen + knownTeamsFlow.value).distinctBy { it.kind to it.name.lowercase() }.take(MAX_KNOWN)
        if (teams != knownTeamsFlow.value) {
            knownTeamsFlow.value = teams
            store.putString(KEY_KNOWN_TEAMS, teams.joinToString(LINE) { listOf(it.kind.name, it.name, it.shortName.orEmpty(), it.logoUrl.orEmpty(), it.competition.orEmpty()).joinToString(FIELD) { f -> f.replace(LINE, " ").replace(FIELD, " ") } })
        }
        val competitions = (knownCompetitionsFlow.value + matches.mapNotNull { e ->
            val id = e.competitionId ?: return@mapNotNull null
            KnownCompetition(id, e.competition ?: return@mapNotNull null, e.kind)
        }).distinctBy { it.id }.take(MAX_KNOWN)
        if (competitions != knownCompetitionsFlow.value) {
            knownCompetitionsFlow.value = competitions
            store.putString(KEY_KNOWN_COMPETITIONS, competitions.filter { c -> BUILT_IN_COMPETITIONS.none { it.id == c.id } }
                .joinToString(LINE) { listOf(it.kind.name, it.id, it.name).joinToString(FIELD) { f -> f.replace(LINE, " ").replace(FIELD, " ") } })
        }
    }

    fun isReminded(id: String): Boolean = remindersFlow.value.any { it.id == id }

    /** Sets or clears the reminder for [event] (a "Coming up" row's Remind). */
    fun toggleReminder(event: LiveEvent) {
        val start = event.startMs ?: return
        val now = remindersFlow.value
        val next = if (now.any { it.id == event.id }) now.filterNot { it.id == event.id } else now + LiveReminder(event.id, event.title, start)
        writeReminders(next)
    }

    /** Takes out the reminders that are due by [nowMs] (five minutes before the start) and returns them. */
    fun takeDue(nowMs: Long): List<LiveReminder> {
        val (due, later) = remindersFlow.value.partition { it.startMs - REMIND_BEFORE_MS <= nowMs }
        if (due.isNotEmpty()) writeReminders(later)
        return due
    }

    private fun writeReminders(list: List<LiveReminder>) {
        store.putString(KEY_REMINDERS, list.joinToString(LINE) { "${it.id}$FIELD${it.startMs}$FIELD${it.title.replace(LINE, " ").replace(FIELD, " ")}" })
        remindersFlow.value = list
    }

    private fun readSports(): Set<LiveEventKind> {
        val raw = store.getString(KEY_SPORTS, "")
        if (raw.isEmpty()) return followedKinds
        if (raw == NONE) return emptySet()
        return raw.split(SEP).mapNotNull { n -> LiveEventKind.entries.firstOrNull { it.name == n } }.filter { it in followedKinds }.toSet()
    }

    private fun readList(key: String): List<String> = store.getString(key, "").split(SEP).map { it.trim() }.filter { it.isNotEmpty() }

    private fun readReminders(): List<LiveReminder> = store.getString(KEY_REMINDERS, "").split(LINE).mapNotNull { line ->
        val parts = line.split(FIELD)
        if (parts.size < 3) null else parts[1].toLongOrNull()?.let { LiveReminder(parts[0], parts.drop(2).joinToString(" "), it) }
    }

    private fun readKnownTeams(): List<KnownTeam> = store.getString(KEY_KNOWN_TEAMS, "").split(LINE).mapNotNull { line ->
        val p = line.split(FIELD)
        if (p.size < 5) return@mapNotNull null
        val kind = LiveEventKind.entries.firstOrNull { it.name == p[0] } ?: return@mapNotNull null
        KnownTeam(p[1].ifBlank { return@mapNotNull null }, p[2].ifBlank { null }, p[3].ifBlank { null }, p[4].ifBlank { null }, kind)
    }

    private fun readKnownCompetitions(): List<KnownCompetition> = store.getString(KEY_KNOWN_COMPETITIONS, "").split(LINE).mapNotNull { line ->
        val p = line.split(FIELD)
        if (p.size < 3) return@mapNotNull null
        val kind = LiveEventKind.entries.firstOrNull { it.name == p[0] } ?: return@mapNotNull null
        KnownCompetition(p[1], p[2], kind)
    }

    companion object {
        val followedKinds = setOf(LiveEventKind.CRICKET, LiveEventKind.FOOTBALL, LiveEventKind.SHOW)
        /** ESPN's football competitions the Spotlight reads, each once (the three qualifier groups are one). */
        val BUILT_IN_COMPETITIONS: List<KnownCompetition> = EspnFootball.LEAGUES.distinctBy { it.name }.map { KnownCompetition(it.slug, it.name, LiveEventKind.FOOTBALL) }
        const val KEY_PILL = "live_pill"
        const val KEY_SPORTS = "live_sports"
        const val KEY_TEAMS = "live_teams"
        const val KEY_COMPETITIONS = "live_competitions"
        const val KEY_REMINDERS = "live_reminders"
        const val KEY_KNOWN_TEAMS = "live_known_teams"
        const val KEY_KNOWN_COMPETITIONS = "live_known_competitions"
        /** A reminder fires this long before the start. */
        const val REMIND_BEFORE_MS = 5 * 60_000L
        const val MAX_KNOWN = 600
        private const val SEP = "|"
        private const val LINE = "\n"
        private const val FIELD = "\t"
        private const val NONE = "none"
    }
}

/** An in-memory store, for previews and render tests (nothing is kept). */
class LiveNowMemoryStore : KeyValueStore {
    private val values = HashMap<String, Any>()
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun getString(key: String, default: String) = values[key] as? String ?: default
    override fun putString(key: String, value: String) { values[key] = value }
}

/** The process's one [LiveNowSettings], kept where the platform keeps settings. */
@Composable
expect fun rememberLiveNowSettings(): LiveNowSettings
