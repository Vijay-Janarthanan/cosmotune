package com.cosmotune.ui.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.kit.KitDivider
import com.cosmotune.ui.kit.KitEmptyState
import com.cosmotune.ui.kit.KitGroup
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitSettingsRow
import com.cosmotune.ui.kit.KitTileTint
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.sharedui.resources.cosmo_settings_section_account
import com.cosmotune.music.sharedui.resources.cosmo_settings_section_listening
import com.cosmotune.music.sharedui.resources.cosmo_settings_section_modes
import com.cosmotune.music.sharedui.resources.cosmo_settings_section_more
import com.cosmotune.music.sharedui.resources.cosmo_settings_section_yours
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_settings_back
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_about
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_about_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_account
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_account_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_appearance
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_appearance_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_downloads
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_downloads_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_integrations
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_integrations_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_listening
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_listening_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_lyrics
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_lyrics_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_playback
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_playback_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_podio
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_podio_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_privacy
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_privacy_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_read
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_read_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_sources
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_sources_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_video
import com.cosmotune.music.sharedui.resources.cosmo_settings_cat_video_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_about
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_behaviour
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_canvas
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_free_space
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_lyrics
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_motion
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_music_video
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_output
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_player
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_services
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_sound
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_transitions
import com.cosmotune.music.sharedui.resources.cosmo_settings_group_window
import com.cosmotune.music.sharedui.resources.cosmo_settings_licenses
import com.cosmotune.music.sharedui.resources.cosmo_settings_mode_needs_look
import com.cosmotune.music.sharedui.resources.cosmo_settings_no_results
import com.cosmotune.music.sharedui.resources.cosmo_settings_open_podio
import com.cosmotune.music.sharedui.resources.cosmo_settings_open_podio_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_open_read
import com.cosmotune.music.sharedui.resources.cosmo_settings_open_read_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_open_video
import com.cosmotune.music.sharedui.resources.cosmo_settings_open_video_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_results
import com.cosmotune.music.sharedui.resources.cosmo_settings_services
import com.cosmotune.music.sharedui.resources.cosmo_settings_services_summary
import com.cosmotune.music.sharedui.resources.cosmo_settings_source_code
import com.cosmotune.music.sharedui.resources.cosmo_settings_version
import com.cosmotune.music.sharedui.resources.cosmo_settings_website
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalCosmoColors
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The settings home and what phone and desktop draw around their pages: the categories with their icons and one-line summaries
 * ([SettingsHub]), the desktop's category list ([SettingsCategoryRail]), the header of a page ([SettingsPageHeader]) and the
 * search results ([SettingsSearchResults]). The rows of the pages themselves are the blocks of CosmoSettingsBlocks.kt.
 */

/** One category as the settings home shows it: its [summary] is the current values when the screen knows them, else what it holds. */
@Immutable
data class SettingsCategoryEntry(
    val category: SettingsCategory,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    /** What the category holds, whatever its current values: the search finds the category by these words too. */
    val blurb: String = summary,
)

/** The category's icon on the settings home, in the rail and beside its search results. */
val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.ALL -> Icons.Rounded.Search
        SettingsCategory.ACCOUNT -> Icons.Rounded.Person
        SettingsCategory.PLAYBACK -> Icons.Rounded.GraphicEq
        SettingsCategory.SOURCES -> Icons.Rounded.Extension
        SettingsCategory.DOWNLOADS -> Icons.Rounded.Download
        SettingsCategory.LYRICS -> Icons.Rounded.Lyrics
        SettingsCategory.APPEARANCE -> Icons.Rounded.Palette
        SettingsCategory.VIDEO -> Icons.Rounded.SmartDisplay
        SettingsCategory.READ -> Icons.AutoMirrored.Rounded.MenuBook
        SettingsCategory.PODIO -> Icons.Rounded.Podcasts
        SettingsCategory.LISTENING -> Icons.Rounded.Insights
        SettingsCategory.INTEGRATIONS -> Icons.Rounded.Hub
        SettingsCategory.PRIVACY -> Icons.Rounded.Backup
        SettingsCategory.ABOUT -> Icons.Rounded.Info
    }

private fun SettingsCategory.titleRes(): StringResource? = when (this) {
    SettingsCategory.ALL -> null
    SettingsCategory.ACCOUNT -> Res.string.cosmo_settings_cat_account
    SettingsCategory.PLAYBACK -> Res.string.cosmo_settings_cat_playback
    SettingsCategory.SOURCES -> Res.string.cosmo_settings_cat_sources
    SettingsCategory.DOWNLOADS -> Res.string.cosmo_settings_cat_downloads
    SettingsCategory.LYRICS -> Res.string.cosmo_settings_cat_lyrics
    SettingsCategory.APPEARANCE -> Res.string.cosmo_settings_cat_appearance
    SettingsCategory.VIDEO -> Res.string.cosmo_settings_cat_video
    SettingsCategory.READ -> Res.string.cosmo_settings_cat_read
    SettingsCategory.PODIO -> Res.string.cosmo_settings_cat_podio
    SettingsCategory.LISTENING -> Res.string.cosmo_settings_cat_listening
    SettingsCategory.INTEGRATIONS -> Res.string.cosmo_settings_cat_integrations
    SettingsCategory.PRIVACY -> Res.string.cosmo_settings_cat_privacy
    SettingsCategory.ABOUT -> Res.string.cosmo_settings_cat_about
}

private fun SettingsCategory.blurbRes(): StringResource? = when (this) {
    SettingsCategory.ALL -> null
    SettingsCategory.ACCOUNT -> Res.string.cosmo_settings_cat_account_summary
    SettingsCategory.PLAYBACK -> Res.string.cosmo_settings_cat_playback_summary
    SettingsCategory.SOURCES -> Res.string.cosmo_settings_cat_sources_summary
    SettingsCategory.DOWNLOADS -> Res.string.cosmo_settings_cat_downloads_summary
    SettingsCategory.LYRICS -> Res.string.cosmo_settings_cat_lyrics_summary
    SettingsCategory.APPEARANCE -> Res.string.cosmo_settings_cat_appearance_summary
    SettingsCategory.VIDEO -> Res.string.cosmo_settings_cat_video_summary
    SettingsCategory.READ -> Res.string.cosmo_settings_cat_read_summary
    SettingsCategory.PODIO -> Res.string.cosmo_settings_cat_podio_summary
    SettingsCategory.LISTENING -> Res.string.cosmo_settings_cat_listening_summary
    SettingsCategory.INTEGRATIONS -> Res.string.cosmo_settings_cat_integrations_summary
    SettingsCategory.PRIVACY -> Res.string.cosmo_settings_cat_privacy_summary
    SettingsCategory.ABOUT -> Res.string.cosmo_settings_cat_about_summary
}

/** The category's name ("Playback & audio quality"); the settings home itself has none. */
@Composable
fun SettingsCategory.title(): String = titleRes()?.let { stringResource(it) }.orEmpty()

/** What the category holds, in one line, for the settings home when the screen has no current values to show. */
@Composable
fun SettingsCategory.blurb(): String = blurbRes()?.let { stringResource(it) }.orEmpty()

/** The other texts both settings screens share: the headers of the new groups and the rows that link elsewhere. */
enum class SettingsText(internal val res: StringResource) {
    RESULTS(Res.string.cosmo_settings_results),
    BACK(Res.string.cosmo_settings_back),
    GROUP_LYRICS(Res.string.cosmo_settings_group_lyrics),
    GROUP_OUTPUT(Res.string.cosmo_settings_group_output),
    GROUP_SOUND(Res.string.cosmo_settings_group_sound),
    GROUP_TRANSITIONS(Res.string.cosmo_settings_group_transitions),
    GROUP_BEHAVIOUR(Res.string.cosmo_settings_group_behaviour),
    GROUP_PLAYER(Res.string.cosmo_settings_group_player),
    GROUP_MOTION(Res.string.cosmo_settings_group_motion),
    GROUP_CANVAS(Res.string.cosmo_settings_group_canvas),
    GROUP_MUSIC_VIDEO(Res.string.cosmo_settings_group_music_video),
    GROUP_FREE_SPACE(Res.string.cosmo_settings_group_free_space),
    GROUP_ABOUT(Res.string.cosmo_settings_group_about),
    GROUP_SERVICES(Res.string.cosmo_settings_group_services),
    GROUP_WINDOW(Res.string.cosmo_settings_group_window),
    SERVICES(Res.string.cosmo_settings_services),
    SERVICES_SUMMARY(Res.string.cosmo_settings_services_summary),
    VERSION(Res.string.cosmo_settings_version),
    SOURCE_CODE(Res.string.cosmo_settings_source_code),
    WEBSITE(Res.string.cosmo_settings_website),
    LICENSES(Res.string.cosmo_settings_licenses),
    OPEN_VIDEO(Res.string.cosmo_settings_open_video),
    OPEN_VIDEO_SUMMARY(Res.string.cosmo_settings_open_video_summary),
    OPEN_READ(Res.string.cosmo_settings_open_read),
    OPEN_READ_SUMMARY(Res.string.cosmo_settings_open_read_summary),
    OPEN_PODIO(Res.string.cosmo_settings_open_podio),
    OPEN_PODIO_SUMMARY(Res.string.cosmo_settings_open_podio_summary),
    MODE_NEEDS_LOOK(Res.string.cosmo_settings_mode_needs_look),
}

@Composable
fun SettingsText.text(): String = stringResource(res)

/**
 * The entries of the settings home for [categories], each with its name, its icon and [summaries]' line for it (or what it holds,
 * when the screen gives none).
 */
@Composable
fun settingsCategoryEntries(
    categories: List<SettingsCategory> = SettingsCategory.pages,
    summaries: Map<SettingsCategory, String?> = emptyMap(),
): List<SettingsCategoryEntry> = categories.map { category ->
    SettingsCategoryEntry(
        category = category,
        title = category.title(),
        summary = summaries[category]?.takeIf { it.isNotBlank() } ?: category.blurb(),
        icon = category.icon,
        blurb = category.blurb(),
    )
}

/** The categories as the search finds them, so typing "privacy" opens the page itself. Their ids start with [CATEGORY_ID_PREFIX]. */
fun List<SettingsCategoryEntry>.asSearchItems(): List<SettingsItem> = map { entry ->
    SettingsItem(
        id = CATEGORY_ID_PREFIX + entry.category.name,
        title = entry.title,
        category = entry.category,
        summary = entry.summary,
        keywords = listOf(entry.blurb),
    )
}

const val CATEGORY_ID_PREFIX = "category:"

/** How the settings home groups its categories: each section is one card under its overline. */
private enum class HubSection(val res: StringResource, val categories: List<SettingsCategory>) {
    ACCOUNT(Res.string.cosmo_settings_section_account, listOf(SettingsCategory.ACCOUNT)),
    LISTENING(
        Res.string.cosmo_settings_section_listening,
        listOf(SettingsCategory.PLAYBACK, SettingsCategory.SOURCES, SettingsCategory.DOWNLOADS, SettingsCategory.LYRICS),
    ),
    YOURS(Res.string.cosmo_settings_section_yours, listOf(SettingsCategory.APPEARANCE, SettingsCategory.LISTENING)),
    MODES(Res.string.cosmo_settings_section_modes, listOf(SettingsCategory.VIDEO, SettingsCategory.READ, SettingsCategory.PODIO)),
    MORE(Res.string.cosmo_settings_section_more, listOf(SettingsCategory.INTEGRATIONS, SettingsCategory.PRIVACY, SettingsCategory.ABOUT)),
}

/**
 * The squircle tile of [category]'s icon (DESIGN_SYSTEM.md 4.10, the owner's Prism-style tiles): one of the kit's muted tile tints,
 * so neighbouring categories never share a colour and the white glyph stays readable in both themes. The home (ALL) is grey.
 */
val SettingsCategory.tile: KitTileTint
    get() = when (this) {
        SettingsCategory.ALL -> KitTileTint.GREY
        SettingsCategory.ACCOUNT -> KitTileTint.BLUE
        SettingsCategory.PLAYBACK -> KitTileTint.RED
        SettingsCategory.SOURCES -> KitTileTint.PURPLE
        SettingsCategory.DOWNLOADS -> KitTileTint.GREEN
        SettingsCategory.LYRICS -> KitTileTint.PINK
        SettingsCategory.APPEARANCE -> KitTileTint.BLUE
        SettingsCategory.VIDEO -> KitTileTint.RED
        SettingsCategory.READ -> KitTileTint.ORANGE
        SettingsCategory.PODIO -> KitTileTint.PURPLE
        SettingsCategory.LISTENING -> KitTileTint.TEAL
        SettingsCategory.INTEGRATIONS -> KitTileTint.ORANGE
        SettingsCategory.PRIVACY -> KitTileTint.GREEN
        SettingsCategory.ABOUT -> KitTileTint.GREY
    }

/** The colour of [category]'s icon tile in the current theme ([tile]'s light or dark step), for the rows of its page. */
@Composable
fun settingsCategoryTint(category: SettingsCategory): Color {
    val dark = LocalCosmoColors.current.dark
    return remember(category, dark) { Color(category.tile.fill(dark)) }
}

/**
 * The settings home's title: large and light, in the kit's display type ([KitLargeTopBar]'s large title), for a host that already
 * draws the bar above the page.
 */
@Composable
fun SettingsHubTitle(text: String, modifier: Modifier = Modifier) {
    val type = LocalCosmoType.current
    Text(
        text = text,
        style = type.forText(type.displayLarge, text),
        color = Color(LocalCosmoColors.current.palette.textPrimary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = CosmoSpacing.margin + 4.dp, end = CosmoSpacing.margin, top = 8.dp, bottom = 14.dp)
            .semantics { heading() },
    )
}

/**
 * The settings home: [accountCard] first when there is one (it stands in for the Account category), then the categories of
 * [entries] in sections, each a [KitGroup] (an overline in capitals over one card) of [KitSettingsRow]s (the category's coloured
 * squircle, its name, its summary, a chevron) parted by [KitDivider]s. Picking one calls [onOpen].
 */
@Composable
fun SettingsHub(
    entries: List<SettingsCategoryEntry>,
    onOpen: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
    accountCard: (@Composable () -> Unit)? = null,
) {
    val sectioned = HubSection.entries.flatMap { it.categories }.toSet()
    val rest = entries.filter { it.category !in sectioned }
    Column(modifier.fillMaxWidth()) {
        if (accountCard != null) accountCard()
        HubSection.entries.forEach { section ->
            if (section == HubSection.ACCOUNT && accountCard != null) return@forEach
            val shown = section.categories.mapNotNull { category -> entries.firstOrNull { it.category == category } }
            if (shown.isEmpty()) return@forEach
            KitGroup(title = stringResource(section.res)) { HubRows(shown, onOpen) }
        }
        if (rest.isNotEmpty()) KitGroup(modifier = Modifier.padding(top = 16.dp)) { HubRows(rest, onOpen) }
    }
}

@Composable
private fun HubRows(entries: List<SettingsCategoryEntry>, onOpen: (SettingsCategory) -> Unit) {
    entries.forEachIndexed { index, entry ->
        if (index > 0) KitDivider(start = KIT_TEXT_START)
        SettingsLinkRow(
            icon = entry.icon,
            tile = entry.category.tile,
            title = entry.title,
            summary = entry.summary,
            singleLineSummary = true,
            onClick = { onOpen(entry.category) },
            modifier = Modifier.testTag("settings:hub:${entry.category.name}"),
        )
    }
}

/** Where a [KitSettingsRow]'s text starts: its 14 dp padding, the 30 dp squircle and the 12 dp gap. */
private val KIT_TEXT_START = 56.dp

/**
 * A row that leads somewhere: the kit's settings row with its squircle in a [tile] tint and a chevron. [singleLineSummary] keeps
 * the summary to one line (the current values, on the settings home).
 */
@Composable
fun SettingsLinkRow(
    icon: ImageVector,
    tile: KitTileTint,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    singleLineSummary: Boolean = false,
) {
    KitSettingsRow(
        title = title,
        modifier = modifier,
        icon = icon,
        tile = tile,
        summary = summary,
        onClick = onClick,
        singleLineSummary = singleLineSummary,
    )
}

/**
 * The search results: [results] best first, in one [KitGroup] under "Results", each a [KitSettingsRow] with its category's coloured
 * squircle, its title, and where it lives ("Playback & audio quality · Output") over its summary. Picking one calls [onPick]; the
 * screen then opens its page and lights it up. With no results, the kit's empty state says so ([emptyMessage], or "No settings
 * match").
 */
@Composable
fun SettingsSearchResults(
    results: List<SettingsItem>,
    query: String,
    onPick: (SettingsItem) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        if (results.isEmpty()) {
            val message = emptyMessage ?: stringResource(Res.string.cosmo_settings_no_results, query.trim())
            KitEmptyState(
                title = SettingsText.RESULTS.text(),
                message = message,
                icon = CosmoIcons.Search,
                modifier = Modifier.testTag("settings:results:empty"),
            )
            return@Column
        }
        KitGroup(title = SettingsText.RESULTS.text()) {
            results.forEachIndexed { index, item ->
                if (index > 0) KitDivider(start = KIT_TEXT_START)
                val where = listOfNotNull(item.category.title().takeIf { it.isNotEmpty() }, item.group?.takeIf { it.isNotBlank() && it != item.title })
                    .distinct()
                    .joinToString(" · ")
                val summary = listOfNotNull(where.takeIf { it.isNotEmpty() }, item.summary?.takeIf { it.isNotBlank() && it != item.title })
                    .joinToString("\n")
                SettingsLinkRow(
                    icon = item.category.icon,
                    tile = item.category.tile,
                    title = item.title,
                    summary = summary,
                    onClick = { onPick(item) },
                    modifier = Modifier.testTag("settings:result:${item.id}"),
                )
            }
        }
    }
}

/**
 * The top of a settings page: the kit's round back button ([onBack], where the page was opened from the settings home) over its
 * [title] in the kit's large light type, and [subtitle] saying what the page holds.
 */
@Composable
fun SettingsPageHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette
    Column(modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin)) {
        if (onBack != null) {
            KitIconButton(CosmoIcons.ArrowLeft, SettingsText.BACK.text(), onBack, modifier = Modifier.padding(top = 4.dp).testTag("settings:back"))
        }
        Text(
            text = title,
            style = type.forText(type.displayLarge, title),
            color = Color(palette.textPrimary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = type.body().copy(fontSize = 14.sp),
                color = Color(palette.textSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * The desktop's list of categories beside the page: one kit settings row per entry (its coloured squircle, its name, its summary
 * on one line), the [selected] one marked by the kit row's selected state. Null selects none (while the search is open). Each row
 * is a tab of the list, so a screen reader says which one is open.
 */
@Composable
fun SettingsCategoryRail(
    entries: List<SettingsCategoryEntry>,
    selected: SettingsCategory?,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        entries.forEach { entry ->
            val isSelected = entry.category == selected
            KitSettingsRow(
                title = entry.title,
                modifier = Modifier
                    .clip(RoundedCornerShape(CosmoRadii.rMd))
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(entry.category) })
                    .testTag("settings:rail:${entry.category.name}"),
                icon = entry.icon,
                tile = entry.category.tile,
                summary = entry.summary,
                selected = isSelected,
                singleLineSummary = true,
            )
        }
    }
}
