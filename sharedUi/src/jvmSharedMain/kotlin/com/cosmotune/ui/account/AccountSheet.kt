package com.cosmotune.ui.account

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
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import org.jetbrains.compose.resources.stringResource

/*
 * The one account sheet of the app, for the one Google sign-in Music and Video share: who is signed in (picture, name, handle),
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

/** [AccountSheetContent] in the kit's bottom sheet. */
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
    KitSheet(onDismiss = onDismiss, title = stringResource(Res.string.cosmo_account_title)) {
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
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val signedOut = model.status == AccountStatus.SIGNED_OUT
    val name = model.name?.takeIf { it.isNotBlank() && !signedOut } ?: stringResource(Res.string.cosmo_account_signed_out_title)
    val status = when (model.status) {
        AccountStatus.SIGNED_IN -> statusText ?: stringResource(Res.string.cosmo_account_status_signed_in)
        AccountStatus.EXPIRED -> stringResource(Res.string.cosmo_account_status_expired)
        AccountStatus.SIGNED_OUT -> stringResource(Res.string.cosmo_account_signed_out_hint)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag(AccountSheetTags.HEAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(model.avatarUrl.takeUnless { signedOut }, 56)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = type.title(TypeScale.TITLE_S), color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!signedOut && !model.handle.isNullOrBlank()) {
                Text(model.handle, style = type.body().copy(fontSize = 13.sp), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(AccountSheetTags.STATUS)) {
                if (!signedOut) {
                    val dot = if (model.status == AccountStatus.EXPIRED) palette.danger else palette.accent
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(CosmoRadii.rXs)).background(Color(dot)))
                    Spacer(Modifier.width(6.dp))
                }
                Text(status, style = type.body().copy(fontSize = 13.sp), color = Color(palette.textSecondary), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    when (model.status) {
        AccountStatus.SIGNED_OUT -> KitButton(
            text = stringResource(Res.string.cosmo_account_sign_in),
            onClick = { onSignIn(); onDismiss() },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().testTag(AccountSheetTags.SIGN_IN),
            icon = CosmoIcons.User,
        )
        AccountStatus.EXPIRED -> KitButton(
            text = stringResource(Res.string.cosmo_account_sign_in_again),
            onClick = { onSignIn(); onDismiss() },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().testTag(AccountSheetTags.SIGN_IN),
            icon = CosmoIcons.User,
        )
        AccountStatus.SIGNED_IN -> Unit
    }
    if (!signedOut && model.choices.size > 1) {
        val groups = model.choices.groupBy { it.group }
        for ((group, rows) in groups) {
            KitGroup(title = group) {
                rows.forEachIndexed { index, choice ->
                    if (index > 0) KitDivider()
                    ChoiceRow(choice, active = choice.id == model.activeId) { onSwitch(choice.id); onDismiss() }
                }
            }
        }
    }
    Spacer(Modifier.size(8.dp))
    KitGroup {
        var first = true
        if (onAddAccount != null) {
            KitSettingsRow(
                title = stringResource(Res.string.cosmo_account_add),
                icon = CosmoIcons.Plus,
                tint = Color(palette.accent),
                onClick = { onAddAccount(); onDismiss() },
                modifier = Modifier.testTag(AccountSheetTags.ADD),
            )
            first = false
        }
        if (!signedOut) {
            if (!first) KitDivider()
            KitSettingsRow(
                title = stringResource(Res.string.cosmo_account_sign_out),
                icon = CosmoIcons.Close,
                tint = Color(palette.danger),
                onClick = { onSignOut(); onDismiss() },
                modifier = Modifier.testTag(AccountSheetTags.SIGN_OUT),
            )
        }
        extra()
    }
    Spacer(Modifier.size(12.dp))
}

/** One channel row: its picture, its name over its handle (or Personal / Brand channel), a check when it is the active one. */
@Composable
private fun ChoiceRow(choice: AccountChoiceRow, active: Boolean, onClick: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val activeLabel = stringResource(Res.string.cosmo_account_active)
    val subtitle = choice.handle?.takeIf { it.isNotBlank() }
        ?: stringResource(if (choice.isBrand) Res.string.cosmo_account_brand else Res.string.cosmo_account_personal)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .then(if (active) Modifier.semantics { contentDescription = "${choice.name}, $activeLabel" } else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(AccountSheetTags.choice(choice.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(choice.avatarUrl, 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                choice.name,
                style = type.body().copy(fontSize = 15.sp, fontWeight = if (active) FontWeight(600) else FontWeight(500)),
                color = Color(if (active) palette.accentStrong else palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = type.body().copy(fontSize = 13.sp), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (active) Icon(CosmoIcons.Check, contentDescription = null, tint = Color(palette.accentStrong), modifier = Modifier.size(20.dp))
    }
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
