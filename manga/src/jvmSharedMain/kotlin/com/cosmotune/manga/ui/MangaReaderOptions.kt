package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.MangaLibraryState
import com.cosmotune.manga.data.MangaReaderBackground
import com.cosmotune.manga.data.MangaReaderFilter
import com.cosmotune.manga.data.MangaTapZones
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.orbit.orbitLook

/**
 * The reader's page options (Mihon's reader settings), the same in the reader's sheet and in Settings › Manga: crop borders, turn
 * wide pages, two pages side by side, the colour filter, the ground around the pages and the volume keys.
 */
@Composable
internal fun MangaReaderImageOptions(state: MangaLibraryState, modifier: Modifier = Modifier, change: ((MangaLibraryState) -> MangaLibraryState) -> Unit) {
    val look = orbitLook()
    Column(modifier.fillMaxWidth().testTag("manga:reader:options"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OrbitSwitchRow(mangaText("Crop borders"), state.readerCrop, { on -> change { it.copy(readerCrop = on) } }, plain = true,
            detail = mangaText("Cuts the plain margins off each page"))
        OrbitSwitchRow(mangaText("Turn wide pages"), state.readerRotateWide, { on -> change { it.copy(readerRotateWide = on) } }, plain = true,
            detail = mangaText("A double page turns sideways to fill a tall screen"))
        OrbitSwitchRow(mangaText("Two pages side by side"), state.readerSpread, { on -> change { it.copy(readerSpread = on) } }, plain = true,
            detail = mangaText("When the window is wider than tall"))
        if (state.readerSpread) OrbitSwitchRow(mangaText("First page alone"), state.readerCoverAlone, { on -> change { it.copy(readerCoverAlone = on) } }, plain = true,
            detail = mangaText("Turn off when the pairs look one page out"))
        Text(mangaText("Colour"), style = ReadType.rowTitle(), color = look.ink)
        ReadChipRow(inset = 0.dp) {
            listOf(MangaReaderFilter.NONE to "Original", MangaReaderFilter.GREY to "Grey", MangaReaderFilter.SEPIA to "Sepia", MangaReaderFilter.INVERT to "Inverted").forEach { (filter, label) ->
                ReadChip(mangaText(label), state.readerFilter == filter, { change { it.copy(readerFilter = filter) } })
            }
        }
        Text(mangaText("Background"), style = ReadType.rowTitle(), color = look.ink)
        ReadChipRow(inset = 0.dp) {
            listOf(MangaReaderBackground.BLACK to "Black", MangaReaderBackground.GREY to "Grey", MangaReaderBackground.WHITE to "White").forEach { (ground, label) ->
                ReadChip(mangaText(label), state.readerBackground == ground, { change { it.copy(readerBackground = ground) } })
            }
        }
        Text(mangaText("Tap zones"), style = ReadType.rowTitle(), color = look.ink)
        ReadChipRow(inset = 0.dp) {
            listOf(MangaTapZones.THIRDS to "Sides", MangaTapZones.L_SHAPED to "L shape", MangaTapZones.KINDLE to "Kindle", MangaTapZones.EDGE to "Edges", MangaTapZones.OFF to "Off").forEach { (zones, label) ->
                ReadChip(mangaText(label), state.readerTapZones == zones, { change { it.copy(readerTapZones = zones) } })
            }
        }
        if (state.readerTapZones != MangaTapZones.OFF) OrbitSwitchRow(mangaText("Swap back and next"), state.readerTapInvert, { on -> change { it.copy(readerTapInvert = on) } }, plain = true)
        OrbitSwitchRow(mangaText("Skip read chapters"), state.skipReadChapters, { on -> change { it.copy(skipReadChapters = on) } }, plain = true,
            detail = mangaText("Next chapter goes past the ones you have read"))
        OrbitSwitchRow(mangaText("Volume keys turn pages"), state.readerVolumeKeys, { on -> change { it.copy(readerVolumeKeys = on) } }, plain = true)
    }
}
