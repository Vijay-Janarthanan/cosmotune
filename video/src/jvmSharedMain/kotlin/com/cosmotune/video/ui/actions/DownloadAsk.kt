package com.cosmotune.video.ui.actions

import com.cosmotune.video.downloads.DownloadBatchInfo
import com.cosmotune.video.model.VideoItem

/**
 * A download someone asked for and the sheet has not answered yet (spec 5.16: every download asks first, unless a choice is
 * remembered). [VideoActionHandler.download] and [VideoActionHandler.downloadBatch] set it; [DownloadOptionsHost] shows the sheet
 * (phone) or the dialog (desktop) for it and clears it when it is answered or dismissed.
 */
sealed interface DownloadAsk {
    /** One video (a card's sheet, the watch page's chip, the pager's "more"). */
    data class One(val item: VideoItem) : DownloadAsk

    /**
     * A batch (a playlist's "Download all", a channel's "Download the latest", watch later's and the feed's "Download all"):
     * [info] names it, [items] are the videos loaded, and [latestPicker] asks how many of the newest to take (the channel's
     * "Download the latest").
     */
    data class Batch(val info: DownloadBatchInfo, val items: List<VideoItem>, val latestPicker: Boolean) : DownloadAsk
}
