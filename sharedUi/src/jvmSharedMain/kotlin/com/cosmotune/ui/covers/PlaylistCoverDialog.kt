package com.cosmotune.ui.covers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import com.cosmotune.cloud.SyncPlaylistCovers
import com.cosmotune.music.data.covers.CoverCrop
import com.cosmotune.music.data.covers.CoverJpeg
import com.cosmotune.music.data.covers.CoverPixels
import com.cosmotune.music.data.covers.CoverPlacement
import com.cosmotune.music.data.covers.CropSquare
import com.cosmotune.music.data.covers.PlaylistCoverRules
import com.cosmotune.music.data.covers.PlaylistCoverStore
import com.cosmotune.music.data.covers.PlaylistCovers
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.artworkAt
import com.cosmotune.music.ui.graphics.argbPixels
import com.cosmotune.music.ui.graphics.forPixelAccess
import com.cosmotune.music.ui.graphics.toImageBitmap
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitPageButton
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoDialog
import com.cosmotune.ui.sheets.CosmoDialogActions
import com.cosmotune.ui.sheets.CosmoDialogHeading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The words of the picker, one place for both hosts' menus too. */
object PlaylistCoverWords {
    const val CHANGE = "Change cover"
    const val AUTOMATIC = "Use the automatic cover"
    const val PHOTO = "Choose a photo"
}

/** The photo is decoded at most this big: enough for a 1200 px cover cut from part of it. */
private const val PHOTO_DECODE_PX = 2048

/** The circle the photo is placed in, and the song covers offered first. */
private val PLACE_SIZE = 240.dp
private val PREVIEW_SIZE = 132.dp
private val CHOICE_SIZE = 56.dp

/**
 * Picking the cover of one of the person's own playlists ([browseId], called [title]): the playlist's own song covers first, then
 * "Choose a photo", which is placed in the circle with drag and pinch (or the mouse wheel, or the zoom buttons) before it is saved;
 * and "Use the automatic cover" once a picture is set, which puts back the cover YouTube Music makes ([automatic]). The picture is
 * kept on this device ([PlaylistCovers]) and, when signed in, in the Cosmotune account; never on YouTube Music. A dialog of the
 * Orbit kit on both hosts.
 */
@Composable
fun PlaylistCoverDialog(
    browseId: String,
    title: String,
    songs: List<Song>,
    automatic: String?,
    onDismiss: () -> Unit,
) {
    val covers by PlaylistCovers.covers.collectAsState()
    val custom = PlaylistCoverRules.key(browseId)?.let(covers::get)
    val choices = remember(songs) { PlaylistCoverRules.songChoices(songs) }
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // The store is opened at start, off the main thread; read when it is needed, so a picker opened that early still saves.
    val save: (suspend (PlaylistCoverStore) -> Boolean) -> Unit = { work ->
        if (!busy) scope.launch {
            busy = true
            error = null
            val store = PlaylistCovers.store
            val saved = store != null && runCatching { work(store) }.getOrDefault(false)
            busy = false
            if (saved) onDismiss() else error = "The cover could not be saved. Try again."
        }
    }
    val choosePhoto = rememberCoverPhotoChooser { model ->
        scope.launch {
            busy = true
            error = null
            val image = runCatching { CoverMaker.load(context, model) }.getOrNull()
            busy = false
            if (image == null) error = "That photo could not be opened. Try another one." else photo = image
        }
    }

    CosmoDialog(onDismiss = { if (!busy) onDismiss() }) {
        val placing = photo
        if (placing == null) {
            ChooseStep(
                title = title, preview = picked ?: custom ?: automatic, choices = choices, picked = picked, onPick = { picked = it },
                hasCustom = custom != null, busy = busy, error = error,
                onPhoto = { if (!busy) choosePhoto() },
                onAutomatic = {
                    PlaylistCovers.clearSoon(browseId)
                    onDismiss()
                },
                onSave = {
                    val url = picked
                    if (url != null) save { store -> CoverMaker.saveSongCover(context, store, browseId, url) }
                },
                onCancel = onDismiss,
            )
        } else {
            PlaceStep(
                title = title, photo = placing, busy = busy, error = error,
                onSave = { square -> save { store -> CoverMaker.save(store, browseId, placing, square) } },
                onCancel = {
                    photo = null
                    error = null
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChooseStep(
    title: String,
    preview: String?,
    choices: List<String>,
    picked: String?,
    onPick: (String) -> Unit,
    hasCustom: Boolean,
    busy: Boolean,
    error: String?,
    onPhoto: () -> Unit,
    onAutomatic: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
        CosmoDialogHeading(title, PlaylistCoverWords.CHANGE, error, messageIsError = error != null)
        Column(Modifier.weight(1f, fill = false).padding(top = 18.dp).verticalScroll(rememberScrollState())) {
            Disc(preview, PREVIEW_SIZE, Modifier.align(Alignment.CenterHorizontally).testTag("cover:preview"))
            if (choices.isNotEmpty()) {
                OrbitLabel("From this playlist", fonts, look.dim, Modifier.padding(top = 22.dp, bottom = 10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((index, url) in choices.withIndex()) {
                        val selected = url == picked
                        // The ring sits round the cover, never on it.
                        Box(
                            Modifier.size(CHOICE_SIZE + 8.dp).clip(CircleShape)
                                .border(2.dp, if (selected) look.accent else look.chip, CircleShape)
                                .clickable(enabled = !busy, role = Role.RadioButton) { onPick(url) }
                                .semantics { this.selected = selected; contentDescription = "Song cover ${index + 1}" }
                                .testTag("cover:choice:$index"),
                            contentAlignment = Alignment.Center,
                        ) { Disc(url, CHOICE_SIZE) }
                    }
                }
            }
            FlowRow(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OrbitPillButton(PlaylistCoverWords.PHOTO, onPhoto, fonts, look, Modifier.testTag("cover:photo"), enabled = !busy, icon = Icons.Rounded.Image)
                if (hasCustom) {
                    OrbitPillButton(PlaylistCoverWords.AUTOMATIC, onAutomatic, fonts, look, Modifier.testTag("cover:automatic"), enabled = !busy, icon = Icons.AutoMirrored.Rounded.Undo)
                }
            }
        }
        CosmoDialogActions("Save", onSave, "Cancel", onCancel, confirmEnabled = picked != null && !busy, dismissEnabled = true, busy = busy)
    }
}

@Composable
private fun PlaceStep(
    title: String,
    photo: ImageBitmap,
    busy: Boolean,
    error: String?,
    onSave: (CropSquare) -> Unit,
    onCancel: () -> Unit,
) {
    val look = orbitLook()
    val width = photo.width
    val height = photo.height
    var placement by remember(photo) { mutableStateOf(CoverPlacement()) }
    var viewport by remember { mutableFloatStateOf(0f) }
    fun zoom(by: Float) {
        if (viewport > 0f) placement = CoverCrop.gesture(placement, width, height, viewport, 0f, 0f, by, viewport / 2f, viewport / 2f)
    }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
        CosmoDialogHeading(title, "Place the photo", error ?: coverPlaceHint, messageIsError = error != null)
        Box(
            Modifier.padding(top = 18.dp).align(Alignment.CenterHorizontally).size(PLACE_SIZE).clip(CircleShape).background(look.chip)
                .onSizeChanged { size ->
                    val next = size.width.toFloat()
                    if (viewport > 0f && next != viewport) placement = CoverCrop.rescale(placement, viewport, next)
                    viewport = next
                }
                .pointerInput(photo) {
                    detectTransformGestures { centroid, pan, zoomBy, _ ->
                        if (viewport > 0f) placement = CoverCrop.gesture(placement, width, height, viewport, pan.x, pan.y, zoomBy, centroid.x, centroid.y)
                    }
                }
                .pointerInput(photo) {
                    // The mouse wheel zooms about the pointer, a notch at a time.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val notch = change.scrollDelta.y
                            if (notch == 0f || viewport <= 0f) continue
                            placement = CoverCrop.gesture(
                                placement, width, height, viewport, 0f, 0f, if (notch < 0f) 1.1f else 1f / 1.1f, change.position.x, change.position.y,
                            )
                            change.consume()
                        }
                    }
                }
                .semantics { contentDescription = "The photo in the cover circle" }
                .testTag("cover:place"),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val side = size.width
                val scale = side / minOf(width, height) * placement.zoom
                val shownW = width * scale
                val shownH = height * scale
                drawImage(
                    photo,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(width, height),
                    dstOffset = IntOffset((side / 2f + placement.offsetX - shownW / 2f).toInt(), (side / 2f + placement.offsetY - shownH / 2f).toInt()),
                    dstSize = IntSize(shownW.toInt(), shownH.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            OrbitPageButton(Icons.Rounded.Remove, "Zoom out", { zoom(1f / 1.25f) }, Modifier.testTag("cover:zoom-out"))
            OrbitPageButton(CosmoIcons.Plus, "Zoom in", { zoom(1.25f) }, Modifier.testTag("cover:zoom-in"))
        }
        CosmoDialogActions(
            "Save", { if (viewport > 0f) onSave(CoverCrop.square(placement, width, height, viewport)) }, "Cancel", onCancel,
            confirmEnabled = !busy && viewport > 0f, busy = busy,
        )
    }
}

/** Loading a picture, cutting the cover out of it and keeping it: the two copies, one for this device and one for the account. */
internal object CoverMaker {
    /** Device copy quality: the page shows it large. */
    private const val DEVICE_QUALITY = 88

    /** The account copy when even the lowest quality is too big at full size. */
    private const val FALLBACK_SIDE = 240

    suspend fun load(context: PlatformContext, model: Any, maxSide: Int = PHOTO_DECODE_PX): ImageBitmap? {
        val request = ImageRequest.Builder(context).data(model).size(maxSide).scale(Scale.FIT).precision(Precision.EXACT)
            .memoryCachePolicy(CachePolicy.DISABLED).forPixelAccess().build()
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image ?: return null
        return image.toImageBitmap().takeIf { it.width > 0 && it.height > 0 }
    }

    /** A song's cover, as it is (they are square): loaded large and kept whole. */
    suspend fun saveSongCover(context: PlatformContext, store: PlaylistCoverStore, browseId: String, url: String): Boolean {
        val image = load(context, url.artworkAt(CoverPixels.DEVICE_SIDE) ?: url) ?: return false
        return save(store, browseId, image, CoverCrop.square(CoverPlacement(), image.width, image.height, 1f))
    }

    suspend fun save(store: PlaylistCoverStore, browseId: String, image: ImageBitmap, square: CropSquare): Boolean = withContext(Dispatchers.Default) {
        val pixels = image.argbPixels()
        val w = image.width
        val h = image.height
        val big = CoverPixels.outputSide(square, CoverPixels.DEVICE_SIDE)
        val full = encodeCoverJpeg(CoverPixels.crop(pixels, w, h, square, big), big, DEVICE_QUALITY)
        val smallSide = CoverPixels.outputSide(square, CoverPixels.ACCOUNT_SIDE)
        val smallPixels = CoverPixels.crop(pixels, w, h, square, smallSide)
        val small = CoverJpeg.fitWithin(SyncPlaylistCovers.MAX_PICTURE_BYTES) { quality -> encodeCoverJpeg(smallPixels, smallSide, quality) }
            ?: FALLBACK_SIDE.coerceAtMost(smallSide).let { side -> encodeCoverJpeg(CoverPixels.crop(pixels, w, h, square, side), side, CoverJpeg.QUALITIES.last()) }
        withContext(Dispatchers.IO) { store.set(browseId, full, small) }
    }
}
