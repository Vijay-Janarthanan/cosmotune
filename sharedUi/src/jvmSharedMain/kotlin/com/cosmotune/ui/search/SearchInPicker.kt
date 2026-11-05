package com.cosmotune.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.kit.KitChip
import com.cosmotune.ui.kit.KitSectionHeader
import com.cosmotune.ui.kit.KitSkeletonRows
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics

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

/** Theme tokens only: the colours are the ui-shell's to refresh. */
@Composable
private fun dotFor(status: SearchInStatus, cosmo: Boolean): Dot? {
    val scheme = MaterialTheme.colorScheme
    val palette = if (cosmo) LocalCosmoColors.current.palette else null
    fun pick(cosmoToken: Int?, material: Color): Color = if (cosmoToken != null) Color(cosmoToken) else material
    return when (status) {
        SearchInStatus.IDLE -> null
        SearchInStatus.READY -> Dot(pick(palette?.textTertiary, scheme.outline))
        SearchInStatus.SEARCHING, SearchInStatus.VERIFYING -> Dot(pick(palette?.accent, scheme.primary))
        SearchInStatus.NEEDS_VERIFICATION -> Dot(pick(palette?.accentStrong, scheme.tertiary), hollow = true)
        SearchInStatus.ERROR -> Dot(pick(palette?.danger, scheme.error))
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
 * "Search in": a sideways-scrolling row of thin pills, each a low-contrast fill with the source's status dot; the selected one
 * takes the accent fill. [cosmo] draws them from the new look's tokens, else from the Material scheme.
 */
@Composable
fun SearchInChips(state: SearchInState, cosmo: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val palette = if (cosmo) LocalCosmoColors.current.palette else null
    val idleFill = palette?.let { Color(it.surfaceVariant) } ?: scheme.surfaceVariant
    val idleInk = palette?.let { Color(it.textPrimary) } ?: scheme.onSurface
    // The design system's chip: the chosen one in the inverse neutral, not the accent (the classic look's pills).
    val selectedFill = scheme.inverseSurface
    val selectedInk = scheme.inverseOnSurface
    val haptics = rememberHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("search-in"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.choices.forEach { choice ->
            val selected = choice.key == state.selected
            val dot = dotFor(choice.status, cosmo)
            val description = listOf(choice.label, statusWords(choice.status)).filter(String::isNotEmpty).joinToString(", ")
            // The new look: the kit's thin chip, with the status dot laid over its top end (the kit chip has no badge slot).
            if (cosmo) Box(Modifier.testTag("search-in:${choice.key}").semantics(mergeDescendants = true) { contentDescription = description }) {
                KitChip(label = choice.label, selected = selected, onClick = { state.onSelect(choice.key) })
                if (dot != null) StatusDot(dot, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 4.dp)
                    .testTag("search-in:${choice.key}:${choice.status.name.lowercase()}"))
            } else Row(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) selectedFill else idleFill)
                    .selectable(selected = selected, role = Role.Tab) {
                        if (!selected) haptics.play(Haptic.Select)
                        state.onSelect(choice.key)
                    }
                    .padding(horizontal = 12.dp)
                    .testTag("search-in:${choice.key}")
                    .semantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dot != null) {
                    StatusDot(dot, Modifier.testTag("search-in:${choice.key}:${choice.status.name.lowercase()}"))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    choice.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) selectedInk else idleInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
 * The Add-ons answer source by source: a header per source with its state (searching, n results, failed and why, "Verify to
 * search"), then its rows. A row's queue is its own source's rows. [row] draws one song in the page's look.
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
            if (cosmo) Box(Modifier.testTag("search-in:section:${result.id}")) {
                // The kit's header: the source's state as the overline, "Verify to search" in the See-all slot.
                val verify = result.status == AddonSourceStatus.NEEDS_VERIFICATION && providerNameOf(result.id) != null
                KitSectionHeader(title = result.name, overline = sectionStatus(result), seeAllLabel = "Verify to search",
                    onSeeAll = if (verify) ({ state.onVerify(result.id) }) else null)
            } else SourceSectionHeader(result, state)
        }
        if (cosmo && result.status == AddonSourceStatus.SEARCHING && result.songs.isEmpty()) {
            item(key = "$keyPrefix:section:${result.id}:loading") { KitSkeletonRows(count = 2) }
        }
        val tracks = result.songs
        itemsIndexed(tracks, key = { index, song -> "$keyPrefix:${result.id}:${song.videoId}:$index" }) { index, song ->
            row(tracks, index, song)
        }
    }
}

/** The classic look's header: a source's name over its state, "Verify to search" at the end when it needs a check. */
@Composable
private fun SourceSectionHeader(result: AddonSourceResult, state: SearchInState) {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.onSurface
    val secondary = scheme.onSurfaceVariant
    val danger = scheme.error
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 6.dp).testTag("search-in:section:${result.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(result.name, style = MaterialTheme.typography.titleMedium, color = primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sectionStatus(result), style = MaterialTheme.typography.bodySmall,
                color = if (result.status == AddonSourceStatus.FAILED) danger else secondary)
        }
        if (result.status == AddonSourceStatus.SEARCHING) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = scheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        if (result.status == AddonSourceStatus.NEEDS_VERIFICATION && providerNameOf(result.id) != null) {
            TextButton(onClick = { state.onVerify(result.id) }, modifier = Modifier.testTag("search-in:verify:${result.id}")) {
                Text("Verify to search", color = scheme.primary)
            }
        }
    }
}
