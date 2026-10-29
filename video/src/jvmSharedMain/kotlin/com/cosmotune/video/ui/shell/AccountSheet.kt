package com.cosmotune.video.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_sign_in
import com.cosmotune.video.resources.video_sign_out
import com.cosmotune.video.resources.video_signed_out_hint
import com.cosmotune.video.resources.video_signed_out_name
import com.cosmotune.video.resources.video_switch_account
import com.cosmotune.video.ui.blocks.ChannelAvatar
import org.jetbrains.compose.resources.stringResource

/*
 * The account sheet: who is signed in with their picture, the other accounts of the session to switch to, and Sign in or Sign out.
 * It takes what it shows and says what was tapped; the page that opens it does the work (the account's own calls).
 */

private val HEAD_AVATAR = 56.dp

/**
 * The sheet of the avatar button. Signed in: the account's picture, name and handle, a row for each other account ("Switch account"),
 * and Sign out. Signed out: the hint and Sign in. A tap on a row calls its callback and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    state: AccountState,
    onSignIn: () -> Unit,
    onSwitch: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AccountSheetBody(state, onSignIn, onSwitch, onSignOut, onDismiss)
    }
}

/** What the sheet holds, without its host. */
@Composable
internal fun ColumnScope.AccountSheetBody(
    state: AccountState,
    onSignIn: () -> Unit,
    onSwitch: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val signedIn = state as? AccountState.SignedIn
    val name = signedIn?.displayName ?: stringResource(Res.string.video_signed_out_name)
    val line = signedIn?.handle ?: if (signedIn == null) stringResource(Res.string.video_signed_out_hint) else null
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ChannelAvatar(signedIn?.avatarUrl, HEAD_AVATAR, name = signedIn?.displayName)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = name, style = type.title(TypeScale.TITLE_S), color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line != null) {
                Text(text = line, style = type.body(), color = Color(palette.textSecondary), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (signedIn == null) {
        CosmoActionRow(icon = CosmoIcons.User, label = stringResource(Res.string.video_sign_in), onClick = { onSignIn(); onDismiss() })
    } else {
        for (choice in signedIn.accounts.filter { it.id != signedIn.active?.id }) {
            CosmoActionRow(
                icon = CosmoIcons.User,
                label = choice.displayName,
                value = stringResource(Res.string.video_switch_account),
                onClick = { onSwitch(choice.id); onDismiss() },
            )
        }
        CosmoActionRow(icon = CosmoIcons.Close, label = stringResource(Res.string.video_sign_out), onClick = { onSignOut(); onDismiss() })
    }
    Spacer(Modifier.height(8.dp))
}
