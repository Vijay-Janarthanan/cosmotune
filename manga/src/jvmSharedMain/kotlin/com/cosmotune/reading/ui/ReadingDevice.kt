package com.cosmotune.reading.ui

import androidx.compose.runtime.Composable

/** The device's battery charge (0 to 100) for the reader's foot line, checked now and then; null where there is no battery to read. */
@Composable internal expect fun rememberReadingBattery(enabled: Boolean): Int?

/** A charge from a battery tool's words ("Now drawing from 'Battery Power' -InternalBattery-0 (id=1) 81%; discharging"), or null. */
internal fun readingBatteryFrom(text: String?): Int? =
    text?.let { Regex("""(\d{1,3})\s*%""").find(it)?.groupValues?.get(1) ?: it.trim().takeIf { t -> t.matches(Regex("""\d{1,3}""")) } }
        ?.toIntOrNull()?.takeIf { it in 0..100 }

/** Read's links with the rest of the device's app: on a phone, the home-screen widget and a book it asks to open; nothing elsewhere. */
@Composable internal expect fun ReadingOutsideHooks(scope: ReadScope)
