/*
 * Source: FORK.md companion source record TV-0011
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.ui.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.music.ui.tv.theme.TvGlass
import com.cosmotune.music.ui.tv.theme.TvThemeColors
import com.cosmotune.music.ui.tv.theme.TvType

// ─────────────────────────────────────────────────────────────────────────────
// Buttons
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A button: an Orbit pill at ink 8 % (a touch brighter when [isPrimary]) that
 * turns to solid ink, ground text on it, with the peach ring when focused.
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isPrimary: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val content = when {
        !enabled -> TvGlass.TextTertiary
        focused -> TvGlass.OnPlatter
        destructive -> TvGlass.Destructive
        else -> TvGlass.TextPrimary
    }
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .tvLift(interaction, CircleShape, focusedScale = 1.03f, ringGap = 3.dp)
            .tvPlatter(
                interaction,
                resting = when {
                    !enabled -> TvGlass.Row
                    isPrimary -> TvGlass.FillStrong
                    else -> TvGlass.Fill
                },
            )
            .tvClick(interaction, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, style = TvType.Body.copy(fontWeight = FontWeight.W700), color = content, maxLines = 1)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// States
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TvErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Couldn't Load", style = TvType.Title.copy(fontSize = 19.sp), color = TvGlass.TextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = TvType.Callout,
            color = TvGlass.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 416.dp),
        )
        Spacer(modifier = Modifier.height(19.dp))
        TvButton(text = "Try Again", onClick = onRetry)
    }
}

@Composable
fun TvEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = TvType.Title.copy(fontSize = 19.sp),
            color = TvGlass.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = TvType.Callout,
            color = TvGlass.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 416.dp),
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(19.dp))
            action()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Alerts
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A dialog in Orbit for the sofa: the app dims away and the dialog is a flat Orbit card on the dimmed ground (radius 32, the
 * ground lifted by a little ink), the title in the wide face with the peach full stop's colour kept for the page headings, an
 * optional message, then its controls, each with the TV's peach focus ring outside it. Back dismisses it.
 */
@Composable
fun TvDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    width: Dp = 448.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(220),
        label = "tvDialogIn",
    )
    val ground = TvThemeColors.current.ground

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress }
                .background(ground.copy(alpha = 0.86f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .width(width + DialogPadding * 2)
                    .graphicsLayer {
                        val s = 0.96f + 0.04f * progress
                        scaleX = s
                        scaleY = s
                    }
                    .clip(RoundedCornerShape(32.dp))
                    .background(TvGlass.Ink.copy(alpha = 0.07f).compositeOver(ground))
                    .padding(horizontal = DialogPadding, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = TvType.Title.copy(fontSize = 24.sp, lineHeight = 28.sp),
                    color = TvGlass.TextPrimary,
                    textAlign = TextAlign.Center,
                )
                if (!message.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = TvType.Callout,
                        color = TvGlass.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                content()
            }
        }
    }
}

/** Room inside a dialog's card round its controls, wider than their focus ring. */
private val DialogPadding = 30.dp

/** A full-width alert button, stacked one per row as tvOS alerts do. */
@Composable
fun TvDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
) {
    TvButton(
        text = text,
        onClick = onClick,
        destructive = destructive,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            // Stacked buttons keep their focus rings apart.
            .padding(vertical = 4.dp)
            .tvInitialFocus(initialFocus),
    )
}

/** A rounded group of list rows inside an alert — menus, pickers, settings sheets. */
@Composable
fun TvDialogList(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(13.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        // Room between the rows for the focus ring.
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
