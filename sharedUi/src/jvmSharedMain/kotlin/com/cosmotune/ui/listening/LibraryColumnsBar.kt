package com.cosmotune.ui.listening

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.audio.LibraryAudioAnalysis
import com.cosmotune.listening.LibraryColumn
import com.cosmotune.listening.LibraryQualityFilter
import com.cosmotune.listening.LibrarySource
import com.cosmotune.listening.LibraryViewOptions
import com.cosmotune.music.data.model.Song

/**
 * Columns and filters for a main library song list, shared by Android and desktop: which extra
 * columns each row shows, and filters by source, measured quality and genre.
 */
@Composable
fun LibraryColumnsBar(options: LibraryViewOptions, genres: List<String>, onChange: (LibraryViewOptions) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()).testTag("library:columns:bar"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Menu("Columns · ${options.columns.size}", "library:columns:menu") { close ->
            LibraryColumn.entries.forEach { column ->
                DropdownMenuItem(text = { Text(column.label) }, modifier = Modifier.testTag("library:column:${column.name}"),
                    leadingIcon = { Checkbox(checked = column in options.columns, onCheckedChange = null) },
                    onClick = { onChange(options.copy(columns = if (column in options.columns) options.columns - column else options.columns + column)); close() })
            }
        }
        Menu(options.source?.label ?: "Any source", "library:filter:source") { close ->
            (listOf<LibrarySource?>(null) + LibrarySource.entries).forEach { source ->
                DropdownMenuItem(text = { Text(source?.label ?: "Any source") }, onClick = { onChange(options.copy(source = source)); close() })
            }
        }
        Menu(options.quality.label, "library:filter:quality") { close ->
            LibraryQualityFilter.entries.forEach { quality ->
                DropdownMenuItem(text = { Text(quality.label) }, modifier = Modifier.testTag("library:quality:${quality.name}"),
                    onClick = { onChange(options.copy(quality = quality)); close() })
            }
        }
        if (genres.isNotEmpty() || options.genre != null) Menu(options.genre ?: "Any genre", "library:filter:genre") { close ->
            (listOf<String?>(null) + genres).forEach { genre ->
                DropdownMenuItem(text = { Text(genre ?: "Any genre") }, onClick = { onChange(options.copy(genre = genre)); close() })
            }
        }
        if (options.filtering) TextButton(onClick = { onChange(options.copy(source = null, quality = LibraryQualityFilter.ANY, genre = null)) },
            modifier = Modifier.testTag("library:filter:clear")) { Text("Clear filters") }
    }
}

@Composable
private fun Menu(label: String, tag: String, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(label, maxLines = 1) }, modifier = Modifier.testTag(tag))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

/** The chosen column values for one row, as a single line under the title and artist. */
@Composable
fun LibraryColumnsLine(song: Song, options: LibraryViewOptions, analysis: LibraryAudioAnalysis?, modifier: Modifier = Modifier) {
    val cells = remember(song, options, analysis) { options.cells(song, analysis) }
    if (cells.isEmpty()) return
    Text(cells.joinToString("  ·  ") { it.second }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(vertical = 2.dp).testTag("library:columns:${song.videoId}"))
}
