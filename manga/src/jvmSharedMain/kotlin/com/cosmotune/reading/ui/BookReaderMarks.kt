package com.cosmotune.reading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.OrbitReaderButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.serialization.json.*

/** Words selected on a chapter: offsets into its text, where they sit, and the words. */
internal data class ReadingSelection(val text: String, val start: Int, val end: Int, val progression: Double)

/** What the page reported: a selection, a tapped highlight, a tapped note mark (its words, or the address of the note) or picture. */
internal data class PageEvent(val selection: ReadingSelection?, val tapped: String?, val noteText: String? = null, val noteAddress: String? = null, val image: String? = null)

internal fun parsePageEvent(raw: String?): PageEvent {
    val (selection, tapped) = parseSelection(raw)
    if (raw.isNullOrBlank()) return PageEvent(null, null)
    return runCatching {
        val value = Json.parseToJsonElement(raw).jsonObject
        val note = value["note"] as? JsonObject
        PageEvent(selection, tapped, note?.get("text")?.jsonPrimitive?.contentOrNull, note?.get("href")?.jsonPrimitive?.contentOrNull,
            (value["image"] as? JsonPrimitive)?.contentOrNull)
    }.getOrDefault(PageEvent(selection, tapped))
}

/** What [ReadingEpubScripts.selection] gave: a selection, a tapped highlight's id, or neither. */
internal fun parseSelection(raw: String?): Pair<ReadingSelection?, String?> {
    if (raw.isNullOrBlank()) return null to null
    return runCatching {
        val value = Json.parseToJsonElement(raw).jsonObject
        val tapped = (value["tapped"] as? JsonPrimitive)?.contentOrNull
        val text = value["text"]?.jsonPrimitive?.contentOrNull
        val start = value["start"]?.jsonPrimitive?.intOrNull
        val end = value["end"]?.jsonPrimitive?.intOrNull
        val selection = if (text != null && start != null && end != null && end > start)
            ReadingSelection(text, start, end, value["progression"]?.jsonPrimitive?.doubleOrNull ?: 0.0) else null
        selection to tapped
    }.getOrDefault(null to null)
}

/** The colour a highlight's style shows in the app's own rows (the page draws its own, a little lighter). */
internal fun markColor(style: ReadingMarkStyle): Color = when (style) {
    ReadingMarkStyle.YELLOW -> Color(0xFFF2C14E); ReadingMarkStyle.GREEN -> Color(0xFF6CBF78); ReadingMarkStyle.BLUE -> Color(0xFF5F9BE6)
    ReadingMarkStyle.PINK -> Color(0xFFE77AB0); ReadingMarkStyle.UNDERLINE -> Color(0xFFE0533D)
}

/** A highlight colour as the Orbit round button: a dot (or the underline) in the colour, filled with it when chosen. */
@Composable
private fun MarkSwatch(style: ReadingMarkStyle, fill: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OrbitReaderButton(if (style == ReadingMarkStyle.UNDERLINE) Icons.Rounded.FormatUnderlined else Icons.Rounded.Circle,
        if (style == ReadingMarkStyle.UNDERLINE) "Underline" else "Highlight in ${style.label.lowercase()}", markColor(style), fill, onClick, modifier, on = selected)
}

/**
 * The card over the page's foot while words are selected: the four colours and underline (each highlights at once), a note, look
 * up and copy. [extra] adds the reader's other actions on the words (read aloud from here).
 */
@Composable
internal fun ReaderSelectionBar(selection: ReadingSelection, fill: Color, ink: Color, onHighlight: (ReadingMarkStyle) -> Unit, onNote: () -> Unit,
    onLookUp: (() -> Unit)?, onCopy: () -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    val fonts = orbitFonts()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(fill).padding(horizontal = 16.dp, vertical = 12.dp).testTag("books:selection")) {
        Text("“" + selection.text.replace(Regex("\\s+"), " ").trim() + "”", style = fonts.body, color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadingMarkStyle.entries.forEach { style -> MarkSwatch(style, fill, false, { onHighlight(style) }, Modifier.testTag("books:mark:${style.name.lowercase()}")) }
            OrbitReaderButton(Icons.Rounded.EditNote, "Add a note", ink, fill, onNote, Modifier.testTag("books:selection:note"))
            if (onLookUp != null) OrbitReaderButton(CosmoIcons.Search, "Look up", ink, fill, onLookUp, Modifier.testTag("books:selection:look-up"))
            OrbitReaderButton(Icons.Rounded.ContentCopy, "Copy", ink, fill, onCopy, Modifier.testTag("books:selection:copy"))
            extra()
        }
    }
}

/** One highlight as a row: its colour as a bar at the start, the words, the note under them, and the cross that removes it. */
@Composable
internal fun HighlightRow(mark: ReadingHighlight, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier, showChapter: Boolean = true) {
    val look = orbitLook()
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp).testTag("books:highlight:${mark.id}"),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).width(4.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(2.dp)).background(markColor(mark.style)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Text(mark.text.replace(Regex("\\s+"), " ").trim(), style = ReadType.body(), color = look.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (mark.note.isNotBlank()) Text(mark.note, style = ReadType.body(), color = look.dim, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (showChapter) Text(mark.chapter.ifBlank { "Chapter ${mark.section + 1}" }, style = ReadType.caption(), color = look.dim, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        ReadIconButton(CosmoIcons.Close, "Remove highlight", onRemove)
    }
}

/** A highlight's own sheet (a tap on it on the page): its colour, its note, remove. */
@Composable
internal fun HighlightSheet(mark: ReadingHighlight, onStyle: (ReadingMarkStyle) -> Unit, onNote: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var note by remember(mark.id) { mutableStateOf(mark.note) }
    val look = orbitLook()
    KitSheet(onDismiss = { if (note != mark.note) onNote(note); onDismiss() }) {
        ReadSheetHeader("Highlight", onDone = { if (note != mark.note) onNote(note); onDismiss() })
        Text(mark.text, style = ReadType.body(), color = look.ink, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        ReadSheetSection("Colour") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingMarkStyle.entries.forEach { style -> MarkSwatch(style, look.ground, mark.style == style, { onStyle(style) }) }
            }
        }
        ReadSheetSection("Note") {
            OrbitInputField(note, { note = it.take(10_000) }, "Add a note", modifier = Modifier.fillMaxWidth().testTag("books:highlight:note"), singleLine = false, sentences = true, minLines = 3)
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { ReadButton("Remove highlight", { onRemove(); onDismiss() }, kind = ReadKind.TONAL, icon = CosmoIcons.Close) }
        Spacer(Modifier.height(16.dp))
    }
}

/** A note for words not yet highlighted: saving it highlights them in yellow with the note. */
@Composable
internal fun NoteSheet(words: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Note", onDone = { onSave(note); onDismiss() }, doneLabel = "Save")
        Text("“" + words.replace(Regex("\\s+"), " ").trim() + "”", style = ReadType.body(), color = orbitLook().dim, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            OrbitInputField(note, { note = it.take(10_000) }, "Your note", modifier = Modifier.fillMaxWidth().testTag("books:note"), singleLine = false, sentences = true, minLines = 4)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The reader's list of this book's highlights and notes, in book order, with Export. */
@Composable
internal fun HighlightsPanel(marks: List<ReadingHighlight>, onOpen: (ReadingHighlight) -> Unit, onRemove: (ReadingHighlight) -> Unit,
    onExport: (markdown: Boolean) -> Unit, onDismiss: () -> Unit) {
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Highlights and notes", onDone = onDismiss, doneLabel = "Close")
        if (marks.isNotEmpty()) ReadChipRow(Modifier.padding(vertical = 4.dp)) {
            ReadChip("Export as Markdown", false, { onExport(true) }, Modifier.testTag("books:export:markdown"), icon = CosmoIcons.Download)
            ReadChip("Export as a web page", false, { onExport(false) }, Modifier.testTag("books:export:html"), icon = CosmoIcons.Download)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            if (marks.isEmpty()) item {
                Text("Select words on the page to highlight them or add a note.", style = ReadType.body(), color = orbitLook().dim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            items(marks, key = { it.id }) { mark -> HighlightRow(mark, { onOpen(mark) }, { onRemove(mark) }) }
        }
        Spacer(Modifier.height(16.dp))
    }
}
