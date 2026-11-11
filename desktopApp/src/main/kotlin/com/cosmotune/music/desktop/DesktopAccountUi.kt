package com.cosmotune.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import com.cosmotune.ui.bridge.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.icons.CosmotuneIcons
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitAccountCard
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.orbitFloatEdge
import com.cosmotune.ui.orbit.orbitFloatGround
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoDialogBody
import com.cosmotune.ui.sheets.CosmoDialogCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The account button in the toolbar: the listener's photo, or a person glyph when there is nobody
 * signed in.
 */
@Composable
internal fun DesktopAccountButton(
    avatar: String?,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            DesktopArtwork(
                avatar,
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentScale = ContentScale.Crop,
                px = 96,
            )
        } else {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    DesktopStrings["accounts", "Accounts"],
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The account switcher as an Orbit popover (the kit's float card, as [com.cosmotune.ui.orbit.OrbitMenu] draws it) under the toolbar's
 * account button: the account card at its head (Settings' own, compact: the active profile's picture as a disc, its name or "Sign
 * in", its handle), the quick theme switch (Light, Dark or the system's: the Settings choice itself, [com.cosmotune.ui.orbit.OrbitThemeRow]),
 * then every login this app holds with the channels under it as Orbit rows (the picture disc, the name, the handle;
 * the one in use solid ink with a check in its own ink), then the actions that change the set (add an account, manage them, Settings) as
 * rows. "Manage accounts" turns the profile rows into "Sign out" rows. A click outside closes it.
 */
@Composable
internal fun DesktopAccountSelector(
    accounts: List<DesktopGoogleAccount>,
    activeAccountId: String?,
    activeProfileId: String?,
    busy: Boolean,
    onSelect: (DesktopGoogleAccount, DesktopYouTubeProfile) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (DesktopGoogleAccount) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    val look = orbitLook()
    val fonts = orbitFonts()
    val activeAccount = accounts.firstOrNull { it.accountId == activeAccountId }
    val activeProfile = activeAccount?.profiles?.firstOrNull { it.profileId == activeProfileId }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // The popover never runs past the window: everything in it scrolls inside it, the actions at its foot included.
        val room = (maxHeight - 64.dp - 24.dp).coerceAtLeast(160.dp)
        // The scrim is its own layer rather than a modifier on the container.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 24.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .heightIn(max = room)
                .shadow(24.dp, shape, clip = false)
                .clip(shape)
                .background(orbitFloatGround(look))
                .border(1.dp, orbitFloatEdge(look), shape)
                // Clicks on the card's own background stay on the card.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(8.dp),
        ) {
            OrbitAccountCard(
                label = if (activeProfile != null) "Account · Google" else "Account",
                name = activeProfile?.name ?: activeAccount?.name?.ifBlank { null } ?: "Sign in",
                line = activeProfile?.handle?.ifBlank { null } ?: activeAccount?.email?.ifBlank { null }
                    ?: if (accounts.isEmpty()) DesktopStrings[
                        "d_nobody_is_signed_in",
                        "Nobody is signed in. Your library, playlists and history need a YouTube Music account.",
                    ] else null,
                pictureUrl = activeProfile?.avatar,
                fonts = fonts,
                onClick = if (accounts.isEmpty()) onAddAccount else null,
                compact = true,
            )
            // The quick theme switch under the card: its label on the account labels' line (14 dp), the pill's end on the rows'
            // end, the pill 12 dp under the card and the accounts' label 14 dp under the pill (the switch's 48 dp touch targets
            // stand 4 dp beyond the pill on each side). It stores the Settings choice, so the two always agree.
            com.cosmotune.ui.orbit.OrbitThemeRow(Modifier.padding(top = 8.dp), labelStart = 14.dp)
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OrbitLabel(if (accounts.isEmpty()) "Accounts" else "Switch account", fonts, look.dim, Modifier.weight(1f))
                if (busy) CircularProgressIndicator(color = look.accent, modifier = Modifier.size(16.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                accounts.forEach { account ->
                    run {
                        Text(
                            account.email.ifBlank { account.name.ifBlank { "Account" } },
                            style = fonts.body.copy(fontSize = 12.sp),
                            color = look.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                        )
                    }
                    account.profiles.forEach { profile ->
                        DesktopProfileRow(
                            profile = profile,
                            selected = account.accountId == activeAccountId &&
                                profile.profileId == activeProfileId,
                            managing = managing,
                            onClick = { onSelect(account, profile); onDismiss() },
                            onRemove = { onRemoveAccount(account) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                DesktopSelectorAction(CosmotuneIcons.Plus, "Add account", onAddAccount)
                if (accounts.isNotEmpty()) {
                    run {
                        DesktopSelectorAction(Icons.Rounded.ManageAccounts, if (managing) "Done managing" else "Manage accounts", onClick = { managing = !managing }, on = managing)
                    }
                }
                DesktopSelectorAction(CosmoIcons.Settings, "Settings", onOpenSettings)
            }
        }
    }
}

/**
 * A channel of an account as an Orbit row: 60 dp, radius 22, the picture as a 40 dp disc (the user glyph on a faint disc without
 * one), the name over the handle. The one in use is solid ink with the ground's colour for its words and a check in that colour; while
 * [managing] the row says "Sign out" in red and a click signs the account out.
 */
@Composable
private fun DesktopProfileRow(
    profile: DesktopYouTubeProfile,
    selected: Boolean,
    managing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val on = selected && !managing
    val ink = if (on) look.ground else look.ink
    val dim = if (on) look.ground.copy(alpha = 0.72f) else look.dim
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (on) look.ink else look.row)
            .clickable(role = Role.Button, onClick = if (managing) onRemove else onClick)
            .semantics { if (selected) this.selected = true }
            .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(ink.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(CosmoIcons.User, null, tint = ink, modifier = Modifier.size(18.dp))
            if (profile.avatar != null) Disc(profile.avatar, 40.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, style = fonts.title.copy(fontSize = 14.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                profile.handle.ifBlank { if (profile.isBrandAccount) "Brand account" else "Personal" },
                style = fonts.body.copy(fontSize = 12.sp),
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            managing -> Text(DesktopStrings["sign_out", "Sign out"], color = DesktopDestructive, style = fonts.chip.copy(fontSize = 13.sp))
            // A check in the row's own ink, not peach: peach is "playing now" alone.
            selected -> Icon(CosmoIcons.Check, DesktopStrings["selected", "Selected"], tint = ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** An action of the switcher as an Orbit row: 44 dp, round, the glyph in the dim ink and the label; [on] (managing) is the chip fill. */
@Composable
private fun DesktopSelectorAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    on: Boolean = false,
) {
    val look = orbitLook()
    val fonts = orbitFonts()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(if (on) look.chip else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = look.dim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = fonts.chip, color = look.ink)
    }
}

/** What [DesktopSignInDialog]'s `busy` holds while the in-app sign-in window is open. */
internal const val WEB_SIGN_IN = "Google"

/**
 * Signing in, on the Orbit dialog ([CosmoDialogCard] with [CosmoDialogBody]): Google's own page in a Cosmotune window, a browser to
 * take the session from, or the cookie by hand, each an Orbit row. A click on the scrim does nothing (leaving would cancel a sign-in
 * that is still open): only Close does.
 */
@Composable
internal fun DesktopSignInDialog(
    /** Whether the in-app sign-in window can be shown; see [DesktopWebSignIn]. */
    webSignIn: Boolean,
    onWebSignIn: () -> Unit,
    onImport: (DesktopBrowserCookies.Profile) -> Unit,
    onPaste: (String) -> Unit,
    busy: String?,
    error: String?,
    onDismiss: () -> Unit,
) {
    var profiles by remember { mutableStateOf<List<DesktopBrowserCookies.Profile>?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        profiles = withContext(Dispatchers.IO) {
            DesktopBrowserCookies.profiles().filterNot {
                DesktopPlatform.isWindows && it.family == DesktopBrowserCookies.Family.CHROMIUM
            }
        }
    }
    val look = orbitLook()
    val fonts = orbitFonts()

    CosmoDialogCard(onDismiss = {}, maxWidth = 520.dp) {
        CosmoDialogBody(
            overline = "Account",
            title = DesktopStrings["sign_in_youtube_music", "Sign in to YouTube Music"],
            message = when {
                pasting -> DesktopStrings["d_paste_the_cookie_header_for_music_youtube_com", "Paste the Cookie header for music.youtube.com."]
                webSignIn -> "Sign in with Google in a Cosmotune window, then choose \"Use this profile\"."
                else -> DesktopStrings[
                    "d_google_signs_in_inside_a_browser",
                    "Google signs in inside a browser and hands back a session. Pick a browser " +
                        "here that is already signed in and Cosmotune will use its session.",
                ]
            },
            confirmLabel = if (pasting) DesktopStrings["sign_in", "Sign in"] else null,
            onConfirm = { onPaste(pasted.trim()) },
            confirmEnabled = pasted.isNotBlank() && busy == null,
            // Close alone would be the dialog's one peach action; it is a quiet button of its own below instead.
            dismissLabel = if (pasting) DesktopStrings["back", "Back"] else null,
            onDismiss = { pasting = false },
            extra = {
                error?.let {
                    Text(it, style = fonts.body, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 10.dp))
                }
                when {
                    pasting -> BasicTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        singleLine = false,
                        textStyle = fonts.body.copy(color = look.ink),
                        cursorBrush = SolidColor(look.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 84.dp, max = 160.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(look.row)
                            .padding(14.dp),
                    )
                    profiles == null -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = look.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(DesktopStrings["d_looking_for_browsers", "Looking for browsers…"], style = fonts.body, color = look.dim)
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val found = profiles.orEmpty()
                        if (webSignIn) {
                            SignInRow(
                                "Sign in with Google",
                                if (busy == WEB_SIGN_IN) "Finish in the sign-in window, then choose \"Use this profile\""
                                else "Opens Google's sign-in page in a Cosmotune window",
                                CosmoIcons.User, enabled = busy == null, working = busy == WEB_SIGN_IN, onClick = onWebSignIn,
                            )
                        }
                        if (found.isEmpty() && !webSignIn) {
                            Text(
                                DesktopStrings["d_no_browser_profile_was_found_on_this_machine", "No browser profile was found on this machine."],
                                style = fonts.body, color = look.dim, modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        found.forEach { profile ->
                            SignInRow(profile.label, null, CosmoIcons.Explore, enabled = busy == null, working = busy == profile.label) { onImport(profile) }
                        }
                        SignInRow(DesktopStrings["d_paste_a_cookie_instead", "Paste a cookie instead"], null, Icons.Rounded.ContentPaste, enabled = true, working = false) {
                            pasting = true
                        }
                    }
                }
            },
        )
        if (!pasting) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                OrbitPillButton(DesktopStrings["close", "Close"], onDismiss, fonts, look)
            }
        }
    }
}

/** One way to sign in as an Orbit row: 56 dp, radius 22, the glyph, the [title] over its [detail], a small spinner while [working]. */
@Composable
private fun SignInRow(
    title: String,
    detail: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    working: Boolean,
    onClick: () -> Unit,
) {
    val look = orbitLook()
    val fonts = orbitFonts()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(look.row)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = look.dim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (working) CircularProgressIndicator(color = look.accent, modifier = Modifier.size(14.dp))
    }
}
