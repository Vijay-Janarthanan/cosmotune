package com.cosmotune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cosmotune.music.data.model.ShelfItem

@Composable
fun DesktopLibraryActions(item: ShelfItem, pinned: Boolean, onPin: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge)
                Button(onClick = { onPin(); onDismiss() }) { Text(if (pinned) "Unpin from library" else "Pin to library") }
                TextButton(onClick = { onOpen(); onDismiss() }) { Text("Open") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
