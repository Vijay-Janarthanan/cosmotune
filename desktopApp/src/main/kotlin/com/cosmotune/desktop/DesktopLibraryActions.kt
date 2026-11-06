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
import com.cosmotune.ui.theme.CosmoRadii

@Composable
fun DesktopLibraryActions(item: ShelfItem, pinned: Boolean, onPin: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = item.title,
        confirmLabel = if (pinned) "Unpin from library" else "Pin to library",
        onConfirm = { onPin(); onDismiss() },
        dismissLabel = "Close",
        onDismiss = onDismiss,
    ) {
        com.cosmotune.ui.sheets.CosmoDialogQuietButton("Open", { onOpen(); onDismiss() })
    }
}
