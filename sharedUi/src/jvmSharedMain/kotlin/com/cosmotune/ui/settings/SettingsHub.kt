package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.cosmotune.ui.orbit.OrbitAccountCard
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSearchPill
import com.cosmotune.ui.orbit.OrbitSettingsTile
import com.cosmotune.ui.orbit.OrbitStatCard
import com.cosmotune.ui.orbit.OrbitTileGrid
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.kit.KitTileTint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
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
 * The settings home in Orbit (Canvas repo, design/cosmotune/batch-a/board.html, section 2) and what phone and desktop draw around
 * their pages: the account card that leads it ([SettingsAccountCard]), the categories as tiles in two columns with their icons and
 * one-line summaries ([SettingsHub]), the desktop's home beside the open page ([SettingsCategoryRail]), the header of a page
 * ([SettingsPageHeader]), the search field ([SettingsSearchPill]) and the search results ([SettingsSearchResults]). The rows of the
 * pages themselves are the blocks of CosmoSettingsBlocks.kt.
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

/** The order of the settings home's tiles: the account, what you listen to, what is yours, the other modes, and the rest. */
private val HUB_ORDER = listOf(
    SettingsCategory.ACCOUNT,
    SettingsCategory.PLAYBACK, SettingsCategory.SOURCES, SettingsCategory.DOWNLOADS, SettingsCategory.LYRICS,
    SettingsCategory.APPEARANCE, SettingsCategory.LISTENING,
    SettingsCategory.VIDEO, SettingsCategory.READ, SettingsCategory.PODIO,
    SettingsCategory.INTEGRATIONS, SettingsCategory.PRIVACY, SettingsCategory.ABOUT,
)

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


/** The settings home's title in Orbit: the page heading in the wide face with its peach full stop, for a host that draws its own bar. */
@Composable
fun SettingsHubTitle(text: String, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    OrbitPageHeading(
        title = text,
        label = "",
        fonts = fonts,
        look = look,
        modifier = modifier.padding(start = HUB_SIDE, end = HUB_SIDE, top = 12.dp, bottom = 16.dp).semantics { heading() },
    )
}

/** The settings search field: the Orbit search pill (60 dp, ink at 8 %, the magnifier and a clear button once something is typed). */
@Composable
fun SettingsSearchPill(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    OrbitSearchPill(query, onQueryChange, onSubmit = {}, placeholder = placeholder, fonts = orbitFonts(), look = orbitLook(), focusRequester = focus, modifier = modifier)
}

/**
 * The account card that leads Settings (board 2): Home's colour card from the account's picture, the picture as a disc, the mono
 * [label] ("Account · Google"), the [name] and the [line] under it (the sync status). No bite, since nothing plays here; [onClick]
 * opens the account (or signs in). [compact] is the phone's.
 */
@Composable
fun SettingsAccountCard(
    label: String,
    name: String,
    line: String?,
    pictureUrl: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    OrbitAccountCard(label, name, line, pictureUrl, orbitFonts(), onClick, modifier.testTag("settings:account"), compact = compact)
}

/**
 * The settings home (board 2): [accountCard] first when there is one (it stands in for the Account category), then one tile per
 * category of [entries] in two columns: its colour tile with the category's icon, its name and its summary on one line. Picking one
 * calls [onOpen]. A cell under 240 dp wide (the phone) stacks the icon over the words.
 */
@Composable
fun SettingsHub(
    entries: List<SettingsCategoryEntry>,
    onOpen: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
    accountCard: (@Composable () -> Unit)? = null,
) {
    val shown = hubOrder(entries).filter { accountCard == null || it.category != SettingsCategory.ACCOUNT }
    Column(modifier.fillMaxWidth().padding(horizontal = HUB_SIDE)) {
        if (accountCard != null) {
            accountCard()
            Spacer(Modifier.height(16.dp))
        }
        SettingsTiles(shown, selected = null, onOpen = onOpen, tag = "settings:hub:")
    }
}

/** [entries] in the home's order, then any the order does not name. */
private fun hubOrder(entries: List<SettingsCategoryEntry>): List<SettingsCategoryEntry> =
    HUB_ORDER.mapNotNull { category -> entries.firstOrNull { it.category == category } } + entries.filter { it.category !in HUB_ORDER }

@Composable
private fun SettingsTiles(entries: List<SettingsCategoryEntry>, selected: SettingsCategory?, onOpen: (SettingsCategory) -> Unit, tag: String, columns: Int = 2) {
    val fonts = orbitFonts()
    val look = orbitLook()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = (maxWidth - 12.dp * (columns - 1)) / columns < 240.dp
        OrbitTileGrid(entries.size, columns = columns) { i, cell ->
            val entry = entries[i]
            OrbitSettingsTile(
                icon = entry.icon,
                fill = settingsCategoryTint(entry.category),
                title = entry.title,
                summary = entry.summary,
                fonts = fonts,
                look = look,
                onClick = { onOpen(entry.category) },
                modifier = cell.testTag(tag + entry.category.name),
                selected = selected?.let { it == entry.category },
                stacked = stacked,
            )
        }
    }
}

/**
 * The search results: [results] best first, each a tile of the settings home's look (its category's colour tile, its title, and where
 * it lives, "Playback & audio quality · Output", before its summary). Picking one calls [onPick]; the screen then opens its page and
 * lights the row up. With no results, a quiet card says so ([emptyMessage], or "No settings match").
 */
@Composable
fun SettingsSearchResults(
    results: List<SettingsItem>,
    query: String,
    onPick: (SettingsItem) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.fillMaxWidth().padding(horizontal = HUB_SIDE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (results.isEmpty()) {
            val message = emptyMessage ?: stringResource(Res.string.cosmo_settings_no_results, query.trim())
            OrbitStatCard(SettingsText.RESULTS.text(), message, null, fonts, look, Modifier.padding(top = 12.dp).testTag("settings:results:empty"))
            return@Column
        }
        OrbitLabel(SettingsText.RESULTS.text(), fonts, look.dim, Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp))
        results.forEach { item ->
            val where = listOfNotNull(item.category.title().takeIf { it.isNotEmpty() }, item.group?.takeIf { it.isNotBlank() && it != item.title })
                .distinct()
                .joinToString(" · ")
            val summary = listOfNotNull(where.takeIf { it.isNotEmpty() }, item.summary?.takeIf { it.isNotBlank() && it != item.title })
                .joinToString(" — ")
            OrbitSettingsTile(
                icon = item.category.icon,
                fill = settingsCategoryTint(item.category),
                title = item.title,
                summary = summary,
                fonts = fonts,
                look = look,
                onClick = { onPick(item) },
                modifier = Modifier.testTag("settings:result:${item.id}"),
                summaryLines = 2,
            )
        }
    }
}

/**
 * The top of a settings page: a round back button ([onBack], where the page was opened from the settings home), the mono "Settings"
 * label over the page's [title] in the wide face with its peach full stop, and [subtitle] saying what the page holds.
 */
@Composable
fun SettingsPageHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.fillMaxWidth().padding(horizontal = HUB_SIDE)) {
        if (onBack != null) {
            OrbitRoundButton(CosmoIcons.ArrowLeft, SettingsText.BACK.text(), look.ink, onBack, Modifier.padding(top = 4.dp, bottom = 14.dp).testTag("settings:back"))
        }
        OrbitLabel("Settings", fonts, look.dim, Modifier.padding(start = 2.dp))
        Text(
            buildAnnotatedString {
                append(title)
                pushStyle(SpanStyle(color = look.accent)); append("."); pop()
            },
            style = fonts.heroTitle.copy(fontSize = 30.sp, lineHeight = 33.sp),
            color = look.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp).semantics { heading() },
        )
        if (subtitle != null) {
            Text(subtitle, style = fonts.body, color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        }
    }
}

/**
 * The desktop's settings home beside the page (board 2, the left column): [accountCard] when there is one, then a tile per entry in
 * [columns] columns, the [selected] one solid ink (null selects none, while the search is open). Each tile is a tab of the list, so a
 * screen reader says which one is open.
 */
@Composable
fun SettingsCategoryRail(
    entries: List<SettingsCategoryEntry>,
    selected: SettingsCategory?,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 1,
    accountCard: (@Composable () -> Unit)? = null,
) {
    // The account card stands in for the Account tile, except while the Account page is the one open beside it.
    val shown = hubOrder(entries).filter { accountCard == null || it.category != SettingsCategory.ACCOUNT || it.category == selected }
    Column(modifier.selectableGroup()) {
        if (accountCard != null) {
            accountCard()
            Spacer(Modifier.height(16.dp))
        }
        SettingsTiles(shown, selected = selected ?: SettingsCategory.ALL, onOpen = onSelect, tag = "settings:rail:", columns = columns)
    }
}

/** A settings home's distance from the sides of its page. */
private val HUB_SIDE = 16.dp

/** The words TMDB's terms ask every app that uses its API to show. */
const val TMDB_NOTICE = "This product uses the TMDB API but is not endorsed or certified by TMDB."

/**
 * TMDB's attribution for About's credits (Watch mode's films and series come from TMDB): its mark and [TMDB_NOTICE], in one quiet
 * row. The mark is drawn here in TMDB's colours (its green to its blue) until the official logo file is added to the resources.
 */
@Composable
fun TmdbAttribution(modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().padding(horizontal = HUB_SIDE).testTag("settings:about:tmdb"),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .height(28.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF90CEA1), Color(0xFF3CBEC9), Color(0xFF00B3E5))),
                    androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = "TMDB" },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text("TMDB", style = fonts.heroTitle.copy(fontSize = 14.sp, lineHeight = 16.sp), color = Color(0xFF0D253F), maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Text(TMDB_NOTICE, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 16.sp), color = look.dim, modifier = Modifier.weight(1f))
    }
}
