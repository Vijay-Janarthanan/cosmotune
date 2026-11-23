package com.cosmotune.reading.ui

import androidx.compose.runtime.Composable

internal actual fun readingDeviceModel(): Pair<String, String> =
    (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: System.getProperty("os.name") ?: "Computer").take(40) to "desktop"

/** A computer hears multicast without asking. */
@Composable internal actual fun NearbyMulticastLock(active: Boolean) = Unit
