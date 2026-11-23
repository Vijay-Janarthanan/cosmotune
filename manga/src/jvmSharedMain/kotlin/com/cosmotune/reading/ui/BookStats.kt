package com.cosmotune.reading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.ReadingProgressMath
import com.cosmotune.reading.data.ReadingState
import com.cosmotune.reading.data.ReadingStatistics
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import java.time.LocalDate

/** Reading statistics: the last seven days as bars, the streak, books finished this year and the books read longest. */
@Composable
internal fun ReadingStatsSheet(state: ReadingState, onDismiss: () -> Unit) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val today = LocalDate.now()
    val week = ReadingStatistics.week(state, today)
    val most = week.maxOf { it.second }.coerceAtLeast(1)
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Reading time", onDone = onDismiss, doneLabel = "Close")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("books:stats")) {
            Text(ReadingProgressMath.duration(week.sumOf { it.second }) + " in the last seven days", style = ReadType.rowTitle(), color = look.ink)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                week.forEach { (day, ms) ->
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight((ms.toFloat() / most).coerceIn(0.02f, 1f) * 0.8f).clip(ReadShape.sm)
                            .background(if (ms > 0) look.accent else look.row))
                        Spacer(Modifier.height(8.dp))
                        Text(day, style = fonts.label, color = look.dim, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            val streak = ReadingStatistics.streak(state, today)
            listOf(
                "Days in a row" to if (streak == 1) "1 day" else "$streak days",
                "Finished this year" to ReadingStatistics.finishedIn(state, today.year).toString(),
                "All reading" to ReadingProgressMath.duration(state.readingMs.values.sum()),
            ).forEach { (name, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(name, style = ReadType.body(), color = look.dim, modifier = Modifier.weight(1f))
                    Text(value, style = ReadType.body(), color = look.ink)
                }
            }
            val top = ReadingStatistics.topBooks(state)
            if (top.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                ReadOverline("Read longest", inset = 0.dp)
                top.forEach { (book, ms) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(book.title, style = ReadType.body(), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(ReadingProgressMath.duration(ms), style = ReadType.body(), color = look.dim)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
