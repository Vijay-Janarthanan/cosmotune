package com.cosmotune.ui.components

import android.app.ActivityManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

internal actual val platformSdkInt: Int? = Build.VERSION.SDK_INT

@Composable
internal actual fun rememberLowRamDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }
}
