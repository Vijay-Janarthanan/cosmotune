package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem
import kotlinx.serialization.Serializable

/**
 * A group of followed channels the listener made (Subscriptions, the group chips): its [name] and the channels in it. The feed
 * shows only their videos while the group is picked.
 */
@Serializable
data class FeedChannelGroup(val id: String, val name: String, val channelIds: Set<String> = emptySet())

object FeedGroups {
    const val MAX_GROUPS = 30
    const val MAX_NAME = 40

    /** [items] of the channels in [group] only; every item when [group] is null. */
    fun itemsOf(items: List<VideoItem>, group: FeedChannelGroup?): List<VideoItem> =
        if (group == null) items else items.filter { it.channelId != null && it.channelId in group.channelIds }

    /** [groups] with [group] saved: replaced when its id is there, else added at the end; a blank name is not saved. */
    fun save(groups: List<FeedChannelGroup>, group: FeedChannelGroup): List<FeedChannelGroup> {
        val name = group.name.trim().take(MAX_NAME)
        if (name.isEmpty()) return groups
        val clean = group.copy(name = name)
        val at = groups.indexOfFirst { it.id == group.id }
        return if (at >= 0) groups.toMutableList().also { it[at] = clean } else (groups + clean).take(MAX_GROUPS)
    }

    fun delete(groups: List<FeedChannelGroup>, id: String): List<FeedChannelGroup> = groups.filterNot { it.id == id }

    /** A new group's id: not one of [groups]' ids. */
    fun newId(groups: List<FeedChannelGroup>, nowMs: Long): String {
        var n = nowMs
        while (groups.any { it.id == "g$n" }) n++
        return "g$n"
    }

    /** What settings keep: blank names and repeated ids dropped, at most [MAX_GROUPS]. */
    fun clean(groups: List<FeedChannelGroup>): List<FeedChannelGroup> =
        groups.filter { it.name.isNotBlank() && it.id.isNotBlank() }.distinctBy { it.id }.take(MAX_GROUPS)
}
