package com.cosmotune.video.ui.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_menu
import com.cosmotune.video.resources.video_search_hint
import com.cosmotune.video.resources.video_account
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.video.ui.shell.AccountSheet
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.jetbrains.compose.resources.stringResource

/**
 * The header of Video's page on the desktop (the video apps' top row): the menu button that folds the guide to its icon rail, a
 * rounded search field in the middle (at most 640 dp wide, "Ctrl K" outside macOS) and the account's picture at the end, which
 * opens the account sheet. The field's text is [DesktopVideoState.query], the one the search page follows; Enter records the search
 * and pushes the search page, typing on another page opens the suggestions ([openSidebarSuggestions]). It takes the focus when the
 * search shortcut or the host asks ([DesktopVideoState.focusSearch]) and again when the window comes back to the front.
 */
@Composable
internal fun VideoDesktopHeader(modifier: Modifier = Modifier) {
    val navigator = LocalVideoNavigator.current
    val graph = LocalVideoGraph.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val asked = DesktopVideoState.focusSearch
    LaunchedEffect(asked) {
        if (asked) {
            runCatching { focusRequester.requestFocus() }
            DesktopVideoState.focusSearch = false
        }
    }
    val windowInfo = LocalWindowInfo.current
    var searchFocused by remember { mutableStateOf(false) }
    var restoreSearchFocus by remember { mutableStateOf(false) }
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused) {
            restoreSearchFocus = searchFocused
        } else if (restoreSearchFocus) {
            // Let the desktop window finish its activation before asking Compose to put the native text input target back in focus.
            yield()
            runCatching { focusRequester.requestFocus() }
            restoreSearchFocus = false
        }
    }

    val account = graph.account
    val signedIn = account.state.collectAsState().value as? AccountState.SignedIn
    var sheet by remember { mutableStateOf(false) }

    Row(modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = HEADER_SIDE), verticalAlignment = Alignment.CenterVertically) {
        if (DesktopVideoState.sidebarCanCollapse) {
            CosmoIconButton(
                icon = VideoIcons.Menu,
                contentDescription = stringResource(Res.string.video_menu),
                onClick = { DesktopVideoState.sidebarCollapsed = !DesktopVideoState.sidebarCollapsed },
                style = CosmoIconButtonStyle.PLAIN,
                size = BUTTON,
            )
        } else {
            Box(Modifier.size(BUTTON))
        }
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            CosmoSearchField(
                query = DesktopVideoState.query,
                onQueryChange = { DesktopVideoState.query = it; openSidebarSuggestions(navigator, it) },
                onSubmit = { submitSidebarSearch(navigator, DesktopVideoState.query) },
                placeholder = stringResource(Res.string.video_search_hint),
                modifier = Modifier.widthIn(max = FIELD_MAX).fillMaxWidth(),
                focusRequester = focusRequester,
                height = FIELD_HEIGHT,
                keyHint = if (IS_MAC) null else "Ctrl K",
                onFocusChanged = { searchFocused = it },
            )
        }
        Box(
            Modifier
                .size(BUTTON)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.video_account)) { sheet = true },
            contentAlignment = Alignment.Center,
        ) {
            ChannelAvatar(signedIn?.avatarUrl, AVATAR, name = signedIn?.displayName)
        }
    }
    if (sheet) {
        AccountSheet(
            state = account.state.value,
            onSignIn = { scope.launch { account.signInRequest() } },
            onSwitch = { id -> scope.launch { account.switchAccount(id) } },
            onSignOut = { scope.launch { account.signOut() } },
            onDismiss = { sheet = false },
        )
    }
}

private val HEADER_HEIGHT = 56.dp
private val HEADER_SIDE = 16.dp
private val BUTTON = 40.dp
private val AVATAR = 32.dp
private val FIELD_HEIGHT = 40.dp
private val FIELD_MAX = 640.dp
private val IS_MAC = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)
