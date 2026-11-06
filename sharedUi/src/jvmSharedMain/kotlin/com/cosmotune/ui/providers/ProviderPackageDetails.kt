package com.cosmotune.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.providers.*
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * A package's tile: 44 dp, radius 14, the chip's fill with the package's initial in the accent. Uninstalled/unapproved packages use
 * the local initial; their remote icon never reaches Coil directly.
 */
@Composable
internal fun ProviderPackageAvatar(title: String, installed: ProviderInstall?, iconUrl: String?) {
    val artwork = ProviderHub.artwork
    val reference = remember(artwork, installed?.sha256, installed?.allowArtwork, installed?.enabled, iconUrl) {
        if (installed?.enabled == true && installed.allowArtwork) artwork?.reference("provider:${installed.manifest.name}", iconUrl) else null
    }
    val fonts = orbitFonts()
    val look = orbitLook()
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(look.chip), contentAlignment = Alignment.Center) {
        Text(title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty(), style = fonts.section.copy(fontSize = 19.sp), color = look.accent)
        if (reference != null) AsyncImage(reference, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(4.dp))
    }
}

@Composable
fun ProviderServiceReportView(report: ProviderServiceReport, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val small = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)
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
            }, style = small,
                color = if (result.state == ProviderServiceState.UNAVAILABLE) MaterialTheme.colorScheme.error else look.dim)
        }
        OrbitLabel("Checked " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date(report.checkedAtMs)), fonts, look.dim)
        Text("Endpoint status does not confirm that a song is available or that its audio can play. Provider switches and saved connections are unchanged.",
            style = small, color = look.dim)
    }
}

@Composable
internal fun ProviderPackageDetails(installed: ProviderInstall, report: ProviderServiceReport?, onDismiss: () -> Unit) {
    val manifest = installed.manifest
    val fonts = orbitFonts()
    val look = orbitLook()
    val heading = fonts.title.copy(fontSize = 14.sp)
    val small = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = manifest.title,
        overline = "Version ${manifest.version}",
        scroll = false,
        content = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (manifest.description.isNotBlank()) item { Text(manifest.description) }
            item { Text("Provides", style = heading, color = look.ink, modifier = Modifier.padding(top = 4.dp)) }
            items(manifest.type) { capability -> Text(when (capability) {
                "metadata_provider" -> "Music catalogue, search and collections"
                "download_provider" -> "Audio streams and downloads"
                "lyrics_provider" -> "Lyrics"
                else -> capability
            }) }
            if (manifest.qualityOptions.isNotEmpty()) {
                item { Text("Available quality choices", style = heading, color = look.ink, modifier = Modifier.padding(top = 4.dp)) }
                items(manifest.qualityOptions) { quality -> Column {
                    Text(quality.label.ifBlank { quality.id })
                    if (quality.description.isNotBlank()) Text(quality.description, style = small, color = look.dim)
                } }
                item { Text("These are provider offers. Actual format and quality are reported by the playing decoder.", style = small, color = look.dim) }
            }
            item { Text("Permissions", style = heading, color = look.ink, modifier = Modifier.padding(top = 4.dp)) }
            item { Text("Artwork downloads: ${if (installed.allowArtwork) "allowed" else "off"} · Local network: ${if (installed.allowLocalNetwork) "allowed" else "off"}") }
            item { Text(manifest.permissions.network.joinToString("\n").ifBlank { "No network domains" }, style = small, color = look.dim) }
            item { Text("Service status", style = heading, color = look.ink, modifier = Modifier.padding(top = 4.dp)) }
            item { if (report != null) ProviderServiceReportView(report)
                else Text(if (manifest.serviceHealth.isEmpty()) "This package declares no health checks." else "Not checked. Use Check services on the provider card.", color = look.dim) }
            item { Text("Package fingerprint", style = heading, color = look.ink, modifier = Modifier.padding(top = 4.dp)) }
            item { SelectionContainer { Text(installed.sha256, style = fonts.label.copy(letterSpacing = 0.sp), color = look.dim) } }
        }
    },
        confirmLabel = "Done",
        onConfirm = onDismiss,
    )
}
