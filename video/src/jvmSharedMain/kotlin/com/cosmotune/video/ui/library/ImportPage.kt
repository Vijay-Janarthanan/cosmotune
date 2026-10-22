package com.cosmotune.video.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.blocks.cosmoTile
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.sheets.CosmoToast
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.library.ImportReport
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_export
import com.cosmotune.video.resources.video_export_done
import com.cosmotune.video.resources.video_import
import com.cosmotune.video.resources.video_import_confirm
import com.cosmotune.video.resources.video_import_cosmotune
import com.cosmotune.video.resources.video_import_done
import com.cosmotune.video.resources.video_import_export
import com.cosmotune.video.resources.video_import_failed
import com.cosmotune.video.resources.video_import_lookups
import com.cosmotune.video.resources.video_import_newpipe_db
import com.cosmotune.video.resources.video_import_newpipe_subs
import com.cosmotune.video.resources.video_import_opml
import com.cosmotune.video.resources.video_import_skipped
import com.cosmotune.video.resources.video_import_summary
import com.cosmotune.video.resources.video_import_takeout
import com.cosmotune.video.resources.video_set_export
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.music.sharedui.resources.cancel
import com.cosmotune.music.sharedui.resources.cosmo_close
import com.cosmotune.music.ui.components.FLOATING_BAR_MAX_WIDTH
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import com.cosmotune.music.sharedui.resources.Res as SharedRes

/**
 * Import and export (spec 8.1, 8.2): the heading; "Import" with a row for each kind of file (NewPipe subscriptions, a NewPipe backup,
 * Google Takeout as a ZIP, a CSV or a JSON file, OPML, Cosmotune's own export), each opening the file picker; under them, once a file
 * is picked, the summary card ("123 channels, 4,210 history entries, 6 playlists", what was skipped, the channels still being looked
 * up with the lookups' progress) with Import and Cancel, then "Imported" with what was added, or "This file could not be read";
 * then "Export" with the row that saves the whole library as `cosmotune-video-<date>.json`, and "Saved <name>" as a toast.
 *
 * The file is read for what it is ([ImportReader]), whichever row opened the picker; nothing is written before Import.
 */
@Composable
fun ImportPage(contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val platform = rememberImportPlatform()
    val scope = rememberCoroutineScope()
    val state = remember(graph, platform) { ImportState(scope, graph.library, graph.source, platform) }
    val toast = remember { PageToastState() }
    val picker = rememberFilePicker(
        onOpened = { state.read(it) },
        onCreated = { file -> state.export(file) { written -> toast.show(if (written) PageToastMessage.Saved(file.name) else PageToastMessage.NotSaved) } },
    )
    val rows = listOf(
        ImportRow(ImportKind.NEWPIPE_SUBSCRIPTIONS, VideoIcons.Subscriptions, Res.string.video_import_newpipe_subs),
        ImportRow(ImportKind.NEWPIPE_BACKUP, VideoIcons.History, Res.string.video_import_newpipe_db),
        ImportRow(ImportKind.TAKEOUT, CosmoIcons.Library, Res.string.video_import_takeout),
        ImportRow(ImportKind.OPML, CosmoIcons.List, Res.string.video_import_opml),
        ImportRow(ImportKind.COSMOTUNE, CosmoIcons.Download, Res.string.video_import_cosmotune),
    )
    val phase = state.phase

    Box(Modifier.fillMaxSize()) {
        LibraryList(contentPadding, wide) { cell ->
            item(key = "heading") { LibraryHeader(stringResource(Res.string.video_import_export), wide, cell) }
            item(key = "import:header") { CosmoOverline(stringResource(Res.string.video_import), cell) }
            items(rows, key = { "import:" + it.kind.name }) { row ->
                LinkRow(row.icon, stringResource(row.title), count = null, onClick = { picker.open(row.kind.mimeTypes) }, modifier = cell)
            }
            if (phase != ImportPhase.Idle) {
                item(key = "summary") {
                    SummaryCard(
                        phase = phase,
                        onImport = { state.confirm() },
                        onDismiss = { state.dismiss() },
                        modifier = cell,
                    )
                }
            }
            item(key = "export:header") { CosmoOverline(stringResource(Res.string.video_export), cell) }
            item(key = "export") {
                LinkRow(
                    VideoIcons.Upload,
                    stringResource(Res.string.video_set_export),
                    count = null,
                    onClick = { picker.create(LibraryPlan.exportFileName(LocalDate.now()), LibraryPlan.EXPORT_MIME) },
                    modifier = cell,
                )
            }
        }
        PageToast(toast, contentPadding)
    }
}

/** A row of the import list: what it picks, its icon and its name. */
private class ImportRow(val kind: ImportKind, val icon: ImageVector, val title: StringResource)

/**
 * The summary card (spec 8.2), a tile of the page (superellipse 24, padding 14 16, margin 8 16): the file's name, then
 * - while reading: a shimmer line;
 * - while looking channels up, and once ready: `video_import_summary` (channels, history entries, playlists), what was skipped, and
 *   while looking up, how many channels are left to look up over a progress line; Import (once ready) and Cancel;
 * - done: "Imported" and what the merge added;
 * - failed: "This file could not be read".
 * The outcome lines are a polite live region, so assistive technology reads them when they change.
 */
@Composable
internal fun SummaryCard(phase: ImportPhase, onImport: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val fileName = when (phase) {
        ImportPhase.Idle -> ""
        is ImportPhase.Reading -> phase.fileName
        is ImportPhase.Resolving -> phase.pending.fileName
        is ImportPhase.Ready -> phase.pending.fileName
        is ImportPhase.Importing -> phase.pending.fileName
        is ImportPhase.Done -> phase.pending.fileName
        is ImportPhase.Failed -> phase.fileName
    }
    val titleStyle = remember(type, fileName) { type.forText(type.title(TypeScale.TITLE_S), fileName) }
    val lineStyle = remember(type) { TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = LINE_SP.sp, lineHeight = LINE_SP.sp * 1.45f) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = CARD_MARGIN_V)
            .cosmoTile(CARD_SHAPE)
            .padding(horizontal = CARD_PADDING_H, vertical = CARD_PADDING_V),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = fileName, style = titleStyle, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (phase is ImportPhase.Done || phase is ImportPhase.Failed) {
                CosmoIconButton(CosmoIcons.Close, stringResource(SharedRes.string.cosmo_close), onDismiss, style = CosmoIconButtonStyle.PLAIN, size = 40.dp)
            }
        }
        Spacer(Modifier.height(LINE_GAP))
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (phase) {
                ImportPhase.Idle -> Unit
                is ImportPhase.Reading -> CosmoShimmer(Modifier.fillMaxWidth(SHIMMER_WIDTH).height(SHIMMER_HEIGHT))
                is ImportPhase.Resolving -> {
                    SummaryLines(phase.pending.channels, phase.pending.historyEntries, phase.pending.playlists, phase.pending.skipped, lineStyle)
                    val left = phase.total - phase.done
                    Text(pluralStringResource(Res.plurals.video_import_lookups, left, left), style = lineStyle, color = Color(palette.textSecondary))
                    Spacer(Modifier.height(LINE_GAP))
                    LookupProgress(if (phase.total == 0) 1f else phase.done.toFloat() / phase.total)
                }
                is ImportPhase.Ready -> SummaryLines(phase.pending.channels, phase.pending.historyEntries, phase.pending.playlists, phase.pending.skipped, lineStyle)
                is ImportPhase.Importing -> SummaryLines(phase.pending.channels, phase.pending.historyEntries, phase.pending.playlists, phase.pending.skipped, lineStyle)
                is ImportPhase.Done -> {
                    Text(stringResource(Res.string.video_import_done), style = lineStyle.copy(fontWeight = FontWeight.SemiBold), color = Color(palette.textPrimary))
                    ReportLines(phase.report, phase.pending.skipped, lineStyle)
                }
                is ImportPhase.Failed -> Text(stringResource(Res.string.video_import_failed), style = lineStyle, color = Color(palette.textSecondary))
            }
        }
        if (phase is ImportPhase.Resolving || phase is ImportPhase.Ready || phase is ImportPhase.Importing) {
            Row(Modifier.fillMaxWidth().padding(top = BUTTONS_TOP), verticalAlignment = Alignment.CenterVertically) {
                CosmoButton(
                    stringResource(Res.string.video_import_confirm),
                    onImport,
                    kind = CosmoButtonKind.PRIMARY,
                    size = CosmoButtonSize.SMALL,
                    enabled = phase is ImportPhase.Ready,
                )
                Spacer(Modifier.width(BUTTON_GAP))
                CosmoButton(
                    stringResource(SharedRes.string.cancel),
                    onDismiss,
                    kind = CosmoButtonKind.TEXT,
                    size = CosmoButtonSize.SMALL,
                    enabled = phase !is ImportPhase.Importing,
                )
            }
        }
    }
}

/** "123 channels, 4,210 history entries, 6 playlists" and, when any, "3 skipped" (spec 8.2). */
@Composable
private fun SummaryLines(channels: Int, history: Int, playlists: Int, skipped: Int, style: TextStyle) {
    val palette = LocalCosmoColors.current.palette
    Text(stringResource(Res.string.video_import_summary, channels, history, playlists), style = style, color = Color(palette.textSecondary))
    if (skipped > 0) Text(pluralStringResource(Res.plurals.video_import_skipped, skipped, skipped), style = style, color = Color(palette.textTertiary))
}

/** What the merge added (the library's own report: a channel, an entry or a playlist already there is not counted again) and what the file skipped. */
@Composable
private fun ReportLines(report: ImportReport, skipped: Int, style: TextStyle) {
    SummaryLines(report.subscriptions, report.history, report.playlists, skipped + report.skipped, style)
}

/** The lookups' progress: a 4 dp line, the done part in `accent` over `textPrimary` at 10 %, both read while drawing. */
@Composable
private fun LookupProgress(fraction: Float) {
    val colors = LocalCosmoColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(PROGRESS_HEIGHT)
            .clip(SuperellipseShape(PROGRESS_HEIGHT / 2))
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = PROGRESS_TRACK_ALPHA), cornerRadius = radius)
                drawRoundRect(colors.live(CoverField.ACCENT), topLeft = Offset.Zero, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
            },
    )
}

/** What the page's toast says: the export was saved under [Saved.name] ("Saved cosmotune-video-2026-10-16.json"), or it could not be written. */
private sealed interface PageToastMessage {
    data class Saved(val name: String) : PageToastMessage

    data object NotSaved : PageToastMessage
}

/** The page's own toast: [show] puts it up for 4 s; a second one replaces it. */
@Stable
private class PageToastState {
    var message: PageToastMessage? by mutableStateOf(null)
        private set
    var serial by mutableIntStateOf(0)
        private set

    fun show(text: PageToastMessage) {
        serial += 1
        message = text
    }

    fun clear(shown: Int) {
        if (serial == shown) message = null
    }
}

/** [toast] as part two's toast above the floating chrome ([contentPadding]'s bottom), at most the floating bars' width, for 4 s. */
@Composable
private fun PageToast(toast: PageToastState, contentPadding: PaddingValues) {
    val shown = toast.message ?: return
    val serial = toast.serial
    val message = when (shown) {
        is PageToastMessage.Saved -> stringResource(Res.string.video_export_done, shown.name)
        PageToastMessage.NotSaved -> stringResource(Res.string.video_import_failed)
    }
    LaunchedEffect(serial) {
        delay(TOAST_MS)
        toast.clear(serial)
    }
    Box(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding()), contentAlignment = Alignment.BottomCenter) {
        key(serial) { CosmoToast(message, Modifier.widthIn(max = FLOATING_BAR_MAX_WIDTH)) }
    }
}

/** The card: superellipse 24 (`cosmoTile`), margin 8 16, padding 14 16 (the watch page's description tile, spec 5.4). */
private val CARD_SHAPE = SuperellipseShape(CosmoRadii.card)
private val CARD_MARGIN_V = 8.dp
private val CARD_PADDING_H = 16.dp
private val CARD_PADDING_V = 14.dp
private const val LINE_SP = 14f
private val LINE_GAP = 6.dp
private val BUTTONS_TOP = 12.dp
private val BUTTON_GAP = 8.dp
private const val SHIMMER_WIDTH = 0.6f
private val SHIMMER_HEIGHT = 14.dp
private val PROGRESS_HEIGHT = 4.dp
private const val PROGRESS_TRACK_ALPHA = 0.10f

/** How long the toast stays (`CosmoToast`: 4 s, the host's to keep). */
private const val TOAST_MS = 4_000L
