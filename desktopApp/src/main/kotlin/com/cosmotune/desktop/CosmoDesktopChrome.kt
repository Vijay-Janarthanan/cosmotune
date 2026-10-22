package com.cosmotune.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cosmotune.desktopapp.generated.resources.Res
import cosmotune.desktopapp.generated.resources.logo_mark
import com.cosmotune.design.ShellMetrics
import com.cosmotune.ui.chrome.CosmoDrawerEntry
import com.cosmotune.ui.chrome.CosmoProfileButton
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.music.desktop.DesktopPlatform
import com.cosmotune.music.desktop.DesktopStrings
import com.cosmotune.music.desktop.DesktopTitleBarDragArea
import com.cosmotune.music.desktop.DesktopTitleBarSetting
import com.cosmotune.music.desktop.DesktopWindowButtons
import org.jetbrains.compose.resources.painterResource

/**
 * The desktop's top bar in the new look (`V2.desktop`, `.d2-top`): 56 dp high and see-through, so the ambient ground shows
 * behind it; the old bar's transport, now-playing cell and volume are in the floating player bar. From the left: the logo
 * mark (28 x 18 dp, untinted: apricot on the dark desktop), the one way back (a 40 dp tonal button), then the profile button
 * at the right edge: the account's picture, which opens the profile drawer with its mode switch and its [entries]. The drawer's
 * head shows [accountName] (a blank one reads "Accounts"), and a tap on it calls [onOpenAccounts], the account switcher.
 *
 * The strip moves the window and maximises it on a double click, like the old bar. A [compact] window has no sidebar to carry the
 * window's own buttons, so, when the application draws its own window frame and there is no separate title bar, they lead
 * this bar instead (the rule of `DesktopTopBar`).
 *
 * While the mode is Video ([com.cosmotune.video.host.desktopVideoShown]) the bar is the same, but the back button follows Video's
 * pages (`VideoDesktopBack`) instead of [canGoBack] and [onBack], and the drawer lists Video's entries instead of [entries].
 */
@Composable
internal fun CosmoDesktopTopBar(
    compact: Boolean,
    canGoBack: Boolean,
    onBack: () -> Unit,
    accountAvatar: String?,
    onOpenAccounts: () -> Unit,
    accountName: String? = null,
    entries: List<CosmoDrawerEntry> = emptyList(),
) {
    val titleBarEnabled by DesktopTitleBarSetting.enabled.collectAsState()
    val inlineCaption = compact && DesktopPlatform.drawsOwnWindowFrame && !titleBarEnabled
    val video = com.cosmotune.video.host.desktopVideoShown()
    val backEnabled = if (video) com.cosmotune.video.ui.desktop.VideoDesktopBack.canGoBack else canGoBack
    val back: () -> Unit = if (video) ({ com.cosmotune.video.ui.desktop.VideoDesktopBack.goBack() }) else onBack
    DesktopTitleBarDragArea(Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (inlineCaption) {
                Box(Modifier.fillMaxHeight().padding(start = if (DesktopPlatform.isMac) 16.dp else 10.dp), contentAlignment = Alignment.CenterStart) {
                    DesktopWindowButtons()
                }
            }
            Row(
                Modifier.weight(1f).fillMaxHeight().padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(Res.drawable.logo_mark),
                    contentDescription = "Cosmotune",
                    modifier = Modifier.size(width = 28.dp, height = 18.dp),
                )
                Spacer(Modifier.width(14.dp))
                CosmoIconButton(
                    CosmoIcons.ChevronLeft, DesktopStrings["back", "Back"], back,
                    style = CosmoIconButtonStyle.TONAL, size = 40.dp, enabled = backEnabled,
                )
                Spacer(Modifier.weight(1f))
                CosmoProfileButton(
                    avatarUrl = accountAvatar,
                    name = accountName?.takeIf { it.isNotBlank() } ?: DesktopStrings["accounts", "Accounts"],
                    subtitle = null,
                    entries = if (video) com.cosmotune.video.ui.desktop.videoDesktopDrawerEntries() else entries,
                    onAccount = onOpenAccounts,
                    size = AVATAR_SIZE,
                )
            }
        }
    }
}

/**
 * Where the floating player bar goes. [content] gets the modifier that places it: it sits 14 dp above the bottom of the window,
 * clear of the sidebar (220 dp and a 16 dp margin) and, while [panelOpen] (the lyrics or queue column, 360 dp), of the column
 * (and a 16 dp margin); the margin follows the column as it slides, over the same 260 ms. In a window narrower than 980 dp
 * (compact) the sidebar is gone and the phone's chrome is the bottom bar, so this draws nothing. It draws nothing either while the
 * mode is Video ([com.cosmotune.video.host.desktopVideoShown]): the music player bar stands aside there.
 */
@Composable
internal fun CosmoDesktopPlayerBarHost(panelOpen: Boolean, content: @Composable (Modifier) -> Unit) {
    if (com.cosmotune.video.host.desktopVideoShown()) return
    val reduced = LocalReducedMotion.current
    val end by animateDpAsState(
        if (panelOpen) (ShellMetrics.DESKTOP_SIDE_PANEL_DP + SIDE_MARGIN).dp else SIDE_MARGIN.dp,
        animationSpec = if (reduced) snap() else tween(PANEL_SLIDE_MS, easing = FastOutSlowInEasing),
        label = "player-bar-end",
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < ShellMetrics.DESKTOP_COMPACT_DP.dp) return@BoxWithConstraints
        content(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = (ShellMetrics.DESKTOP_SIDEBAR_DP + SIDE_MARGIN).dp, end = end, bottom = ShellMetrics.DESKTOP_BAR_MARGIN_DP.dp),
        )
    }
}

private val TOP_BAR_HEIGHT: Dp = 56.dp
private val AVATAR_SIZE: Dp = 36.dp
private const val SIDE_MARGIN = 16f
private const val PANEL_SLIDE_MS = 260   // DesktopSidePanelColumn's own SLIDE_MS
