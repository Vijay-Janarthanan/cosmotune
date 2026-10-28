package com.cosmotune.ui.listening

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.ListeningActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Shared interactive activity view for Android and every desktop target. */
@Composable
fun ListeningActivityPanel(days: Map<String, Long>, hourly: List<Long>, modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface, today: LocalDate = LocalDate.now()) {
    val window = remember(days, today) { ListeningActivity.window(days, today) }
    var selectedDay by remember(days, today) { mutableStateOf(window.last()) }
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    val high = window.maxOf { it.listenedMs }.coerceAtLeast(1)
    val active = window.count { it.listenedMs > 0 }
    val streak = ListeningActivity.longestStreak(window.associate { it.date.toString() to it.listenedMs })
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Daily activity", style = MaterialTheme.typography.titleLarge, color = textColor)
        Text("Last 4 weeks · $active active ${if (active == 1) "day" else "days"} · longest streak $streak ${if (streak == 1) "day" else "days"}",
            style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = .7f))
        window.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    val description = "${day.date}: ${duration(day.listenedMs)}"
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = if (day.listenedMs == 0L) .08f else .2f + .65f * day.listenedMs.toFloat() / high))
                        .border(2.dp, if (day == selectedDay) accent else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClickLabel = description) { selectedDay = day; selectedHour = null }
                        .semantics { contentDescription = description }.testTag("activity:day:${day.date}"))
                }
            }
        }
        Text("${selectedDay.date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))} · ${duration(selectedDay.listenedMs)}",
            color = textColor, modifier = Modifier.testTag("activity:selection"))
        Text("Listening rhythm", style = MaterialTheme.typography.titleMedium, color = textColor)
        val bins = (0..7).map { bin -> (bin * 3 until bin * 3 + 3).sumOf { hourly.getOrNull(it)?.coerceAtLeast(0) ?: 0 } }
        val max = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
        Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            bins.forEachIndexed { index, ms ->
                val label = "%02d:00–%02d:00: %s".format(index * 3, index * 3 + 3, duration(ms))
                Column(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button, onClickLabel = label) {
                    selectedHour = index
                }.semantics { contentDescription = label }.testTag("activity:hour:$index"), verticalArrangement = Arrangement.Bottom) {
                    Box(Modifier.fillMaxWidth().height((4 + 48 * ms.toFloat() / max).dp).clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = if (selectedHour == index) 1f else .55f)))
                    Text("%02d".format(index * 3), style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = .7f))
                }
            }
        }
        selectedHour?.let { hour ->
            Text("%02d:00–%02d:00 · %s".format(hour * 3, hour * 3 + 3, duration(bins[hour])),
                color = textColor, modifier = Modifier.testTag("activity:hour:selection"))
        }
    }
}

private fun duration(ms: Long): String = when {
    ms <= 0 -> "No listening recorded"
    ms < 60_000 -> "${ms / 1_000} seconds"
    ms < 3_600_000 -> "${ms / 60_000} minutes"
    else -> "${ms / 3_600_000} h ${(ms / 60_000) % 60} min"
}
