package com.cosmotune.music.data.scrobbling

/**
 * Returns the first credited artist for services that do not handle joint
 * artist credits well. The original value is preserved when no separator is
 * present so names such as "AC/DC" remain intact, and a band whose own name has
 * a comma or an ampersand ("Earth, Wind & Fire") is never cut.
 */
internal fun String.primaryArtist(): String =
    com.cosmotune.music.data.model.ArtistIdentity.split(this, PRIMARY_ARTIST_SEPARATOR, keepEmpty = true).first().ifBlank { this }

private val PRIMARY_ARTIST_SEPARATOR = Regex("""\s*,\s*|\s+&\s+|\s+＆\s+""")
