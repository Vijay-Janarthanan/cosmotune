package com.cosmotune.video.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.sheets.CosmoDialogCard
import com.cosmotune.ui.sheets.CosmoSheetHeading
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.sharedui.resources.cancel
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes

/** One row of a [ChoiceSheet]: the [value] it stands for and the [label] it shows. */
@Immutable
internal class ChoiceOption<T>(val value: T, val label: String)

/**
 * The list a settings row opens (spec 5.11: "choices open a sheet of radio rows"): part two's sheet (layer C, through the app's sheet
 * host) with [title] as its heading and one row for each of [options], the one equal to [selected] ticked. A pick calls [onSelect]
 * and closes the sheet through [onDismiss]; the scrim, Back and a drag down close it without a pick.
 *
 * The rows are a lazy list that scrolls inside the sheet past the sheet's height, because the country list has about 250 of them;
 * it opens with the ticked row in view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceSheet(title: String, options: List<ChoiceOption<T>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ChoiceBody(title, options, selected) {
            onSelect(it)
            onDismiss()
        }
    }
}

/** The sheet's content without its host (the renders lay it on the sheet surface directly). */
@Composable
internal fun <T> ColumnScope.ChoiceBody(title: String, options: List<ChoiceOption<T>>, selected: T, onPick: (T) -> Unit) {
    CosmoSheetHeading(title)
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    // The sheet is 85 % of the window at most: what is left of that under its grabber and heading, and over the gap at its end.
    val cap = (windowHeight * SHEET_SHARE - SHEET_CHROME).coerceAtLeast(MIN_LIST_HEIGHT)
    val first = options.indexOfFirst { it.value == selected }
    // A short list shows from its top; a long one (the countries) opens with the pick in view.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = if (first <= SHORT_LIST_ROWS) 0 else first - ROWS_ABOVE_SELECTED)
    LazyColumn(Modifier.heightIn(max = cap).selectableGroup(), state = state) {
        itemsIndexed(options, key = { index, _ -> index }) { _, option ->
            ChoiceRow(option.label, option.value == selected) { onPick(option.value) }
        }
    }
    Spacer(Modifier.height(SHEET_END))
}

/**
 * A radio row: a sheet row (54 dp) whose icon is the check in the cover's accent while it is the pick, or blank so the labels line up
 * (as the player's own lists). It is one radio button for assistive technology, announced selected or not.
 */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = Color(LocalCosmoColors.current.palette.accent)
    Box(Modifier.semantics(mergeDescendants = true) { role = Role.RadioButton; this.selected = selected }) {
        CosmoActionRow(
            icon = if (selected) CosmoIcons.Check else BlankIcon,
            label = label,
            tint = if (selected) accent else null,
            onClick = onClick,
        )
    }
}

/**
 * Asks before something that cannot be undone (clearing the history): part two's dialog card (layer C: the 40 % scrim, the glass card)
 * over the whole window, with [title], the [message], Cancel and the confirming button named [confirmLabel]. [onConfirm] runs on the
 * button only; [onDismiss] closes the dialog (Cancel, the scrim, Back or Escape) and also follows the confirmation.
 */
@Composable
internal fun SettingsConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        SettingsConfirmCard(title, message, confirmLabel, onConfirm, onDismiss)
    }
}

/** The dialog's card without its window (the renders draw it in place). */
@Composable
internal fun SettingsConfirmCard(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // The card fills the width it is given up to 440 dp: on a phone it keeps the page margin on both sides.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardWidth = minOf(DIALOG_MAX_WIDTH, maxWidth - CosmoSpacing.margin * 2)
        CosmoDialogCard(onDismiss = onDismiss, maxWidth = cardWidth) {
            ConfirmContent(title, message, confirmLabel, onConfirm = { onConfirm(); onDismiss() }, onDismiss)
        }
    }
}

@Composable
private fun ConfirmContent(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column {
        Text(
            text = title,
            style = type.forText(type.title(TypeScale.TITLE_S), title),
            color = Color(palette.textPrimary),
            modifier = Modifier.padding(start = PADDING, end = PADDING, top = PADDING, bottom = TITLE_GAP).semantics { heading() },
        )
        Text(
            text = message,
            style = type.forText(type.body(TypeScale.BODY), message),
            color = Color(palette.textSecondary),
            modifier = Modifier.padding(horizontal = PADDING),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = PADDING, end = BUTTONS_END, top = MESSAGE_GAP, bottom = BUTTONS_BOTTOM),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosmoButton(stringResource(SharedRes.string.cancel), onDismiss, kind = CosmoButtonKind.TEXT, size = CosmoButtonSize.SMALL, floating = true)
            Spacer(Modifier.width(BUTTON_GAP))
            CosmoButton(confirmLabel, onConfirm, kind = CosmoButtonKind.PRIMARY, size = CosmoButtonSize.SMALL, floating = true)
        }
    }
}

/** The popup stands at the window's top left corner; the card's scrim fills the window from there. */
private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset.Zero
}

/** The empty icon of a row that is not the pick (the pick shows the check, so the labels line up). */
private val BlankIcon: ImageVector = ImageVector.Builder(
    name = "blank", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).build()

/** The sheet is 85 % of the window high at most (part two's rule); the list gets what is left under the grabber and the heading. */
private const val SHEET_SHARE = 0.85f
private val SHEET_CHROME = 112.dp
private val MIN_LIST_HEIGHT = 216.dp
private val SHEET_END = 8.dp

/** A list that opens on a pick far down shows two rows above it; a pick among the first few rows leaves the list at its top. */
private const val ROWS_ABOVE_SELECTED = 2
private const val SHORT_LIST_ROWS = 5

/** The dialog's width on a wide window (spec 5.16's 440 dp); on a phone the card is the window less its margins. */
private val DIALOG_MAX_WIDTH = 440.dp
private val PADDING = 24.dp
private val TITLE_GAP = 12.dp
private val MESSAGE_GAP = 20.dp
private val BUTTONS_END = 16.dp
private val BUTTONS_BOTTOM = 12.dp
private val BUTTON_GAP = 8.dp
