package com.cosmotune.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitTextField
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Orbit field submits on the key the keyboard shows, whichever it is: the rooms' "Ask to play" asks for Search, and its Search
 * key did nothing once the field became an Orbit field (only Done was wired), so a song could be searched for only with the
 * round button beside it.
 */
class OrbitTextFieldInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val fonts = OrbitFonts(FontFamily.Default, FontFamily.Default, FontFamily.Default)
    private val look = OrbitLook(true, Color.White, Color.Gray, Color.DarkGray, Color.DarkGray, Color(0xFFFFB98F), Color.Black)

    private fun submitsWith(action: ImeAction?): Int {
        var submitted = 0
        compose.setContent {
            OrbitTextField(
                value = "levitating", onValueChange = {}, placeholder = "Search for a song", fonts = fonts, look = look,
                modifier = Modifier.testTag("field"), onSubmit = { submitted++ }, imeAction = action,
            )
        }
        compose.onNodeWithTag("field").performImeAction()
        compose.waitForIdle()
        return submitted
    }

    @Test fun theSearchKeySubmits() = assertEquals(1, submitsWith(ImeAction.Search))

    @Test fun theDoneKeyStillSubmits() = assertEquals(1, submitsWith(null))

    @Test fun theGoKeySubmits() = assertEquals(1, submitsWith(ImeAction.Go))
}
