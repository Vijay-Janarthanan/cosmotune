package com.cosmotune.video.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_sign_in
import com.cosmotune.video.resources.video_signed_out_hint
import com.cosmotune.video.resources.video_signed_out_name
import com.cosmotune.video.resources.video_switch_account
import com.cosmotune.video.ui.blocks.ChannelAvatar
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind

/*
 * The head of the Library tab: the account's picture (56 dp) with its name and handle and a "Switch account" button, or, signed out, a
 * placeholder picture, "Not signed in", the hint and a "Sign in" button. The page under it is the same either way: the local library.
 */

private val AVATAR = 56.dp

/** The Library tab's head for [state]. [onSignIn] and [onAccount] (the account sheet: switch, sign out) are the page's. */
@Composable
fun YouHeader(state: AccountState, onSignIn: () -> Unit, onAccount: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val signedIn = state as? AccountState.SignedIn
    val name = signedIn?.displayName ?: stringResource(Res.string.video_signed_out_name)
    val line = signedIn?.handle ?: if (signedIn == null) stringResource(Res.string.video_signed_out_hint) else null
    val nameStyle = remember(type, name) { type.forText(type.titleMedium, name) }
    Row(modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ChannelAvatar(signedIn?.avatarUrl, AVATAR, name = signedIn?.displayName)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text = name, style = nameStyle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line != null) {
                Text(text = line, style = type.meta, color = Color(palette.textSecondary), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            KitButton(
                text = stringResource(if (signedIn == null) Res.string.video_sign_in else Res.string.video_switch_account),
                onClick = if (signedIn == null) onSignIn else onAccount,
                modifier = Modifier.padding(top = 4.dp),
                kind = KitButtonKind.TONAL,
                dense = true,
            )
        }
    }
}
