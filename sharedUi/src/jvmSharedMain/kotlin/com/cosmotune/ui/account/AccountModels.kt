package com.cosmotune.ui.account

import com.cosmotune.youtube.account.YouTubeAuthState

/**
 * The sheet's model from the one session's state and the host's list of channels: who is shown is the session's described account
 * and channel (the channel's picture and name first), [choices] the host's.
 */
fun accountSheetModelOf(state: YouTubeAuthState, choices: List<AccountChoiceRow>, activeId: String?): AccountSheetModel {
    val (account, profile) = when (state) {
        is YouTubeAuthState.SignedIn -> state.account to state.profile
        is YouTubeAuthState.Expired -> state.account to state.profile
        YouTubeAuthState.SignedOut -> return AccountSheetModel.SIGNED_OUT
    }
    return AccountSheetModel(
        status = if (state is YouTubeAuthState.Expired) AccountStatus.EXPIRED else AccountStatus.SIGNED_IN,
        name = profile?.name?.takeIf { it.isNotBlank() } ?: account?.name,
        handle = profile?.handle ?: account?.email,
        avatarUrl = profile?.avatarUrl ?: account?.avatarUrl,
        choices = choices,
        activeId = activeId ?: profile?.id,
    )
}
