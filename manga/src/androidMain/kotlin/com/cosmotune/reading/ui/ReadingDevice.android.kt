package com.cosmotune.reading.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

@Composable internal actual fun rememberReadingBattery(enabled: Boolean): Int? {
    val context = LocalContext.current
    var level by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(enabled) {
        if (!enabled) { level = null; return@LaunchedEffect }
        while (true) {
            // The battery's sticky broadcast answers at once without a receiver kept registered.
            val status = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            val raw = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            level = if (raw >= 0 && scale > 0) raw * 100 / scale else null
            delay(60_000)
        }
    }
    return level
}
