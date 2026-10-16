package com.cosmotune.video.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.sheets.CosmoDialogCard
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_create
import com.cosmotune.video.resources.video_new_playlist
import com.cosmotune.video.resources.video_playlist_name
import com.music.bitchord.sharedui.resources.cancel
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.sharedui.resources.Res as SharedRes

/**
 * "New playlist" (spec 5.12, from the save-to-playlist sheet): part two's dialog card (layer C: the 40 % scrim, the glass card) over
 * the whole window, with the title, a name field (focused, so the keyboard comes up at once) and Cancel and Create. [onCreate]
 * gets the name, trimmed (Create waits for one that is not blank; Enter or the keyboard's Done also create); [onDismiss] closes it
 * (Cancel, the scrim, Back or Escape).
 */
@Composable
fun NewPlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        NewPlaylistCard(onCreate, onDismiss)
    }
}

/** The dialog's card without its window (the renders draw it in place). */
@Composable
internal fun NewPlaylistCard(onCreate: (String) -> Unit, onDismiss: () -> Unit, focus: Boolean = true) {
    var name by rememberSaveable { mutableStateOf("") }
    val create = { if (name.isNotBlank()) onCreate(name.trim()) }
    val title = stringResource(Res.string.video_new_playlist)
    // The card fills the width it is given up to 440 dp: on a phone it keeps the page margin on both sides.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardWidth = minOf(DIALOG_MAX_WIDTH, maxWidth - CosmoSpacing.margin * 2)
        CosmoDialogCard(onDismiss = onDismiss, maxWidth = cardWidth) {
            NewPlaylistContent(title, name, onName = { name = it }, create, onDismiss, focus)
        }
    }
}

@Composable
private fun NewPlaylistContent(title: String, name: String, onName: (String) -> Unit, create: () -> Unit, onDismiss: () -> Unit, focus: Boolean) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column {
        Text(
            text = title,
            style = type.forText(type.title(TypeScale.TITLE_S), title),
            color = Color(palette.textPrimary),
            modifier = Modifier.padding(start = PADDING, end = PADDING, top = PADDING, bottom = TITLE_GAP).semantics { heading() },
        )
        NameField(name = name, onName = onName, onDone = create, focus = focus)
        Row(
            Modifier.fillMaxWidth().padding(start = PADDING, end = BUTTONS_END, top = FIELD_GAP, bottom = BUTTONS_BOTTOM),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosmoButton(stringResource(SharedRes.string.cancel), onDismiss, kind = CosmoButtonKind.TEXT, size = CosmoButtonSize.SMALL, floating = true)
            Spacer(Modifier.width(BUTTON_GAP))
            CosmoButton(
                stringResource(Res.string.video_create),
                create,
                kind = CosmoButtonKind.PRIMARY,
                size = CosmoButtonSize.SMALL,
                enabled = name.isNotBlank(),
                floating = true,
            )
        }
    }
}

/** The name field: the ink on a quiet fill of the cover's surface, a superellipse of 16, 52 dp at least, the hint while empty. */
@Composable
private fun NameField(name: String, onName: (String) -> Unit, onDone: () -> Unit, focus: Boolean) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val hint = stringResource(Res.string.video_playlist_name)
    val style = remember(type) { type.body(TypeScale.BODY).copy(color = Color(palette.textPrimary)) }
    val requester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Box(
        Modifier
            .padding(horizontal = PADDING)
            .fillMaxWidth()
            .heightIn(min = FIELD_MIN_HEIGHT)
            .clip(FIELD_SHAPE)
            // read while drawing, so the fill follows a palette fade
            .drawBehind { drawRect(colors.live(CoverField.SURFACE_VARIANT)) }
            .padding(horizontal = FIELD_PADDING_H, vertical = FIELD_PADDING_V),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (name.isEmpty()) Text(text = hint, style = style, color = Color(palette.textTertiary))
        BasicTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            textStyle = style.copy(color = Color(palette.textPrimary)),
            cursorBrush = SolidColor(Color(palette.accent)),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .semantics { contentDescription = hint }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        onDone()
                        true
                    } else {
                        false
                    }
                },
        )
    }
}

/** The popup stands at the window's top left corner; the card's scrim fills the window from there. */
private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset.Zero
}

/** The desktop dialog's width (spec 5.16: 440 dp), and on the phone the card is the window less its margins. */
private val DIALOG_MAX_WIDTH = 440.dp
private val PADDING = 24.dp
private val TITLE_GAP = 16.dp
private val FIELD_GAP = 20.dp
private val BUTTONS_END = 16.dp
private val BUTTONS_BOTTOM = 12.dp
private val BUTTON_GAP = 8.dp
private val FIELD_MIN_HEIGHT = 52.dp
private val FIELD_PADDING_H = 16.dp
private val FIELD_PADDING_V = 14.dp
private val FIELD_SHAPE = SuperellipseShape(16.dp)
