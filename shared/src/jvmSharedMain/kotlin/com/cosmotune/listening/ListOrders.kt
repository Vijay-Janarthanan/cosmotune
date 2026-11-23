package com.cosmotune.listening

/**
 * The order each album or playlist page was last put in (the idea from AETHER, see NOTICE.md), kept as one line each
 * ("browseId<TAB>order") in the settings store, the most recent last. A page with none follows Settings' song order.
 */
object ListOrders {
    const val MAX = 500

    fun encode(orders: Map<String, String>): String = orders.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    fun decode(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val id = line.substringBefore('\t', "").trim()
            val order = line.substringAfter('\t', "").trim()
            if (id.isNotEmpty() && order.isNotEmpty() && '\n' !in id) { out.remove(id); out[id] = order }
        }
        while (out.size > MAX) out.remove(out.keys.first())
        return out
    }

    /** [orders] with [id]'s order set to [order] (moved to the end as the most recent), or forgotten when [order] is null. */
    fun with(orders: Map<String, String>, id: String, order: String?): Map<String, String> {
        if (id.isBlank() || '\t' in id || '\n' in id) return orders
        val out = LinkedHashMap(orders)
        out.remove(id)
        if (order != null) out[id] = order
        while (out.size > MAX) out.remove(out.keys.first())
        return out
    }
}
