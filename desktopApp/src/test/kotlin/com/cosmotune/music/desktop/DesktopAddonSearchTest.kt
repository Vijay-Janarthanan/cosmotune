package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.search.AddonSearch
import com.cosmotune.music.data.search.AddonSearchSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BC-31 on the desktop: which configured sources the Search page's Add-ons source asks. Fakes only: the configs are built here
 * and the add-on call is a lambda, so neither the real source list (the registry) nor a network is touched.
 */
class DesktopAddonSearchTest {

    private fun addon(id: String, label: String, enabled: Boolean = true, baseUrl: String = "http://127.0.0.1:1/$id") =
        DesktopSourceConfig(id = id, kind = DesktopSourceKind.ADDON, label = label, baseUrl = baseUrl, enabled = enabled)

    @Test fun onlyEnabledCompleteHttpAddonsAreAskedThenTheProviderPackages() = runBlocking {
        val asked = mutableListOf<String>()
        val configs = listOf(
            addon("a", "Lossless add-on"),
            addon("off", "Disabled add-on", enabled = false),
            addon("blank", "No server yet", baseUrl = ""),
            DesktopSourceConfig(id = "jio", kind = DesktopSourceKind.JIOSAAVN),
            DesktopSourceConfig(id = "yt", kind = DesktopSourceKind.YOUTUBE),
        )
        val provider = AddonSearchSource("Fixture provider") { q, _ -> listOf(Song("src:sflx-fixture::1", "$q from provider", "Singer", null)) }
        val sources = DesktopSourceRegistry.addonSearchSources(configs, listOf(provider)) { config, query, limit ->
            asked += "${config.id}:$query:$limit"
            listOf(Song("src:${config.id}::1", "$query from ${config.id}", "Singer", null))
        }
        assertEquals(listOf("Lossless add-on", "Fixture provider"), sources.map { it.name })

        val rows = AddonSearch.search(" aurora ", sources)
        assertEquals(listOf("a:aurora:${AddonSearch.LIMIT}"), asked, "the add-on gets the trimmed query and the row limit, nothing else is asked")
        assertEquals(listOf("aurora from a", "aurora from provider"), rows.map { it.title })
    }

    @Test fun withNothingConfiguredThereIsNoSource() {
        val sources = DesktopSourceRegistry.addonSearchSources(
            listOf(DesktopSourceConfig(id = "yt", kind = DesktopSourceKind.YOUTUBE), addon("off", "Off", enabled = false)),
            emptyList(),
        ) { _, _, _ -> error("not asked") }
        assertEquals(emptyList(), sources)
    }
}
