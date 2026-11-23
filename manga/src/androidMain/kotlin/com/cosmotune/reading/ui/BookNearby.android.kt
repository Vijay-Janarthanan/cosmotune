package com.cosmotune.reading.ui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

internal actual fun readingDeviceModel(): Pair<String, String> = Build.MODEL.orEmpty().ifBlank { "Android" } to "mobile"

@Composable internal actual fun NearbyMulticastLock(active: Boolean) {
    val context = LocalContext.current.applicationContext
    DisposableEffect(active) {
        val lock = if (!active) null else runCatching {
            (context.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("cosmotune-read-nearby").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        onDispose { runCatching { lock?.release() } }
    }
}
