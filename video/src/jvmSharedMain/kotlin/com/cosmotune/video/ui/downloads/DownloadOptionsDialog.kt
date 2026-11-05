package com.cosmotune.video.ui.downloads

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cosmotune.ui.components.trackFocusModality
import com.cosmotune.ui.sheets.CosmoDialogCard
import com.cosmotune.ui.theme.CosmoSpacing

/** The desktop dialog's width (spec 5.16: `CosmoDialogCard`, 440 dp wide); in a narrow window, the window less the page margins. */
internal val DIALOG_WIDTH = 440.dp

/** The dialog is at most this share of the window high, as a sheet is (part two's rule); the options scroll inside it. */
private const val DIALOG_MAX_HEIGHT_SHARE = 0.85f

/** The dialog has no grabber: its content starts this far below the card's top edge. */
private val DIALOG_TOP = 16.dp

/**
 * The desktop's download dialog (spec 5.16): the sheet's content ([DownloadOptionsBody]) in part two's dialog card (the 40 % scrim and
 * the solid glass, no backdrop blur: `hazeState = null`), 440 dp wide, over the whole window in a focusable popup. The keyboard reaches
 * everything: the arrows move between the rows, Space chooses the focused one, Enter downloads, Escape closes (or leaves the language
 * list); the rows draw part two's focus ring. [onConfirm] is "Download"; [onDismiss] closes it (Escape, the scrim).
 */
@Composable
internal fun DownloadOptionsDialog(state: DownloadSheetState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Popup(
        popupPositionProvider = DialogOrigin,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        DownloadOptionsCard(state, onConfirm, onDismiss)
    }
}

/** The dialog's card without its window (the renders draw it in place); [focus] false leaves the focus alone (a render has no keyboard). */
@Composable
internal fun DownloadOptionsCard(state: DownloadSheetState, onConfirm: () -> Unit, onDismiss: () -> Unit, focus: Boolean = true) {
    var choosingLanguage by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val firstFocus = remember { FocusRequester() }
    val options = state.options
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardWidth = minOf(DIALOG_WIDTH, maxWidth - CosmoSpacing.margin * 2)
        val maxCardHeight = maxHeight * DIALOG_MAX_HEIGHT_SHARE
        CosmoDialogCard(onDismiss = onDismiss, maxWidth = cardWidth, hazeState = null) {
            Column(
                Modifier
                    // the popup is a window of its own: the rows' rings follow its keys
                    .trackFocusModality()
                    .onPreviewKeyEvent { event ->
                        dialogKey(event, focusManager, choosingLanguage, canDownload = state.canDownload, onConfirm = onConfirm, onBack = { choosingLanguage = false }, onDismiss = onDismiss)
                    }
                    .fillMaxWidth()
                    .heightIn(max = maxCardHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(top = DIALOG_TOP),
            ) {
                if (choosingLanguage && options != null) {
                    SubtitleLanguageListBody(
                        languages = options.subtitleLanguages,
                        selected = state.language,
                        onPick = {
                            state.language = it
                            choosingLanguage = false
                        },
                        onBack = { choosingLanguage = false },
                        firstFocus = if (focus) firstFocus else null,
                    )
                } else {
                    DownloadOptionsBody(state, onLanguage = { choosingLanguage = true }, onConfirm = onConfirm, firstFocus = if (focus) firstFocus else null)
                }
            }
        }
    }
    // Once the rows are there (and whenever the list and the options change places), the chosen row takes the focus, so the
    // arrows start from it. The ring shows only once a key is pressed.
    if (focus) {
        LaunchedEffect(options != null, choosingLanguage) {
            if (options != null) runCatching { firstFocus.requestFocus() }
        }
    }
}

/**
 * The dialog's keys (spec 5.16): Up and Down move the focus between the rows; Enter downloads (in the language list it is left to the
 * focused row, which chooses it); Escape closes, or goes back from the language list. Space is the rows' own.
 */
internal fun dialogKey(
    event: KeyEvent,
    focusManager: FocusManager,
    choosingLanguage: Boolean,
    canDownload: Boolean,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
        Key.Escape -> {
            if (choosingLanguage) onBack() else onDismiss()
            true
        }
        Key.Enter, Key.NumPadEnter -> {
            if (choosingLanguage) return false
            if (canDownload) onConfirm()
            true
        }
        else -> false
    }
}

/** The popup stands at the window's top left corner; the card's scrim fills the window from there. */
private object DialogOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset.Zero
}
