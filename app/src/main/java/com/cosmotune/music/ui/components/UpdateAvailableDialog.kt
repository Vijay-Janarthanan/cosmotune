package com.cosmotune.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.BuildConfig
import com.cosmotune.music.data.AppUpdateChecker
import com.cosmotune.ui.whatsnew.WhatsNew
import com.cosmotune.ui.whatsnew.WhatsNewAction
import com.cosmotune.ui.whatsnew.WhatsNewSheet
import com.cosmotune.ui.whatsnew.whatsNewMeta
import dev.chrisbanes.haze.HazeState

/** UIAlertController's own metrics: fixed narrow width, 14pt corner, 44pt rows. */
internal val ALERT_WIDTH = 270.dp
internal val ALERT_CORNER = 14.dp
internal val ACTION_HEIGHT = 44.dp

/**
 * The dim behind the alert. Flat on purpose — the glass is the card, and
 * blurring the wallpaper *behind* it too leaves nothing for the card to be
 * frosted against, which is what made this read as a grey box before.
 */
internal val SCRIM_COLOR = Color.Black.copy(alpha = 0.28f)

/**
 * Once-per-launch nudge that a newer build is on GitHub Releases — the top
 * bar's update icon is the quiet, always-there version of this; this is the
 * one-time, hard-to-miss version shown the moment the check comes back.
 *
 * An Orbit sheet ([WhatsNewSheet]): "What's new · <version>" over "Fresh in
 * Cosmotune", the release's Highlights as Orbit rows (never the whole notes,
 * which the quiet "Full release notes" link opens on the release page), then
 * the version and download size, and Update now beside Later.
 *
 * The update round trip happens here rather than in a browser: Update now
 * pulls the release's APK into the app cache (its orbit fills in place of the
 * version line), then Install now hands it to the system installer. Where the
 * release carries no APK at all, Update now opens the releases page instead.
 * Drawn over the whole app in its own tree; [hazeState] is kept for the caller.
 */
@Composable
fun UpdateAvailableDialog(
    update: AppUpdateChecker.UpdateInfo,
    @Suppress("UNUSED_PARAMETER") hazeState: HazeState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onOpenFullNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by AppUpdateChecker.download.collectAsStateWithLifecycle()
    val version = update.version
    val later = WhatsNewAction("Later", onDismiss)
    // Tapping the scrim reads the same as Later: nothing about this update is mandatory. Mid-download it only closes the sheet;
    // the download keeps going and the top bar's icon reopens this onto wherever it got to.
    val card = when (val s = state) {
        is AppUpdateChecker.DownloadState.Downloading -> WhatsNew(
            version, update.highlights, WhatsNewAction("Hide", onDismiss), WhatsNewAction("Cancel", onCancelDownload),
            onOpenFullNotes, progress = s.fraction,
        )
        is AppUpdateChecker.DownloadState.Ready -> WhatsNew(
            version, update.highlights, WhatsNewAction("Install now", onInstall), later, onOpenFullNotes,
            meta = "Cosmotune $version is ready to install",
        )
        is AppUpdateChecker.DownloadState.Failed -> WhatsNew(
            version, update.highlights, WhatsNewAction("Try again", onDownload), WhatsNewAction("Release page", onOpenReleasePage),
            onOpenFullNotes, problem = s.message,
        )
        else -> WhatsNew(
            version, update.highlights, WhatsNewAction("Update now", onDownload), later, onOpenFullNotes,
            meta = whatsNewMeta(BuildConfig.VERSION_NAME, version, update.apkSize),
        )
    }
    WhatsNewSheet(card, onDismiss, modifier)
}

/**
 * The first launch after an update: the same Orbit sheet with the running
 * build's own Highlights and a single Got it ([AppUpdateChecker.welcome]).
 */
@Composable
fun WhatsNewWelcomeSheet(
    welcome: AppUpdateChecker.WelcomeInfo,
    onDismiss: () -> Unit,
    onOpenFullNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WhatsNewSheet(
        WhatsNew(welcome.version, welcome.highlights, WhatsNewAction("Got it", onDismiss), onFullNotes = onOpenFullNotes),
        onDismiss,
        modifier,
    )
}

/**
 * Full-bleed action row. Tinted rather than filled, so the two read as equals
 * in weight and only the font differentiates the default action — the alert's
 * whole point is that neither choice is a trap.
 *
 * [destructive] is the one exception to that: an action that deletes something
 * is red in this lineage, because the point there *is* to break the symmetry.
 * It stays a plain row rather than a filled button, so it reads as a warning
 * and not as the thing to press.
 */
@Composable
internal fun AlertAction(
    label: String,
    emphasised: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ACTION_HEIGHT)
            // iOS washes the whole row instead of drawing a ripple inside it.
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp,
                fontWeight = if (emphasised) FontWeight.W600 else FontWeight.W400,
            ),
            color = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                .copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

/** Hairline separator — [HorizontalDivider][androidx.compose.material3.HorizontalDivider]'s 1dp reads as a bar at this scale. */
@Composable
internal fun AlertRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
    )
}
