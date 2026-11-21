package com.cosmotune.ui.covers

import androidx.compose.runtime.Composable

/**
 * Opens the platform's own way of choosing a photo: Android's system photo picker (no permission needed), the desktop's file
 * chooser. [onChosen] gets what Coil loads the photo from (a content Uri, a file address); nothing when the person backs out.
 */
@Composable internal expect fun rememberCoverPhotoChooser(onChosen: (Any) -> Unit): () -> Unit

/** Packed ARGB pixels, [side] x [side], as a JPEG of [quality] (0..100). */
internal expect fun encodeCoverJpeg(argb: IntArray, side: Int, quality: Int): ByteArray

/** What the place step says about moving and zooming: a pinch on the phone, the mouse wheel on the desktop. */
internal expect val coverPlaceHint: String
