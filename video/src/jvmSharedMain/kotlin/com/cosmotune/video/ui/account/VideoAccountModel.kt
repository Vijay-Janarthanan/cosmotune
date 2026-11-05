package com.cosmotune.video.ui.account

import androidx.compose.runtime.Composable
import com.cosmotune.ui.account.AccountChoiceRow
import com.cosmotune.ui.account.AccountSheet
import com.cosmotune.ui.account.AccountSheetModel
import com.cosmotune.ui.account.AccountStatus
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_account_status_signed_in
import org.jetbrains.compose.resources.stringResource

/**
 * The app's one account sheet ([AccountSheet]) with Video's account in it. Signed in: the account's picture, name and handle, where the
 * sign-in stands (signed in for Video and Music, or expired with "Sign in again"), a row for each channel to switch to, and Sign out.
 * Signed out: the hint and Sign in. A tap on a row calls its callback and closes the sheet.
 */
@Composable
internal fun VideoAccountSheet(state: AccountState, onSignIn: () -> Unit, onSwitch: (String) -> Unit, onSignOut: () -> Unit, onDismiss: () -> Unit) {
    AccountSheet(
        model = state.toSheetModel(),
        onSignIn = onSignIn,
        onSwitch = onSwitch,
        onSignOut = onSignOut,
        onDismiss = onDismiss,
        statusText = stringResource(Res.string.video_account_status_signed_in),
    )
}

/** Video's account state as the shared sheet shows it. */
internal fun AccountState.toSheetModel(): AccountSheetModel = when (this) {
    AccountState.SignedOut -> AccountSheetModel.SIGNED_OUT
    is AccountState.SignedIn -> AccountSheetModel(
        status = if (expired) AccountStatus.EXPIRED else AccountStatus.SIGNED_IN,
        name = displayName,
        handle = handle,
        avatarUrl = avatarUrl,
        choices = accounts.map { AccountChoiceRow(id = it.id, name = it.displayName, handle = it.handle, avatarUrl = it.avatarUrl) },
        activeId = active?.id,
    )
}
