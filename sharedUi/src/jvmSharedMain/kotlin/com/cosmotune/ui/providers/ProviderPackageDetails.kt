package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.providers.*
import com.cosmotune.ui.theme.CosmoRadii

/** Uninstalled/unapproved packages use a local initial; their remote icon never reaches Coil directly. */
@Composable
internal fun ProviderPackageAvatar(title: String, installed: ProviderInstall?, iconUrl: String?) {
    val artwork = ProviderHub.artwork
    val reference = remember(artwork, installed?.sha256, installed?.allowArtwork, installed?.enabled, iconUrl) {
        if (installed?.enabled == true && installed.allowArtwork) artwork?.reference("provider:${installed.manifest.name}", iconUrl) else null
    }
    Surface(shape = RoundedCornerShape(CosmoRadii.rMd), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
            if (reference != null) AsyncImage(reference, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp))
        }
    }
}

@Composable
fun ProviderServiceReportView(report: ProviderServiceReport, modifier: Modifier = Modifier) {
    Column(modifier.testTag("providers:service-report"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        report.checks.forEach { result ->
            val status = when (result.state) {
                ProviderServiceState.REACHABLE -> "Endpoint reachable"
                ProviderServiceState.HEALTHY -> "Service reports healthy"
                ProviderServiceState.DEGRADED -> "Service reports limited access"
                ProviderServiceState.UNAVAILABLE -> "Endpoint or service unavailable"
                ProviderServiceState.UNKNOWN -> "Status unconfirmed"
            }
            Text(buildString {
                append(result.label); append(" · "); append(status)
                result.httpStatus?.let { append(" · HTTP "); append(it) }
                append(" · "); append(result.latencyMs); append(" ms")
            }, style = MaterialTheme.typography.bodySmall,
                color = if (result.state == ProviderServiceState.UNAVAILABLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Checked " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date(report.checkedAtMs)), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Endpoint status does not confirm that a song is available or that its audio can play. Provider switches and saved connections are unchanged.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ProviderPackageDetails(installed: ProviderInstall, report: ProviderServiceReport?, onDismiss: () -> Unit) {
    val manifest = installed.manifest
    AlertDialog(onDismissRequest = onDismiss, title = { Text(manifest.title) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Version ${manifest.version}", style = MaterialTheme.typography.labelLarge) }
            if (manifest.description.isNotBlank()) item { Text(manifest.description) }
            item { Text("Provides", style = MaterialTheme.typography.titleSmall) }
            items(manifest.type) { capability -> Text(when (capability) {
                "metadata_provider" -> "Music catalogue, search and collections"
                "download_provider" -> "Audio streams and downloads"
                "lyrics_provider" -> "Lyrics"
                else -> capability
            }) }
            if (manifest.qualityOptions.isNotEmpty()) {
                item { Text("Available quality choices", style = MaterialTheme.typography.titleSmall) }
                items(manifest.qualityOptions) { quality -> Column {
                    Text(quality.label.ifBlank { quality.id })
                    if (quality.description.isNotBlank()) Text(quality.description, style = MaterialTheme.typography.bodySmall)
                } }
                item { Text("These are provider offers. Actual format and quality are reported by the playing decoder.", style = MaterialTheme.typography.bodySmall) }
            }
            item { Text("Permissions", style = MaterialTheme.typography.titleSmall) }
            item { Text("Artwork downloads: ${if (installed.allowArtwork) "allowed" else "off"} · Local network: ${if (installed.allowLocalNetwork) "allowed" else "off"}") }
            item { Text(manifest.permissions.network.joinToString("\n").ifBlank { "No network domains" }, style = MaterialTheme.typography.bodySmall) }
            item { Text("Service status", style = MaterialTheme.typography.titleSmall) }
            item { if (report != null) ProviderServiceReportView(report)
                else Text(if (manifest.serviceHealth.isEmpty()) "This package declares no health checks." else "Not checked. Use Check services on the provider card.") }
            item { Text("Package fingerprint", style = MaterialTheme.typography.titleSmall) }
            item { SelectionContainer { Text(installed.sha256, style = MaterialTheme.typography.labelSmall) } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
