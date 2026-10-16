package com.cosmotune.ui

import androidx.compose.ui.input.key.Key
import com.cosmotune.design.InputModality
import com.cosmotune.ui.components.FocusModality
import com.cosmotune.ui.components.isFocusNavigationKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The focus ring shows for keyboard focus only (task P4-22): a clicked control gets none. The rule is [InputModality] (tested in
 * `shared`); this pins what the app does with it: which keys count, that the ring asks the rule while drawing, and which roots keep
 * the answer up to date.
 */
class FocusRingModalityTest {
    private val ui = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui"

    private fun code(path: String) = SourceContract.code(SourceContract.read(path)).replace(Regex("\\s+"), " ")

    @Test fun tabTheArrowsSpaceAndEnterAreKeyboardNavigation() {
        for (key in listOf(Key.Tab, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Spacebar, Key.Enter, Key.NumPadEnter)) {
            assertTrue(key.isFocusNavigationKey(), "$key")
        }
        for (key in listOf(Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown, Key.DirectionCenter)) assertTrue(key.isFocusNavigationKey(), "$key pages or acts on a list")
    }

    @Test fun lettersModifiersMediaKeysAndEscapeAreNot() {
        for (key in listOf(Key.A, Key.Zero, Key.ShiftLeft, Key.CtrlLeft, Key.AltLeft, Key.MetaLeft, Key.CapsLock, Key.MediaPlayPause, Key.MediaNext, Key.Escape, Key.F5)) {
            assertFalse(key.isFocusNavigationKey(), "$key")
        }
    }

    @Test fun theAppsOneModalityFollowsTheStateMachine() {
        val modality = FocusModality()
        assertFalse(modality.keyboard, "nothing done yet: no ring")
        modality.on(InputModality.Event.NAVIGATION_KEY)
        assertTrue(modality.keyboard)
        modality.on(InputModality.Event.OTHER_KEY)
        assertTrue(modality.keyboard, "a letter does not take it away")
        modality.on(InputModality.Event.POINTER_DOWN)
        assertFalse(modality.keyboard, "a click does")
    }

    @Test fun theRingAsksTheRuleWhileDrawingSoAClickedControlHasNone() {
        val ring = SourceContract.code(SourceContract.functionBody(SourceContract.read("$ui/components/FocusRing.kt"), "cosmoFocusRing")).replace(Regex("\\s+"), " ")
        assertTrue("val modality = LocalFocusModality.current" in ring)
        assertTrue("return drawWithContent { drawContent() if (InputModality.ringShown(focused.value, modality.keyboard)) {" in ring, "read in the draw block, next to the focus")
        assertFalse("if (focused.value) {" in ring, "the old rule, which drew the ring around every focused control, is gone")
    }

    @Test fun trackingConsumesNothingAndCountsPressesAndKeysOfNavigation() {
        val tracking = SourceContract.code(SourceContract.functionBody(SourceContract.read("$ui/components/FocusRing.kt"), "trackFocusModality")).replace(Regex("\\s+"), " ")
        assertTrue("onPreviewKeyEvent { event -> if ((event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp) && event.key.isFocusNavigationKey()) { modality.on(InputModality.Event.NAVIGATION_KEY) } false }" in tracking, "keys go on to everyone")
        assertTrue("awaitPointerEvent(PointerEventPass.Initial)" in tracking && "event.type == PointerEventType.Press" in tracking && "modality.on(InputModality.Event.POINTER_DOWN)" in tracking, "a press of a mouse or a finger, seen first")
        assertFalse("consume" in tracking, "nothing is consumed")
    }

    @Test fun theAppRootAndEveryWindowOfItsOwnKeepTheAnswerUpToDate() {
        val theme = SourceContract.code(SourceContract.functionBody(SourceContract.read("$ui/theme/CosmoLookTheme.kt"), "CosmoLookTheme")).replace(Regex("\\s+"), " ")
        assertTrue("FocusModalityRoot(enabled = newLook) { CompositionLocalProvider(" in theme, "the app's content, with the switch on")
        assertTrue("content = content, ) }" in theme, "and the content itself is passed once, as before")
        val root = code("$ui/components/FocusRing.kt")
        assertTrue("fun FocusModalityRoot(enabled: Boolean, content: @Composable () -> Unit) { Box(if (enabled) Modifier.trackFocusModality() else Modifier, propagateMinConstraints = true) { content() } }" in root, "a box that changes no constraint")
        assertTrue(".trackFocusModality()" in code("$ui/chrome/CosmoProfileDrawer.kt"), "the drawer's window")
        assertTrue("modifier .trackFocusModality() .fillMaxWidth()" in code("$ui/sheets/CosmoSheetSurface.kt"), "a sheet's window")
        assertTrue("Box(Modifier.trackFocusModality().fillMaxSize().cosmoAmbientGround()" in code("$ui/settings/LookPreview.kt"), "the look preview's window")
    }
}
