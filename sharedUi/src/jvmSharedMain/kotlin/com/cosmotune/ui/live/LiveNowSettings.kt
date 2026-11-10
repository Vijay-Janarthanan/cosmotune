package com.cosmotune.ui.live

import androidx.compose.runtime.Composable
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A match the person asked to be told about before it starts ("Remind" on a "Coming up" row). */
data class LiveReminder(val id: String, val title: String, val startMs: Long)

/**
 * The "Live now" choices, kept on this device only (SharedPreferences `cosmotune_live` on Android, the Preferences node
 * `com.cosmotune.desktop/live` on the desktop): whether the pill shows at all, which sports it follows, the teams followed (none
 * means every team) and the reminders set on "Coming up" rows. Main thread only.
 */
class LiveNowSettings(private val store: KeyValueStore) {
    private val pillFlow = MutableStateFlow(store.getBoolean(KEY_PILL, true))
    private val sportsFlow = MutableStateFlow(readSports())
    private val teamsFlow = MutableStateFlow(readList(KEY_TEAMS))
    private val remindersFlow = MutableStateFlow(readReminders())

    /** The Live pill in every mode's top bar; off hides it (and so the panel) everywhere. */
    val pillOn: StateFlow<Boolean> = pillFlow.asStateFlow()

    /** The kinds followed: cricket, football and live shows, all three until the person drops one. */
    val sports: StateFlow<Set<LiveEventKind>> = sportsFlow.asStateFlow()

    /** The teams followed, as typed or chosen ("India", "CSK"); empty follows every team. */
    val teams: StateFlow<List<String>> = teamsFlow.asStateFlow()

    val reminders: StateFlow<List<LiveReminder>> = remindersFlow.asStateFlow()

    fun setPillOn(on: Boolean) { store.putBoolean(KEY_PILL, on); pillFlow.value = on }

    fun setSport(kind: LiveEventKind, on: Boolean) {
        val next = if (on) sportsFlow.value + kind else sportsFlow.value - kind
        store.putString(KEY_SPORTS, next.joinToString(SEP) { it.name })
        // An empty set is kept as "none", so it does not read back as the default (all).
        if (next.isEmpty()) store.putString(KEY_SPORTS, NONE)
        sportsFlow.value = next
    }

    fun setTeam(name: String, on: Boolean) {
        val clean = name.trim().replace(SEP, " ")
        if (clean.isEmpty()) return
        val now = teamsFlow.value
        val next = if (on) (if (now.any { it.equals(clean, ignoreCase = true) }) now else now + clean) else now.filterNot { it.equals(clean, ignoreCase = true) }
        store.putString(KEY_TEAMS, next.joinToString(SEP))
        teamsFlow.value = next
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
        return raw.split(SEP).mapNotNull { n -> LiveEventKind.entries.firstOrNull { it.name == n } }.toSet()
    }

    private fun readList(key: String): List<String> = store.getString(key, "").split(SEP).map { it.trim() }.filter { it.isNotEmpty() }

    private fun readReminders(): List<LiveReminder> = store.getString(KEY_REMINDERS, "").split(LINE).mapNotNull { line ->
        val parts = line.split(FIELD)
        if (parts.size < 3) null else parts[1].toLongOrNull()?.let { LiveReminder(parts[0], parts.drop(2).joinToString(" "), it) }
    }

    companion object {
        val followedKinds = setOf(LiveEventKind.CRICKET, LiveEventKind.FOOTBALL, LiveEventKind.SHOW)
        const val KEY_PILL = "live_pill"
        const val KEY_SPORTS = "live_sports"
        const val KEY_TEAMS = "live_teams"
        const val KEY_REMINDERS = "live_reminders"
        /** A reminder fires this long before the start. */
        const val REMIND_BEFORE_MS = 5 * 60_000L
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
