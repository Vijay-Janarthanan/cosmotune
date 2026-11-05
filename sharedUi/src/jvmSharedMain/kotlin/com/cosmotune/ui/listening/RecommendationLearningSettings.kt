package com.cosmotune.ui.listening

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.recommendations.LocalPreferenceLearning

@Composable
fun RecommendationLearningSettings() {
    val status by LocalPreferenceLearning.status.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Learn from my listening", style = MaterialTheme.typography.titleMedium)
                Text("Adjust recommendations on this device from listening and early skips. About 66 KB; nothing is uploaded.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(status.enabled, LocalPreferenceLearning::setEnabled, enabled = status.ready)
        }
        Text("${status.observations} listening signals", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = LocalPreferenceLearning::reset, enabled = status.ready) { Text("Reset learning") }
    }
}
