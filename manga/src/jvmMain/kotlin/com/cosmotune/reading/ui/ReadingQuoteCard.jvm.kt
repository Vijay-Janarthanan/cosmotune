package com.cosmotune.reading.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap

internal actual fun encodeReadingPng(image: ImageBitmap): ByteArray =
    requireNotNull(org.jetbrains.skia.Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)) { "The picture could not be made." }.bytes
