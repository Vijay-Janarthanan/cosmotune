package com.cosmotune.video.ui.settings

import androidx.compose.runtime.Composable
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.settings.CosmoRowDivider
import com.cosmotune.ui.settings.CosmoSettingsGroup
import com.cosmotune.ui.settings.CosmoSettingsRow
import com.cosmotune.ui.settings.CosmoSettingsSubRow
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_seg_ask
import com.cosmotune.video.resources.video_seg_mute
import com.cosmotune.video.resources.video_seg_off
import com.cosmotune.video.resources.video_seg_show
import com.cosmotune.video.resources.video_seg_skip
import com.cosmotune.video.resources.video_seg_title_exclusive_access
import com.cosmotune.video.resources.video_seg_title_filler
import com.cosmotune.video.resources.video_seg_title_hook
import com.cosmotune.video.resources.video_seg_title_interaction
import com.cosmotune.video.resources.video_seg_title_intro
import com.cosmotune.video.resources.video_seg_title_music_offtopic
import com.cosmotune.video.resources.video_seg_title_outro
import com.cosmotune.video.resources.video_seg_title_poi_highlight
import com.cosmotune.video.resources.video_seg_title_preview
import com.cosmotune.video.resources.video_seg_title_selfpromo
import com.cosmotune.video.resources.video_seg_title_sponsor
import com.cosmotune.video.resources.video_set_sponsorblock
import com.cosmotune.video.resources.video_set_sponsorblock_bar_short
import com.cosmotune.video.resources.video_set_sponsorblock_count
import com.cosmotune.video.resources.video_set_sponsorblock_count_summary
import com.cosmotune.video.resources.video_set_sponsorblock_on_short
import com.cosmotune.video.resources.video_set_using_sponsorblock
import com.cosmotune.video.segments.SegmentAction
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.VideoIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The SponsorBlock group of the settings (spec 5.11, 7.1): the master switch, "Show segments on the bar", "Count skips" (with what it
 * shares), then one row for each category with the behaviour it has now, which opens the category's choice ([onPickCategory]), and the
 * attribution link under the group. With the master switch off the category rows are dimmed and do nothing: the choices are kept.
 *
 * [update] changes the settings the way the page does (`graph.settings.update`).
 */
@Composable
internal fun SponsorBlockSettings(
    settings: VideoSettings,
    update: SettingsUpdate,
    onPickCategory: (SegmentCategory) -> Unit,
) {
    val on = settings.sponsorBlock
    CosmoSettingsGroup(header = stringResource(Res.string.video_set_sponsorblock)) {
        CosmoSettingsRow(
            icon = VideoIcons.SkipSegment,
            title = stringResource(Res.string.video_set_sponsorblock_on_short),
            trailing = { CosmoSwitch(checked = on, onCheckedChange = { value -> update { it.copy(sponsorBlock = value) } }) },
            onClick = { update { it.copy(sponsorBlock = !on) } },
        )
        CosmoSettingsSubRow(
            title = stringResource(Res.string.video_set_sponsorblock_bar_short),
            checked = settings.segmentsOnBar,
            onCheckedChange = { value -> update { it.copy(segmentsOnBar = value) } },
        )
        CosmoSettingsSubRow(
            title = stringResource(Res.string.video_set_sponsorblock_count),
            checked = settings.countSkips,
            onCheckedChange = { value -> update { it.copy(countSkips = value) } },
            subtitle = stringResource(Res.string.video_set_sponsorblock_count_summary),
        )
        CosmoRowDivider()
        for (category in SegmentCategory.entries) {
            CosmoSettingsRow(
                title = categoryTitle(category),
                value = behaviourLabel(behaviourOf(settings, category)),
                enabled = on,
                onClick = { onPickCategory(category) },
            )
        }
    }
    AttributionLink(text = stringResource(Res.string.video_set_using_sponsorblock), url = SPONSORBLOCK_URL)
}

/**
 * The list a category's row opens: the behaviours [allowedBehaviours] gives it, the one it has now ticked; a pick is saved with the
 * other categories' choices kept.
 */
@Composable
internal fun CategoryChoiceSheet(category: SegmentCategory, settings: VideoSettings, update: SettingsUpdate, onDismiss: () -> Unit) {
    ChoiceSheet(
        title = categoryTitle(category),
        options = allowedBehaviours(category).map { ChoiceOption(it, behaviourLabel(it)) },
        selected = behaviourOf(settings, category),
        onSelect = { behaviour -> update { it.copy(segmentBehaviours = it.segmentBehaviours + (category to behaviour)) } },
        onDismiss = onDismiss,
    )
}

/** What happens with [category] now: the person's choice, else the category's default (spec 7.1). */
internal fun behaviourOf(settings: VideoSettings, category: SegmentCategory): SegmentBehaviour =
    settings.segmentBehaviours[category] ?: category.defaultBehaviour

/**
 * The behaviours [category] may have, in the order Skip, Mute, Ask, Show, Off (spec 5.11): Skip where it can be skipped, Mute where
 * it can be muted, Ask (the pill) where either is possible, and always Show (the bar only) and Off. So the non-music part of a music
 * video has no Mute, and a highlight or exclusive access, which are marks and never skip, only Show and Off.
 */
internal fun allowedBehaviours(category: SegmentCategory): List<SegmentBehaviour> {
    val skips = SegmentAction.SKIP in category.actions
    val mutes = SegmentAction.MUTE in category.actions
    return buildList {
        if (skips) add(SegmentBehaviour.SKIP)
        if (mutes) add(SegmentBehaviour.MUTE)
        if (skips || mutes) add(SegmentBehaviour.ASK)
        add(SegmentBehaviour.SHOW)
        add(SegmentBehaviour.OFF)
    }
}

/** The category's name as the settings and the sheet write it ("Sponsor", "Interaction reminder"). */
@Composable
internal fun categoryTitle(category: SegmentCategory): String = stringResource(
    when (category) {
        SegmentCategory.SPONSOR -> Res.string.video_seg_title_sponsor
        SegmentCategory.SELF_PROMO -> Res.string.video_seg_title_selfpromo
        SegmentCategory.INTERACTION -> Res.string.video_seg_title_interaction
        SegmentCategory.INTRO -> Res.string.video_seg_title_intro
        SegmentCategory.OUTRO -> Res.string.video_seg_title_outro
        SegmentCategory.PREVIEW -> Res.string.video_seg_title_preview
        SegmentCategory.HOOK -> Res.string.video_seg_title_hook
        SegmentCategory.MUSIC_OFFTOPIC -> Res.string.video_seg_title_music_offtopic
        SegmentCategory.FILLER -> Res.string.video_seg_title_filler
        SegmentCategory.POI_HIGHLIGHT -> Res.string.video_seg_title_poi_highlight
        SegmentCategory.EXCLUSIVE_ACCESS -> Res.string.video_seg_title_exclusive_access
    },
)

/** A behaviour's word: Skip, Mute, Ask, Show, Off. */
@Composable
internal fun behaviourLabel(behaviour: SegmentBehaviour): String = stringResource(
    when (behaviour) {
        SegmentBehaviour.SKIP -> Res.string.video_seg_skip
        SegmentBehaviour.MUTE -> Res.string.video_seg_mute
        SegmentBehaviour.ASK -> Res.string.video_seg_ask
        SegmentBehaviour.SHOW -> Res.string.video_seg_show
        SegmentBehaviour.OFF -> Res.string.video_seg_off
    },
)

/** SponsorBlock's home, where "(Using SponsorBlock)" leads (spec 5.11: the required attribution). */
internal const val SPONSORBLOCK_URL = "https://sponsor.ajay.app/"
