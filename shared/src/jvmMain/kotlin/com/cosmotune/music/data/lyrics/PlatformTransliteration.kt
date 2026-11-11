package com.cosmotune.music.data.lyrics

internal actual fun transliterateOffline(text: String, transform: String): String =
    com.ibm.icu.text.Transliterator.getInstance(transform).transliterate(text)
