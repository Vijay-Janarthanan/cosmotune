package com.cosmotune.podio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodioSaved
import com.cosmotune.podio.PodioStats
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.*
import java.time.LocalDate

/**
 * Your listening (AntennaPod's statistics, Pocket Casts' year in review): this week, this month, this year or everything, the time
 * heard, on how many days, and each show with its time and a bar, the most heard first.
 */
@Composable internal fun ListeningSheet(saved: PodioSaved, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var span by remember { mutableIntStateOf(0) }
    val today = LocalDate.now()
    val from = when (span) { 0 -> today.minusDays(6); 1 -> today.withDayOfMonth(1); 2 -> today.withDayOfYear(1); else -> LocalDate.of(2000, 1, 1) }
    val summary = remember(saved.listened, span) { PodioStats.summary(saved, from, today) }
    KitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
            .testTag("podio:listening"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OrbitLabel("Your listening", fonts, look.dim)
            OrbitChipRow(0.dp) {
                listOf("This week", "This month", "This year", "All time").forEachIndexed { index, title ->
                    OrbitSelectChip(title, span == index, fonts, look, { span = index }, haptic = false)
                }
            }
            Text(PodioStats.length(summary.totalMs), style = fonts.heroTitle.copy(fontSize = 30.sp, lineHeight = 34.sp), color = look.ink,
                modifier = Modifier.testTag("podio:listening:total"))
            Text(if (summary.totalMs == 0L) "Nothing heard in this time yet." else "of podcasts, on ${summary.days} ${if (summary.days == 1) "day" else "days"}",
                style = fonts.body, color = look.dim)
            val most = summary.byShow.firstOrNull()?.second ?: 1L
            summary.byShow.take(20).forEach { (id, ms) ->
                val title = saved.shows.firstOrNull { it.id == id }?.title ?: if (id.isEmpty()) "Other episodes" else "A show you no longer follow"
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = fonts.body, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
                        Text(PodioStats.length(ms), style = fonts.label.copy(fontSize = 13.sp), color = look.dim)
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(look.chip)) {
                        Box(Modifier.fillMaxWidth((ms.toFloat() / most).coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(look.accent))
                    }
                }
            }
        }
    }
}
