package com.cosmotune.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.ink
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.clear_search
import com.music.bitchord.sharedui.resources.search
import org.jetbrains.compose.resources.stringResource

/**
 * The search field of the B+C look (`.field2`; the desktop's `.d2-search` is 44 dp): a pill of [height] (52 dp on the phone), `textPrimary`
 * at 9 % with a 1 dp ring of `outline` that turns `accentStrong` and thicker while the field has focus, a magnifier that
 * submits (it works once something is typed), the text, a clear button while there is text, and the [keyHint] (a key cap such
 * as "/") while there is not.
 *
 * It behaves as the old [com.music.bitchord.ui.components.SearchField] did: the keyboard's search key, Enter on the desktop and
 * the magnifier all call [onSubmit], then drop the focus and hide the keyboard; clearing the text keeps the field focused so the
 * next search can be typed at once. [onFocusChanged] hears when the field gains or loses focus (the desktop shows its results
 * panel while it has it).
 */
@Composable
fun CosmoSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    height: Dp = 52.dp,
    keyHint: String? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState()
    // Both ways of saying "search this" do the same things, so they are written once.
    val submit: () -> Unit = {
        onSubmit()
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val shape = remember(height) { RoundedCornerShape(height / 2) }
    val textStyle = type.forText(
        TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 15.5.sp * 1.3f),
        query.ifEmpty { placeholder },
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .drawBehind {
                drawRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = FIELD_FILL))
                // The ring runs inside the edge: a stroke twice as thick, half of it outside the clip.
                val width = (if (focused.value) FOCUS_RING_DP else RING_DP).dp.toPx()
                val ring = if (focused.value) colors.live(CoverField.ACCENT_STRONG) else colors.ink(ControlMetrics.OUTLINE)
                val radius = size.height / 2f
                drawRoundRect(ring, Offset.Zero, Size(size.width, size.height), CornerRadius(radius), style = Stroke(width * 2f))
            }
            // The magnifier and the clear button each take 48 dp, of which 14 are the gutter's: the glyphs end up 16 dp in.
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = query.isNotBlank(), role = Role.Button, onClick = submit),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CosmoIcons.Search, contentDescription = stringResource(Res.string.search), tint = Color(palette.textSecondary), modifier = Modifier.size(20.dp))
        }
        // The hint and the text are one thing to a screen reader: the field, named by what it is for.
        Box(Modifier.weight(1f).semantics(mergeDescendants = true) {}, contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = textStyle,
                    color = Color(palette.textSecondary),
                    // A narrow field (the phone's, beside the account photo) cuts the hint short, not out of the pill.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = textStyle.copy(color = Color(palette.textPrimary)),
                cursorBrush = SolidColor(Color(palette.accent)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state -> onFocusChanged?.invoke(state.isFocused) },
            )
        }
        if (query.isNotEmpty()) {
            CosmoIconButton(
                icon = CosmoIcons.Close,
                contentDescription = stringResource(Res.string.clear_search),
                onClick = {
                    onQueryChange("")
                    // Clearing is an edit, not a dismissal: leave the field ready for the next search.
                    focusRequester.requestFocus()
                    keyboard?.show()
                },
                style = CosmoIconButtonStyle.PLAIN,
                size = 40.dp,
            )
        } else if (keyHint != null) {
            KeyCap(keyHint, Modifier.padding(start = 8.dp, end = 14.dp))
        }
    }
}

/** `.field2 kbd`: the shortcut that focuses the field, 11.5 sp, weight 600, in a small cap (padding 3 x 7, radius 7, `textPrimary` at 10 %). */
@Composable
private fun KeyCap(text: String, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    val type = LocalCosmoType.current
    Box(
        modifier
            // Decoration: the shortcut is the app's business, a screen reader has no use for the cap.
            .clearAndSetSemantics { }
            .clip(RoundedCornerShape(7.dp))
            .drawBehind { drawRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = KEY_CAP_FILL)) }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight(600), letterSpacing = 0.sp),
            color = Color(colors.palette.textSecondary),
            maxLines = 1,
        )
    }
}

private const val FIELD_FILL = 0.09f
private const val KEY_CAP_FILL = 0.10f
private const val RING_DP = 1f
private const val FOCUS_RING_DP = 2f
