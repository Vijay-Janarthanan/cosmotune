package com.cosmotune.video.ui.notice

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_disclaimer_ok
import com.cosmotune.video.resources.video_disclaimer_text
import com.cosmotune.video.resources.video_disclaimer_title
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.settings.VideoSettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource

/**
 * The first-run notice's place in a shell (spec 5.14): call it last in the box that holds the page, so it is drawn over everything.
 * It shows [FirstRunNotice] while the stored flag `firstRunSeen` is false and nothing afterwards; "Got it" sets the flag
 * ([acknowledgeFirstRun]), so the notice comes once. The phone shell and the desktop page both call it.
 *
 * Only a change of the flag recomposes it: no other setting does.
 */
@Composable
fun FirstRunNoticeLayer() {
    val settings = LocalVideoGraph.current.settings
    val shown by remember(settings) { settings.settings.map(::firstRunNoticeShown).distinctUntilChanged() }
        .collectAsState(firstRunNoticeShown(settings.settings.value))
    if (shown) FirstRunNotice(onDismiss = { acknowledgeFirstRun(settings) })
}

/** The notice is shown until the person has said "Got it". */
internal fun firstRunNoticeShown(settings: VideoSettings): Boolean = !settings.firstRunSeen

/** "Got it": keeps that the notice was seen, and changes nothing else. */
internal fun acknowledgeFirstRun(settings: VideoSettingsRepository) {
    settings.update { it.copy(firstRunSeen = true) }
}

/**
 * The notice itself (spec 5.14): a layer-C card over the page (a superellipse of 36 dp, solid glass, 20 dp of padding) with the title
 * "Before you watch", the disclaimer and a primary "Got it" button that calls [onDismiss]. It stands on a 40 % black scrim that takes
 * every touch, so the page below waits until the button is pressed; the button has the focus when the notice appears, so Enter does it
 * on the desktop. The card is at most 440 dp wide and keeps the page margin on a phone. Only the button closes it: a touch outside
 * does nothing, and Back or Escape leave the page as they always do.
 */
@Composable
fun FirstRunNotice(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val title = stringResource(Res.string.video_disclaimer_title)
    val text = stringResource(Res.string.video_disclaimer_text)
    val ok = stringResource(Res.string.video_disclaimer_ok)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier
            .fillMaxSize()
            .background(SCRIM)
            // A pointer handler on the front sibling keeps the page behind it from seeing touches, clicks and the wheel.
            .pointerInput(Unit) { detectTapGestures() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = CosmoSpacing.margin)
                .widthIn(max = CARD_MAX_WIDTH)
                .fillMaxWidth()
                .cosmoGlass(hazeState = null, shape = CARD_SHAPE)
                .semantics { paneTitle = title }
                .padding(CosmoSpacing.s5),
        ) {
            Text(
                text = title,
                style = type.forText(type.title(TypeScale.TITLE_S), title),
                color = Color(palette.textPrimary),
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(CosmoSpacing.s3))
            Text(
                text = text,
                style = type.forText(type.body(TypeScale.BODY), text),
                color = Color(palette.textSecondary),
            )
            Spacer(Modifier.height(CosmoSpacing.s5))
            CosmoButton(
                ok,
                onDismiss,
                modifier = Modifier.align(Alignment.End).focusRequester(focus),
                kind = CosmoButtonKind.PRIMARY,
                size = CosmoButtonSize.MEDIUM,
                floating = true,
            )
        }
    }
}

/** `.scrim-dim`: the black behind a dialog, 40 % (the documented exception: the black scrims of sheets and dialogs). */
private val SCRIM = Color.Black.copy(alpha = 0.40f)

/** Spec 5.14: a superellipse of 36 dp, the sheet's corner. */
private val CARD_SHAPE = SuperellipseShape(CosmoRadii.sheet)

/** The width of the desktop's dialogs (spec 5.16: 440 dp); on a phone the card is the window less its margins. */
private val CARD_MAX_WIDTH = 440.dp
