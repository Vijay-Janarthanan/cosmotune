package com.cosmotune.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.kit.KitChip
import com.cosmotune.ui.kit.KitSectionHeader
import com.cosmotune.ui.kit.KitSkeletonRows
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.search.AddonSearch
import com.cosmotune.music.data.search.AddonSearchProgress
import com.cosmotune.music.data.search.AddonSearchSource
import com.cosmotune.music.data.search.AddonSourceResult
import com.cosmotune.music.data.search.AddonSourceStatus
import com.cosmotune.providers.ProviderAccessState
import com.cosmotune.providers.ProviderAutoVerification
import com.cosmotune.providers.ProviderHub
import com.cosmotune.providers.ProviderVerificationStatus
import com.cosmotune.providers.providerAccessState

/** The small dot on a "Search in" chip. */
enum class SearchInStatus { IDLE, READY, SEARCHING, VERIFYING, NEEDS_VERIFICATION, ERROR }

/** One "Search in" chip: [key] is [AddonSearch.ALL], [AddonSearch.YOUTUBE] or a source id. */
data class SearchInChoice(val key: String, val label: String, val status: SearchInStatus = SearchInStatus.IDLE)

/**
 * The Lokal-style "Search in" picker and the per-source state of the current Add-ons search, handed to [SearchSwitch] by the
 * roots. [selected] is the remembered choice; [onSelect] restricts the search to that source ([AddonSearch.YOUTUBE] goes to the
 * YouTube Music results page, [AddonSearch.ALL] asks everything). [onVerify] opens a source's verification ("Verify to search").
 */
class SearchInState(
    val choices: List<SearchInChoice>,
    val selected: String,
    val onSelect: (String) -> Unit,
    val progress: AddonSearchProgress? = null,
    val onVerify: (String) -> Unit = {},
)

/** A source id's provider package name, for the ones that are packages (`sflx-<name>`). */
fun providerNameOf(sourceId: String): String? = sourceId.takeIf { it.startsWith("sflx-") }?.removePrefix("sflx-")

/**
 * The chips for the host's [sources] (its add-ons, servers and provider packages, YouTube Music excluded), with each one's dot:
 * its part of the current search when there is one, else its provider's saved connection state and any verification in
 * progress.
 */
fun searchInChoices(
    sources: List<AddonSearchSource>,
    progress: AddonSearchProgress?,
    verifications: Map<String, ProviderVerificationStatus> = emptyMap(),
    access: (String) -> ProviderAccessState? = { null },
    allLabel: String = "All",
    youTubeLabel: String = "YouTube Music",
): List<SearchInChoice> = buildList {
    add(SearchInChoice(AddonSearch.ALL, allLabel, if (progress?.searching == true) SearchInStatus.SEARCHING else SearchInStatus.IDLE))
    add(SearchInChoice(AddonSearch.YOUTUBE, youTubeLabel, SearchInStatus.READY))
    sources.filter { it.id != AddonSearch.YOUTUBE }.distinctBy { it.id }.forEach { source ->
        val provider = providerNameOf(source.id)
        val verification = provider?.let { verifications[it] }
        val result = progress?.source(source.id)
        val status = when {
            verification == ProviderVerificationStatus.OPENING || verification == ProviderVerificationStatus.OPEN -> SearchInStatus.VERIFYING
            result != null -> when (result.status) {
                AddonSourceStatus.SEARCHING -> SearchInStatus.SEARCHING
                AddonSourceStatus.DONE -> SearchInStatus.READY
                AddonSourceStatus.NEEDS_VERIFICATION -> SearchInStatus.NEEDS_VERIFICATION
                AddonSourceStatus.FAILED, AddonSourceStatus.TIMED_OUT, AddonSourceStatus.UNSUPPORTED -> SearchInStatus.ERROR
            }
            provider != null -> when (access(provider)) {
                ProviderAccessState.VERIFICATION_REQUIRED, ProviderAccessState.RENEWAL_REQUIRED,
                ProviderAccessState.CONFIGURATION_CHANGED -> SearchInStatus.NEEDS_VERIFICATION
                else -> SearchInStatus.READY
            }
            else -> SearchInStatus.READY
        }
        add(SearchInChoice(source.id, source.name, status))
    }
}

/**
 * [SearchInState] for a root: the chips over [sources], recomputed as provider packages and their connections change, with
 * "Verify to search" wired to [ProviderAutoVerification].
 */
@Composable
fun rememberSearchInState(
    sources: () -> List<AddonSearchSource>,
    selected: String,
    onSelect: (String) -> Unit,
    progress: AddonSearchProgress?,
    allLabel: String = "All",
    youTubeLabel: String = "YouTube Music",
): SearchInState {
    val hub by ProviderHub.state.collectAsState()
    val manager = hub.manager
    val providerState = manager?.store?.state?.collectAsState()?.value
    val verifications by ProviderAutoVerification.statuses.collectAsState()
    val list = remember(providerState?.installed, selected, progress?.query, hub.loading) { sources() }
    val choices = searchInChoices(list, progress, verifications, { name ->
        providerState?.let { saved -> saved.installed.find { it.manifest.name == name }?.let { providerAccessState(it, saved.auth[name]) } }
    }, allLabel, youTubeLabel)
    return SearchInState(choices, selected, onSelect, progress, onVerify = { id -> providerNameOf(id)?.let(ProviderAutoVerification::retry) })
}

/** The dot's look: a filled dot in [color], or a ring ([hollow]) for "needs verification" so it differs by shape too. */
private data class Dot(val color: Color, val hollow: Boolean = false)

/** The dot in Orbit's colours: ready in the dim ink, at work in the ink, a ring of the ink to verify, the theme's danger for a failure. */
@Composable
private fun dotFor(status: SearchInStatus): Dot? {
    val look = orbitLook()
    val danger = Color(LocalCosmoColors.current.palette.danger)
    return when (status) {
        SearchInStatus.IDLE -> null
        SearchInStatus.READY -> Dot(look.dim)
        SearchInStatus.SEARCHING, SearchInStatus.VERIFYING -> Dot(look.ink)
        SearchInStatus.NEEDS_VERIFICATION -> Dot(look.ink, hollow = true)
        SearchInStatus.ERROR -> Dot(danger)
    }
}

@Composable
private fun StatusDot(dot: Dot, modifier: Modifier = Modifier) {
    Box(
        modifier.size(7.dp).clip(CircleShape)
            .then(if (dot.hollow) Modifier.border(1.5.dp, dot.color, CircleShape) else Modifier.background(dot.color)),
    )
}

private fun statusWords(status: SearchInStatus): String = when (status) {
    SearchInStatus.IDLE -> ""
    SearchInStatus.READY -> "ready"
    SearchInStatus.SEARCHING -> "searching"
    SearchInStatus.VERIFYING -> "verifying"
    SearchInStatus.NEEDS_VERIFICATION -> "needs verification"
    SearchInStatus.ERROR -> "error"
}

/**
 * "Search in" in Orbit: a sideways-scrolling row of Orbit chips ([KitChip], 40 dp, the chosen one solid ink), each with the source's
 * status dot at its top end. [cosmo] is kept for the callers: both looks draw the Orbit chips.
 */
@Composable
fun SearchInChips(state: SearchInState, cosmo: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("search-in"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.choices.forEach { choice ->
            val selected = choice.key == state.selected
            val dot = dotFor(choice.status)
            val description = listOf(choice.label, statusWords(choice.status)).filter(String::isNotEmpty).joinToString(", ")
            // The chip has no badge slot: the status dot is laid over its top end.
            Box(Modifier.testTag("search-in:${choice.key}").semantics(mergeDescendants = true) { contentDescription = description }) {
                KitChip(label = choice.label, selected = selected, onClick = { state.onSelect(choice.key) })
                if (dot != null) StatusDot(dot, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 6.dp)
                    .testTag("search-in:${choice.key}:${choice.status.name.lowercase()}"))
            }
        }
    }
}

/** What a source's section header says about it. */
internal fun sectionStatus(result: AddonSourceResult): String = when (result.status) {
    AddonSourceStatus.SEARCHING -> "Searching…"
    AddonSourceStatus.DONE -> when (result.songs.size) { 0 -> "No results"; 1 -> "1 result"; else -> "${result.songs.size} results" }
    AddonSourceStatus.TIMED_OUT -> "Timed out"
    AddonSourceStatus.FAILED -> "Failed · ${result.reason ?: "Search failed"}"
    AddonSourceStatus.NEEDS_VERIFICATION -> "Needs verification"
    AddonSourceStatus.UNSUPPORTED -> "Search not offered"
}

/**
 * The Add-ons answer source by source, in Orbit: a header per source ([KitSectionHeader]: its state as the mono overline, searching,
 * n results, failed and why, and "Verify to search" in the accent), stand-in rows while it searches, then its rows. A row's queue is
 * its own source's rows. [row] draws one song in the page's look; [cosmo] is kept for the callers.
 */
internal fun LazyListScope.addonSourceSections(
    progress: AddonSearchProgress,
    state: SearchInState,
    keyPrefix: String,
    cosmo: Boolean = false,
    row: @Composable (tracks: List<Song>, index: Int, song: Song) -> Unit,
) {
    progress.sources.forEach { result ->
        item(key = "$keyPrefix:section:${result.id}") {
            Box(Modifier.testTag("search-in:section:${result.id}")) {
                // The kit's header: the source's state as the overline, "Verify to search" in the See-all slot.
                val verify = result.status == AddonSourceStatus.NEEDS_VERIFICATION && providerNameOf(result.id) != null
                KitSectionHeader(title = result.name, overline = sectionStatus(result), seeAllLabel = "Verify to search",
                    onSeeAll = if (verify) ({ state.onVerify(result.id) }) else null,
                    modifier = if (verify) Modifier.testTag("search-in:verify:${result.id}") else Modifier)
            }
        }
        if (result.status == AddonSourceStatus.SEARCHING && result.songs.isEmpty()) {
            item(key = "$keyPrefix:section:${result.id}:loading") { KitSkeletonRows(count = 2) }
        }
        val tracks = result.songs
        itemsIndexed(tracks, key = { index, song -> "$keyPrefix:${result.id}:${song.videoId}:$index" }) { index, song ->
            row(tracks, index, song)
        }
    }
}
