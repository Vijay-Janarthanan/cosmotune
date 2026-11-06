package com.cosmotune.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_account_dismiss
import com.cosmotune.music.sharedui.resources.cosmo_account_expired_banner
import com.cosmotune.music.sharedui.resources.cosmo_account_sign_in_again
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.youtube.account.YouTubeAuthState
import com.cosmotune.youtube.account.YouTubeSession
import org.jetbrains.compose.resources.stringResource

/** The banner's test tags. */
object AccountBannerTags {
    const val BANNER = "account-expired-banner"
    const val SIGN_IN = "account-expired-sign-in"
}

/**
 * The sign-in expired and could not be renewed silently, in Orbit: a quiet card at the top of a page, the user glyph on a disc of
 * the danger colour, one line, "Sign in again", and an optional dismiss. Never blocks the page under it: what is on the device
 * still shows. [message] and [actionLabel] replace the defaults (Video says it without the site's name).
 */
@Composable
fun AccountExpiredBanner(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val danger = Color(LocalCosmoColors.current.palette.danger)
    // Orbit's quiet card (the ink at 5 %, radius 22), the user glyph on a disc of the theme's danger colour, the line in the body
    // face, and up to two text actions after it in the accent.
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(look.row)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(AccountBannerTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(danger.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(CosmoIcons.User, contentDescription = null, tint = danger, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            message ?: stringResource(Res.string.cosmo_account_expired_banner),
            style = fonts.body,
            color = look.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        KitTextButton(actionLabel ?: stringResource(Res.string.cosmo_account_sign_in_again), onSignIn, Modifier.testTag(AccountBannerTags.SIGN_IN))
        if (onDismiss != null) KitTextButton(stringResource(Res.string.cosmo_account_dismiss), onDismiss)
    }
}

/**
 * [AccountExpiredBanner] while the one session ([YouTubeSession.shared]) is expired; dismissed, it stays away until the sign-in
 * changes again. For Music's pages (Video reads its own account state).
 */
@Composable
fun SessionExpiredBannerHost(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val state by YouTubeSession.shared.state.collectAsState()
    val expired = state as? YouTubeAuthState.Expired ?: return
    var dismissed by remember(expired) { mutableStateOf(false) }
    if (dismissed) return
    AccountExpiredBanner(onSignIn = onSignIn, modifier = modifier, onDismiss = { dismissed = true })
}
