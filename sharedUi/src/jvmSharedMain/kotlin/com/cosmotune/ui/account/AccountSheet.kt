package com.cosmotune.ui.account

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import com.cosmotune.ui.orbit.OrbitAccountCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_account_active
import com.cosmotune.music.sharedui.resources.cosmo_account_add
import com.cosmotune.music.sharedui.resources.cosmo_account_brand
import com.cosmotune.music.sharedui.resources.cosmo_account_personal
import com.cosmotune.music.sharedui.resources.cosmo_account_sign_in
import com.cosmotune.music.sharedui.resources.cosmo_account_sign_in_again
import com.cosmotune.music.sharedui.resources.cosmo_account_sign_out
import com.cosmotune.music.sharedui.resources.cosmo_account_signed_out_hint
import com.cosmotune.music.sharedui.resources.cosmo_account_signed_out_title
import com.cosmotune.music.sharedui.resources.cosmo_account_status_expired
import com.cosmotune.music.sharedui.resources.cosmo_account_status_signed_in
import com.cosmotune.music.sharedui.resources.cosmo_account_title
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitArt
import com.cosmotune.ui.kit.KitArtwork
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitDivider
import com.cosmotune.ui.kit.KitGroup
import com.cosmotune.ui.kit.KitSettingsRow
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import org.jetbrains.compose.resources.stringResource

/*
 * The one account sheet of the app in Orbit (the account card of Canvas repo, design/cosmotune/batch-a/board.html, section 2, leads
 * it), for the one Google sign-in Music and Video share: who is signed in (picture, name, handle),
 * where the sign-in stands ("Signed in to YouTube & YouTube Music", or expired), the channels and accounts to switch to, and
 * Sign in / Sign in again / Add account / Sign out. Music's profile button and Video's avatar open it; it takes what it shows as
 * an [AccountSheetModel] and says what was tapped.
 */

/** Where the sign-in stands, for the sheet and the chip. */
enum class AccountStatus { SIGNED_OUT, SIGNED_IN, EXPIRED }

/**
 * What the sheet shows. [choices] is every channel of every login the app holds, the active one ([activeId]) among them; a choice's
 * [AccountChoiceRow.group] (the login's email) heads the rows of that login.
 */
@Immutable
data class AccountSheetModel(
    val status: AccountStatus,
    val name: String? = null,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val choices: List<AccountChoiceRow> = emptyList(),
    val activeId: String? = null,
) {
    companion object {
        val SIGNED_OUT = AccountSheetModel(AccountStatus.SIGNED_OUT)
    }
}

/** One channel to switch to. [isBrand] says "Brand channel" under it when it has no handle. */
@Immutable
data class AccountChoiceRow(
    val id: String,
    val name: String,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val group: String? = null,
    val isBrand: Boolean = false,
)

/** The test tags of the sheet's parts. */
object AccountSheetTags {
    const val HEAD = "account-sheet-head"
    const val STATUS = "account-sheet-status"
    const val SIGN_IN = "account-sheet-sign-in"
    const val SIGN_OUT = "account-sheet-sign-out"
    const val ADD = "account-sheet-add"
    fun choice(id: String) = "account-sheet-choice-$id"
}

/** [AccountSheetContent] in the Orbit sheet, under its title. */
@Composable
fun AccountSheet(
    model: AccountSheetModel,
    onSignIn: () -> Unit,
    onSwitch: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    onAddAccount: (() -> Unit)? = null,
    statusText: String? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    // The Orbit sheet (the song sheet's flat surface and grabber), not the kit's glass sheet.
    com.cosmotune.ui.orbit.OrbitPickerSheet(onDismiss = onDismiss) {
        com.cosmotune.ui.orbit.OrbitSheetTitle(stringResource(Res.string.cosmo_account_title))
        AccountSheetContent(model, onSignIn, onSwitch, onSignOut, onDismiss, onAddAccount, statusText, extra)
    }
}

/**
 * What the sheet holds, without its host (a desktop panel or a preview draws it in place). A tap calls its callback and then
 * [onDismiss]. [statusText] replaces the signed-in status line (Video says it without the site's name); [extra] adds rows of the
 * host's own (Music's Settings) at the end.
 */
@Composable
fun ColumnScope.AccountSheetContent(
    model: AccountSheetModel,
    onSignIn: () -> Unit,
    onSwitch: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    onAddAccount: (() -> Unit)? = null,
    statusText: String? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val palette = LocalCosmoColors.current.palette
    val signedOut = model.status == AccountStatus.SIGNED_OUT
    val name = model.name?.takeIf { it.isNotBlank() && !signedOut } ?: stringResource(Res.string.cosmo_account_signed_out_title)
    val status = when (model.status) {
        AccountStatus.SIGNED_IN -> statusText ?: stringResource(Res.string.cosmo_account_status_signed_in)
        AccountStatus.EXPIRED -> stringResource(Res.string.cosmo_account_status_expired)
        AccountStatus.SIGNED_OUT -> stringResource(Res.string.cosmo_account_signed_out_hint)
    }
    // Who is signed in, as the account card that leads Settings (Orbit, board batch-a 2): the colour card, the picture as a disc.
    OrbitAccountCard(
        label = if (signedOut) "Account" else "Account · Google",
        name = name,
        line = model.handle?.takeIf { it.isNotBlank() && !signedOut },
        pictureUrl = model.avatarUrl.takeUnless { signedOut },
        fonts = fonts,
        onClick = null,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(AccountSheetTags.HEAD),
        compact = true,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp).testTag(AccountSheetTags.STATUS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!signedOut) {
            val dot = if (model.status == AccountStatus.EXPIRED) Color(palette.danger) else LIVE_DOT
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
        }
        Text(status, style = fonts.body, color = look.dim, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    if (model.status != AccountStatus.SIGNED_IN) {
        // One solid ink pill: the page's one thing to do.
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(48.dp).clip(CircleShape).background(look.ink)
                .clickable(role = Role.Button) { onSignIn(); onDismiss() }
                .testTag(AccountSheetTags.SIGN_IN),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CosmoIcons.User, contentDescription = null, tint = look.ground, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (model.status == AccountStatus.EXPIRED) Res.string.cosmo_account_sign_in_again else Res.string.cosmo_account_sign_in),
                style = fonts.chip, color = look.ground,
            )
        }
    }
    if (!signedOut && model.choices.size > 1) {
        val groups = model.choices.groupBy { it.group }
        for ((group, rows) in groups) {
            if (group != null) OrbitLabel(group, fonts, look.dim, Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 8.dp))
            Column(Modifier.padding(horizontal = 16.dp, vertical = if (group == null) 8.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { choice -> ChoiceRow(choice, active = choice.id == model.activeId, fonts, look) { onSwitch(choice.id); onDismiss() } }
            }
        }
    }
    Spacer(Modifier.size(12.dp))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onAddAccount != null) {
            OrbitSongRow(
                title = stringResource(Res.string.cosmo_account_add), subtitle = "", cover = null, fonts = fonts, look = look,
                onClick = { onAddAccount(); onDismiss() }, icon = CosmoIcons.Plus, height = 56.dp, discSize = 36.dp,
                modifier = Modifier.fillMaxWidth().testTag(AccountSheetTags.ADD),
            )
        }
        if (!signedOut) {
            OrbitSongRow(
                title = stringResource(Res.string.cosmo_account_sign_out), subtitle = "", cover = null, fonts = fonts, look = look,
                onClick = { onSignOut(); onDismiss() }, icon = CosmoIcons.Close, height = 56.dp, discSize = 36.dp,
                modifier = Modifier.fillMaxWidth().testTag(AccountSheetTags.SIGN_OUT),
            )
        }
    }
    Column(Modifier.padding(horizontal = 16.dp)) { extra() }
    Spacer(Modifier.size(12.dp))
}

/** The green of a sign-in that is in force (Orbit's live dot). */
private val LIVE_DOT = Color(0xFF7CE3A1)

/** One channel: Home's row with its picture as the disc, its name over its handle (or Personal / Brand channel), a check when active. */
@Composable
private fun ChoiceRow(choice: AccountChoiceRow, active: Boolean, fonts: OrbitFonts, look: OrbitLook, onClick: () -> Unit) {
    val activeLabel = stringResource(Res.string.cosmo_account_active)
    val subtitle = choice.handle?.takeIf { it.isNotBlank() }
        ?: stringResource(if (choice.isBrand) Res.string.cosmo_account_brand else Res.string.cosmo_account_personal)
    OrbitSongRow(
        title = choice.name, subtitle = subtitle, cover = choice.avatarUrl, fonts = fonts, look = look, onClick = onClick,
        height = 60.dp, discSize = 40.dp,
        icon = if (choice.avatarUrl == null) CosmoIcons.User else null,
        modifier = Modifier.fillMaxWidth()
            .then(if (active) Modifier.semantics { contentDescription = "${choice.name}, $activeLabel" } else Modifier)
            .testTag(AccountSheetTags.choice(choice.id)),
        trailing = if (active) { { Icon(CosmoIcons.Check, contentDescription = null, tint = look.ink, modifier = Modifier.size(20.dp)) } } else null,
    )
}

/** A round picture of [sizeDp] over the user glyph (what a signed-out or a failed load shows). */
@Composable
internal fun AccountAvatar(url: String?, sizeDp: Int, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val size = sizeDp.dp
    Box(modifier.size(size).clip(RoundedCornerShape(size / 2)).background(Color(palette.accentContainer)), contentAlignment = Alignment.Center) {
        Icon(CosmoIcons.User, contentDescription = null, tint = Color(palette.onAccentContainer), modifier = Modifier.size(size * 0.5f))
        if (url != null) KitArtwork(url, KitArt.ROUND, size)
    }
}

/**
 * The account chip: the picture and the name in a pill, with a red dot when the sign-in expired. Opens the sheet. For a bar that
 * has room for a name (the desktop, a wide page); the phone's top bars use their own avatar button.
 */
@Composable
fun AccountChip(model: AccountSheetModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val signedOut = model.status == AccountStatus.SIGNED_OUT
    val label = if (signedOut) stringResource(Res.string.cosmo_account_sign_in) else model.name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.cosmo_account_title)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(CosmoRadii.rLg))
            .background(Color(palette.surface))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AccountAvatar(model.avatarUrl.takeUnless { signedOut }, 32)
            if (model.status == AccountStatus.EXPIRED) {
                Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(RoundedCornerShape(CosmoRadii.rXs)).background(Color(palette.danger)))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = type.body().copy(fontSize = 14.sp, fontWeight = FontWeight(500)), color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
