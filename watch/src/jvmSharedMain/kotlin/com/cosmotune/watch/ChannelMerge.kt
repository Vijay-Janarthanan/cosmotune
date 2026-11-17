package com.cosmotune.watch

import com.cosmotune.watch.model.Channel

/**
 * Channels of different sources that are the same channel: the same name once case, spaces and punctuation are set aside ("Sun TV",
 * "SUN TV", "Sun-TV"), and no country that differs (a source that names no country agrees with any). "Sun TV HD" stays its own
 * channel. The first of a group, in the order given (the sources' order), is the one shown; the rest are its [Channel.also].
 */
object ChannelMerge {
    fun nameKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    fun byName(channels: List<Channel>): List<Channel> {
        val groups = LinkedHashMap<String, MutableList<MutableList<Channel>>>()
        for (c in channels) {
            val key = nameKey(c.name)
            if (key.isEmpty()) { groups.getOrPut("#" + c.key.value) { mutableListOf() } += mutableListOf(c); continue }
            val sameName = groups.getOrPut(key) { mutableListOf() }
            val group = sameName.firstOrNull { g -> g.none { it.pluginId == c.pluginId } && g.all { agrees(it, c) } }
            if (group != null) group += c else sameName += mutableListOf(c)
        }
        return groups.values.flatten().map { group ->
            val first = group.first()
            if (group.size == 1) first else first.copy(
                also = group.drop(1),
                logoUrl = first.logoUrl ?: group.firstNotNullOfOrNull { it.logoUrl },
                countries = group.flatMap { it.countries }.distinct(),
                languages = group.flatMap { it.languages }.distinct(),
            )
        }
    }

    private fun agrees(a: Channel, b: Channel): Boolean =
        a.countries.isEmpty() || b.countries.isEmpty() || a.countries.any { it in b.countries }
}
