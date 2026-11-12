package com.cosmotune.video.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * What a page keeps while it is in a stack, apart from its composition (which ends when another page is shown): the list it has
 * loaded, so that coming back from a video finds the same list to scroll in, not a list fetched and arranged afresh. Held per page
 * entry; the navigation host drops an entry's slots when the page leaves every stack, and a slot not asked for within [ttlMs] is
 * made anew (a long time away is a fresh start).
 */
internal class KeptPages(private val ttlMs: Long = TTL_MS, private val clock: () -> Long = System::currentTimeMillis) {
    private class Slot(val value: Any, var usedAt: Long)

    private val slots = HashMap<String, HashMap<String, Slot>>()

    /** The slot [slot] of page [page]: the one held, or [create]'s when there is none or it was last used more than [ttlMs] ago. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(page: String, slot: String, create: () -> T): T {
        val now = clock()
        val held = slots.getOrPut(page) { HashMap() }
        val found = held[slot]
        if (found != null && now - found.usedAt in 0..ttlMs) { found.usedAt = now; return found.value as T }
        return create().also { held[slot] = Slot(it, now) }
    }

    /** Forgets everything page [page] kept. */
    fun drop(page: String) { slots.remove(page) }

    companion object {
        const val TTL_MS = 30 * 60_000L
    }
}

/** The kept slots of the page being drawn; null where there is no navigation host (a preview), and a page then keeps nothing. */
internal val LocalKeptPage = compositionLocalOf<((String, () -> Any) -> Any)?> { null }

/** A [KeptPages] slot of this page: [create]'s value the first time, the same one when the page is drawn again after a visit elsewhere. */
@Composable
@Suppress("UNCHECKED_CAST")
internal fun <T : Any> rememberKept(slot: String, create: () -> T): T {
    val keep = LocalKeptPage.current
    return remember(keep, slot) { if (keep == null) create() else keep(slot, create) as T }
}
