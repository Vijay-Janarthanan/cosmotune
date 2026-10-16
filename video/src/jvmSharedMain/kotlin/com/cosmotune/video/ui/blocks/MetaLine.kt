package com.cosmotune.video.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.Age
import com.cosmotune.video.design.AgeUnit
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_age_days
import com.cosmotune.video.resources.video_age_hours
import com.cosmotune.video.resources.video_age_minutes
import com.cosmotune.video.resources.video_age_months
import com.cosmotune.video.resources.video_age_now
import com.cosmotune.video.resources.video_age_weeks
import com.cosmotune.video.resources.video_age_years
import com.cosmotune.video.resources.video_views
import com.cosmotune.video.resources.video_views_none
import com.cosmotune.video.resources.video_watching
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The words under a video's title (spec 5.0: "Channel · 1.2M views · 3 days ago") and the text styles the cards share.
 */

/** The dot between the parts of a meta line (`video_meta_join` joins two parts; a line of three is built here). */
private const val SEPARATOR = " · "

/** The clock the cards print ages against; a screen (or a render) may provide its own, the default is the wall clock read once per card. */
internal val LocalVideoNowMs = compositionLocalOf<Long?> { null }

/** "Now" for a card: [LocalVideoNowMs], else the wall clock at the card's first composition (a list does not tick). */
@Composable
internal fun rememberNowMs(): Long = LocalVideoNowMs.current ?: remember { System.currentTimeMillis() }

/**
 * The meta line of a video as one string (spec 5.0): the channel when [showChannel] is set and the item has one, then the views
 * ("1.2M views", "No views"; "1.2K watching" for a live stream) and the age ("3 days ago") against [nowMs]. A live stream has no age
 * and an upcoming one has no views: it shows the source's own text ("Premieres in 2 days"). An item with only an upload text
 * (the feed's, or the source's) shows that text in the age's place. Parts the item has no data for are left out; an item with
 * none of them gives an empty string.
 */
@Composable
fun MetaLine(item: VideoItem, nowMs: Long, showChannel: Boolean = true): String {
    val channel = if (showChannel) item.channelName?.takeIf { it.isNotBlank() } else null
    val count = item.viewCount
    val views = when {
        item.isUpcoming -> null
        item.isLive -> count?.let { stringResource(Res.string.video_watching, VideoText.compact(it)) }
        count == null -> null
        count <= 0L -> stringResource(Res.string.video_views_none)
        else -> stringResource(Res.string.video_views, VideoText.compact(count))
    }
    val uploadedAtMs = item.uploadedAtMs
    val age = when {
        item.isLive -> null
        item.isUpcoming -> item.uploadedText?.takeIf { it.isNotBlank() }
        uploadedAtMs != null -> ageText(VideoText.age(uploadedAtMs, nowMs))
        else -> item.uploadedText?.takeIf { it.isNotBlank() }
    }
    return remember(channel, views, age) { listOfNotNull(channel, views, age).joinToString(SEPARATOR) }
}

/** An [Age] in words, through the plural strings. */
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

/** A card or row title: Hanken Grotesk at [sizeSp], weight 650, line height 1.3 (`.mrow .ttl`), taller lines for the scripts that need them. */
@Composable
internal fun rememberTitleStyle(sizeSp: Float, text: String): TextStyle {
    val type = LocalCosmoType.current
    return remember(type, sizeSp, text) { type.titleStyle(sizeSp, text) }
}

/** A meta line: Hanken Grotesk at [sizeSp], regular, line height 1.35 (`.mrow .sub`). */
@Composable
internal fun rememberMetaStyle(sizeSp: Float, text: String): TextStyle {
    val type = LocalCosmoType.current
    return remember(type, sizeSp, text) { type.metaStyle(sizeSp, text) }
}

private fun CosmoType.titleStyle(sizeSp: Float, text: String): TextStyle = forText(
    TextStyle(
        fontFamily = textFamily,
        fontWeight = FontWeight(VideoMetrics.FEED_TITLE_WEIGHT),
        fontSize = sizeSp.sp,
        lineHeight = sizeSp.sp * VideoMetrics.FEED_TITLE_LINE,
    ),
    text,
)

private fun CosmoType.metaStyle(sizeSp: Float, text: String): TextStyle = forText(
    TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Normal, fontSize = sizeSp.sp, lineHeight = sizeSp.sp * META_LINE),
    text,
)

/** The meta line's line height factor (`rowSubtitleStyle`: 1.35). */
private const val META_LINE = 1.35f
