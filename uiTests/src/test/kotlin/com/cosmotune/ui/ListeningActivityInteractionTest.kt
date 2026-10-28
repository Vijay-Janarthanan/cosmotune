package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.listening.ListeningActivityPanel
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class ListeningActivityInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun aDayAndTimeOfDayOpenTheirOwnRecordedTotals() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ListeningActivityPanel(mapOf("2026-10-27" to 120_000L), List(24) { if (it == 9) 180_000L else 0L },
                        today = LocalDate.parse("2026-10-28"))
                }
            }
        }
        compose.onNodeWithTag("activity:day:2026-10-27").performClick()
        compose.onNodeWithTag("activity:selection").assertTextContains("2 minutes", substring = true)
        compose.onNodeWithTag("activity:hour:3").performClick()
        compose.onNodeWithTag("activity:hour:selection").assertTextContains("3 minutes", substring = true)
        compose.onNodeWithTag("activity:day:2026-10-28").performClick()
        compose.onNodeWithTag("activity:selection").assertTextContains("No listening recorded", substring = true)
    }
}
