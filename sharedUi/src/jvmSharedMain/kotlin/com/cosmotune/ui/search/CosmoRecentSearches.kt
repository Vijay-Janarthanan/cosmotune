package com.cosmotune.ui.search

import com.cosmotune.settings.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The words searched before, one list per mode ("podio", "read", "manga"), newest first, at most [MAX], kept on this device only.
 * Each app hands its store in at start ([install]: SharedPreferences on the phone, the desktop's preferences node); until then (a
 * test, a preview) a memory store stands in. Music and Video keep their own histories and do not use this.
 */
object CosmoRecentSearches {
    /** The most a mode keeps. */
    const val MAX = 10

    private var store: KeyValueStore = MemoryStore()
    private val lists = HashMap<String, MutableStateFlow<List<String>>>()

    /** Puts the platform's store in place; lists read before it are read again from it. */
    @Synchronized fun install(next: KeyValueStore) {
        store = next
        lists.forEach { (mode, flow) -> flow.value = decode(next.getString(key(mode), "")) }
    }

    /** [mode]'s recent searches, newest first. */
    @Synchronized fun of(mode: String): StateFlow<List<String>> = flow(mode).asStateFlow()

    /** [term] searched in [mode]: it goes to the front (once), and the oldest past [MAX] drops off. A blank term is not kept. */
    fun add(mode: String, term: String) = write(mode) { added(it, term) }

    /** Takes [term] off [mode]'s list. */
    fun remove(mode: String, term: String) = write(mode) { list -> list.filterNot { it.equals(term.trim(), ignoreCase = true) } }

    /** Empties [mode]'s list. */
    fun clear(mode: String) = write(mode) { emptyList() }

    /** Puts [terms] back as [mode]'s list (an Undo). */
    fun restore(mode: String, terms: List<String>) = write(mode) { terms.map(::clean).filter(String::isNotEmpty).distinctBy(String::lowercase).take(MAX) }

    @Synchronized private fun write(mode: String, change: (List<String>) -> List<String>) {
        val flow = flow(mode)
        val next = change(flow.value)
        if (next == flow.value) return
        flow.value = next
        store.putString(key(mode), encode(next))
    }

    private fun flow(mode: String): MutableStateFlow<List<String>> = lists.getOrPut(mode) { MutableStateFlow(decode(store.getString(key(mode), ""))) }

    private fun key(mode: String) = "search_recent_$mode"

    /** [list] with [term] (trimmed, spaces folded) at its front, any other spelling of it in another case gone, cut to [MAX]. */
    internal fun added(list: List<String>, term: String): List<String> {
        val clean = clean(term)
        if (clean.isEmpty()) return list
        return (listOf(clean) + list.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX)
    }

    private fun clean(term: String) = term.replace(SEP, ' ').trim().replace(Regex("\\s+"), " ").take(200)

    internal fun encode(list: List<String>): String = list.joinToString(SEP.toString())
    internal fun decode(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(SEP).filter(String::isNotBlank).take(MAX)

    /** The unit separator: a character nobody types into a search box. */
    private const val SEP = '\u001F'

    private class MemoryStore : KeyValueStore {
        private val values = HashMap<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getString(key: String, default: String) = values[key] as? String ?: default
        override fun putString(key: String, value: String) { values[key] = value }
    }
}
