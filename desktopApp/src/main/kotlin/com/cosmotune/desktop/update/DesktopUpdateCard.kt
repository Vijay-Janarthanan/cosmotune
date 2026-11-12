package com.cosmotune.desktop.update

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.music.desktop.DesktopUpdateChecker
import com.cosmotune.ui.kit.KitPill
import com.cosmotune.ui.whatsnew.WhatsNew
import com.cosmotune.ui.whatsnew.WhatsNewAction
import com.cosmotune.ui.whatsnew.whatsNewMeta

/** What the person can do from the update card; each is one of [DesktopUpdater]'s calls or a link. */
internal class UpdateCardActions(
    val update: () -> Unit,
    val later: () -> Unit,
    val cancel: () -> Unit,
    val downloadPage: () -> Unit,
    val fullNotes: () -> Unit,
)

/**
 * The desktop's update card in each state, on the Orbit "What's new" card: the offer (Update now beside Later, or Download when this
 * copy updates through the download page), the download's progress, the hand-over while Cosmotune closes and opens again, and a
 * failure in plain words with Try again and the download page.
 */
internal object DesktopUpdateCard {
    fun of(
        info: DesktopUpdateChecker.UpdateInfo,
        step: UpdateStep,
        install: DesktopInstall,
        oneClickSize: Long?,
        oneClick: Boolean,
        currentVersion: String,
        failure: String?,
        actions: UpdateCardActions,
    ): WhatsNew {
        fun card(primary: WhatsNewAction, secondary: WhatsNewAction? = null, meta: String? = null, progress: Float? = null, problem: String? = null) =
            WhatsNew(info.version, info.highlights, primary, secondary, actions.fullNotes, meta = meta, progress = progress, problem = problem)
        val tryAgain = WhatsNewAction("Try again", actions.update)
        val page = WhatsNewAction("Download page", actions.downloadPage)
        return when {
            failure != null -> card(tryAgain, page, problem = failure)
            step is UpdateStep.Downloading -> card(WhatsNewAction("Cancel", actions.cancel), WhatsNewAction("Hide", actions.later), progress = step.fraction)
            step is UpdateStep.Installing -> card(WhatsNewAction("Installing…") {}, meta = "Cosmotune will close and open again")
            step is UpdateStep.Failed -> card(tryAgain, page, problem = step.message)
            !oneClick -> card(
                WhatsNewAction("Download", actions.update),
                WhatsNewAction("Later", actions.later),
                meta = (install as? DesktopInstall.Browser)?.reason ?: whatsNewMeta(currentVersion, info.version, info.downloadSize),
            )
            else -> {
                val permission = if (install is DesktopInstall.WindowsInstalled && !install.writable) " · Windows will ask for permission" else ""
                card(
                    WhatsNewAction("Update now", actions.update),
                    WhatsNewAction("Later", actions.later),
                    meta = whatsNewMeta(currentVersion, info.version, oneClickSize) + permission,
                )
            }
        }
    }
}

/** The top bar's quiet way back to an update that was put off: "Update" with the rising-arrow mark. */
@Composable
internal fun UpdatePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    KitPill("Update", modifier, icon = Icons.Rounded.Upgrade, onClick = onClick)
}
