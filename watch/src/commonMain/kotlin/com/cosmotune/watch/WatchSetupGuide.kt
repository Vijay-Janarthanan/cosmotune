package com.cosmotune.watch

import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginInfo

/** Where a first-use step sends the viewer. */
enum class WatchSetupTarget { PLUGINS, SETTINGS }

/** One line of the first-use guide on Watch's Home: what it is for, and whether it is already done. */
data class WatchSetupStep(val id: String, val title: String, val detail: String, val action: String, val target: WatchSetupTarget, val done: Boolean)

/**
 * The first-use guide: a short list built from what this device already has, so it never asks for
 * something that is set up, and never presents an optional extra as required. Watch plays from the
 * first open on its free built-in plugins; the guide only says how to add more.
 */
object WatchSetupGuide {
    fun steps(plugins: List<PluginInfo>, profiles: Int, tracking: List<WatchTrackingStatus>?): List<WatchSetupStep> = buildList {
        val playing = plugins.count { it.enabled && Capability.STREAMS in it.capabilities }
        val yours = plugins.count { !it.builtIn }
        add(WatchSetupStep("sources", "Choose where films come from",
            when {
                playing == 0 -> "Every source is off. Switch on a built-in plugin or add your own."
                yours == 0 -> "$playing free ${if (playing == 1) "source is" else "sources are"} on. Add an add-on link, a Jellyfin or Plex server, or a playlist for more."
                else -> "$playing ${if (playing == 1) "source plays" else "sources play"}, including $yours of yours."
            },
            "Open Plugins", WatchSetupTarget.PLUGINS, done = playing > 0 && yours > 0))
        add(WatchSetupStep("viewers", "Add a viewer for each person",
            if (profiles > 1) "$profiles viewers, each with their own list and progress." else "Optional. Each viewer keeps their own list, progress and PIN.",
            "Open settings", WatchSetupTarget.SETTINGS, done = profiles > 1))
        if (tracking != null) {
            val connected = tracking.filter { it.connected }.map { it.service.label }
            add(WatchSetupStep("tracking", "Bring your Trakt or Simkl history",
                if (connected.isNotEmpty()) "Connected to ${connected.joinToString(" and ")}." else "Optional. Needs your own registered client ID.",
                "Open settings", WatchSetupTarget.SETTINGS, done = connected.isNotEmpty()))
        }
    }

    /** Shown until it is put away, or until every step is done. */
    fun shouldShow(dismissed: Boolean, steps: List<WatchSetupStep>): Boolean = !dismissed && steps.any { !it.done }
}
