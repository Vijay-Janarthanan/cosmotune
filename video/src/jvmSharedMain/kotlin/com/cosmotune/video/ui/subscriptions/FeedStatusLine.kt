package com.cosmotune.video.ui.subscriptions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.Age
import com.cosmotune.video.design.AgeUnit
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_age_days
import com.cosmotune.video.resources.video_age_hours
import com.cosmotune.video.resources.video_age_minutes
import com.cosmotune.video.resources.video_age_months
import com.cosmotune.video.resources.video_age_now
import com.cosmotune.video.resources.video_age_weeks
import com.cosmotune.video.resources.video_age_years
import com.cosmotune.video.resources.video_channels_failed
import com.cosmotune.video.resources.video_dl_all
import com.cosmotune.video.resources.video_feed_refresh
import com.cosmotune.video.resources.video_feed_updated_now
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.resources.video_updated
import com.cosmotune.video.ui.blocks.LocalVideoNowMs
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The faint line under the channel strip (spec 5.7): when the feed was last updated, how many channels could not be loaded, and the
 * feed's header actions. The numbers and wording are the spec's: 12.5 sp `textTertiary`.
 */

/** The size of the line's text (spec 5.7: "a faint line 12.5 sp `textTertiary`"). */
private const val STATUS_SP = 12.5f

/** The line's height: the 48 dp touch target of its small text buttons. */
private val STATUS_MIN_HEIGHT = 48.dp

/** A text button at the end of a row has 12 dp of padding inside its label, so the row gives that much of its margin back (as the section headers do). */
private val END_BUTTON_MARGIN = CosmoSpacing.margin - 12.dp

/** The "Updated 5 minutes ago" text is worded again every minute. */
private const val TICK_MS = 60_000L

/**
 * The status of the subscription feed (spec 5.7), in at most two rows, each 48 dp tall with the page margin at the sides:
 *
 * - "Updated 5 minutes ago" when [updatedAtMs] is known (against the wall clock, worded again every minute), with the text buttons at
 *   its end: [onRefresh] ("Refresh", for a window where a list cannot be pulled; null: none) and [onDownloadAll] ("Download all", the
 *   feed's header action; null: none). The row is left out when it would be empty;
 * - when [failedCount] is above 0, "3 channels could not be loaded" with "Try again" ([onRetry]) at its end.
 *
 * The text is 12.5 sp in `textTertiary`. A screen that shows no feed yet passes null and 0 and the line draws nothing.
 */
@Composable
fun FeedStatusLine(
    updatedAtMs: Long?,
    failedCount: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
    onDownloadAll: (() -> Unit)? = null,
) {
    val nowMs = rememberStatusNowMs()
    val updated = updatedAtMs?.let { updatedText(it, nowMs) }
    val failed = if (failedCount > 0) pluralStringResource(Res.plurals.video_channels_failed, failedCount, failedCount) else null
    if (updated == null && failed == null && onRefresh == null && onDownloadAll == null) return
    Column(modifier.fillMaxWidth()) {
        if (updated != null || onRefresh != null || onDownloadAll != null) {
            StatusRow(text = updated) {
                if (onRefresh != null) CosmoButton(text = stringResource(Res.string.video_feed_refresh), onClick = onRefresh, kind = CosmoButtonKind.TEXT, size = CosmoButtonSize.SMALL)
                if (onDownloadAll != null) CosmoButton(text = stringResource(Res.string.video_dl_all), onClick = onDownloadAll, kind = CosmoButtonKind.TEXT, size = CosmoButtonSize.SMALL)
            }
        }
        if (failed != null) {
            StatusRow(text = failed) {
                CosmoButton(text = stringResource(Res.string.video_retry), onClick = onRetry, kind = CosmoButtonKind.TEXT, size = CosmoButtonSize.SMALL)
            }
        }
    }
}

/** One row of the line: [text] (when there is one) filling the room, then the [buttons] at the end. */
@Composable
private fun StatusRow(text: String?, buttons: @Composable RowScope.() -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val style = remember(type, text) {
        type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = STATUS_SP.sp, lineHeight = STATUS_SP.sp * 1.35f), text.orEmpty())
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = STATUS_MIN_HEIGHT)
            .padding(start = CosmoSpacing.margin, end = END_BUTTON_MARGIN),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (text != null) {
            Text(
                text = text,
                style = style,
                color = Color(palette.textTertiary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        buttons()
    }
}

/** "Updated 5 minutes ago" for a feed updated at [updatedAtMs], seen at [nowMs]; "Updated just now" under a minute. */
@Composable
private fun updatedText(updatedAtMs: Long, nowMs: Long): String {
    val age = VideoText.age(updatedAtMs, nowMs)
    return if (age.unit == AgeUnit.NOW) stringResource(Res.string.video_feed_updated_now) else stringResource(Res.string.video_updated, ageText(age))
}

/** An [Age] in words, through the plural strings (the cards' `MetaLine` has the same table, private to it). */
@Composable
private fun ageText(age: Age): String = when (age.unit) {
    AgeUnit.NOW -> stringResource(Res.string.video_age_now)
    AgeUnit.MINUTES -> pluralStringResource(Res.plurals.video_age_minutes, age.count, age.count)
    AgeUnit.HOURS -> pluralStringResource(Res.plurals.video_age_hours, age.count, age.count)
    AgeUnit.DAYS -> pluralStringResource(Res.plurals.video_age_days, age.count, age.count)
    AgeUnit.WEEKS -> pluralStringResource(Res.plurals.video_age_weeks, age.count, age.count)
    AgeUnit.MONTHS -> pluralStringResource(Res.plurals.video_age_months, age.count, age.count)
    AgeUnit.YEARS -> pluralStringResource(Res.plurals.video_age_years, age.count, age.count)
}

/**
 * The time the line is worded against: the screen's own clock ([LocalVideoNowMs], which a render provides), else the wall clock,
 * read again every minute. Recomposes the line once a minute and nothing else.
 */
@Composable
private fun rememberStatusNowMs(): Long {
    val fixed = LocalVideoNowMs.current
    if (fixed != null) return fixed
    val ticking by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    return ticking
}
