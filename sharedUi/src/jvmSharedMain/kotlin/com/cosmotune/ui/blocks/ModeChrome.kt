package com.cosmotune.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What the desktop sidebar of Podio and Manga selects: the sidebar rows write it and the page reads it, so the two never disagree.
 * Which mode is open is chosen from the profile menu, as in Music and Video, not from a row here.
 */
object ModeNav {
    val podioTab = kotlinx.coroutines.flow.MutableStateFlow("Radio")
    val podioSaved = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** The open station page of Podio on the desktop; the bottom bar sets it when a radio station is clicked. */
    val podioDetail = kotlinx.coroutines.flow.MutableStateFlow(false)
    val mangaTab = kotlinx.coroutines.flow.MutableStateFlow("Library")
}

/** Shared native mode chrome: inherits Music's Material bridge, fonts and artwork palette. Modes are switched from the profile menu. */
@Composable
fun ModePageHeader(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun ModeTabs(tabs: List<String>, selectedTab: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEach { tab ->
            val active = selectedTab == tab
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                .background(if (active) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .semantics { selected = active }.clickable(role = Role.Tab) { onSelect(tab) }.padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text(tab, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
