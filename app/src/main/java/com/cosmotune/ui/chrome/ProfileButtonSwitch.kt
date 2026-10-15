package com.cosmotune.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cosmotune.ui.theme.LocalCosmoShell
import com.music.bitchord.R
import com.music.bitchord.data.model.Account
import com.music.bitchord.ui.components.TopBarAccountButton

/**
 * The phone's one avatar button at the end of the top bar: [CosmoProfileButton] (the avatar that opens the profile drawer) while
 * the new look is on (the shell exists), the old [TopBarAccountButton] while it is off.
 *
 * The parameter list is [TopBarAccountButton]'s, name for name and in the same order, with one more at the end, [entries], which
 * only the drawer uses (the old button has no menu). The old branch passes its four arguments by name, so when upstream adds a
 * parameter to the old button this file stops compiling and is where the new one has to be decided on.
 *
 * In the new look the head of the drawer is the account: a tap on it does what the old avatar did ([onClick]: the account
 * switcher when signed in, Settings when not). The old swipe on the avatar to step through profiles ([onSwipeProfile]) belongs to
 * the old button only: the switcher is one tap away from the head.
 */
@Composable
fun ProfileButtonSwitch(
    account: Account?,
    onClick: () -> Unit,
    onSwipeProfile: ((forward: Boolean) -> Boolean)? = null,
    modifier: Modifier = Modifier,
    entries: List<CosmoDrawerEntry> = emptyList(),
) {
    if (LocalCosmoShell.current != null) {
        CosmoProfileButton(
            avatarUrl = account?.thumbnailUrl,
            name = account?.name ?: stringResource(R.string.sign_in),
            subtitle = account?.email,
            entries = entries,
            onAccount = onClick,
            modifier = modifier,
        )
    } else {
        TopBarAccountButton(
            account = account,
            onClick = onClick,
            onSwipeProfile = onSwipeProfile,
            modifier = modifier,
        )
    }
}
