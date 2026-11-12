package com.cosmotune.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cosmotune.music.R
import com.cosmotune.music.data.model.Account

/**
 * The phone's one avatar button at the end of the top bar: [CosmoProfileButton], the avatar that opens the profile drawer.
 *
 * The parameter list is upstream's `TopBarAccountButton`'s, name for name and in the same order, with one more at the end,
 * [entries], the drawer's. The head of the drawer is the account: a tap on it does what the old avatar did ([onClick]: the account
 * switcher when signed in, Settings when not). The old button's swipe through profiles ([onSwipeProfile]) is accepted and ignored:
 * the switcher is one tap away from the head.
 */
@Composable
fun ProfileButtonSwitch(
    account: Account?,
    onClick: () -> Unit,
    onSwipeProfile: ((forward: Boolean) -> Boolean)? = null,
    modifier: Modifier = Modifier,
    entries: List<CosmoDrawerEntry> = emptyList(),
) {
    CosmoProfileButton(
        avatarUrl = account?.thumbnailUrl,
        name = account?.name ?: stringResource(R.string.sign_in),
        subtitle = account?.email?.takeIf { it.isNotBlank() },
        entries = entries,
        onAccount = onClick,
        modifier = modifier,
    )
}
