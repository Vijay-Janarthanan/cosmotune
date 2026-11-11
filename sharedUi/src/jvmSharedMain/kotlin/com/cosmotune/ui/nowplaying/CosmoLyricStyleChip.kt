package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.lyrics.styles.LyricStyle
import com.cosmotune.lyrics.styles.LyricStyles
import com.cosmotune.lyrics.styles.LyricSurface
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.settings.Extras
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * The lyric style a song is drawn in, and the choice behind it: the one the listener made for every song, the one they gave this
 * song, and the style that comes of them ([LyricStyles.resolve]). Kept in the shared extras settings on every platform.
 */
@Stable
class LyricStyleChoice internal constructor(
    /** The style the song is drawn in. */
    val style: LyricStyle,
    /** Whether this song has a style of its own. */
    val forThisSong: Boolean,
    /** The styles this surface offers. */
    val offered: List<LyricStyle>,
    private val songId: String,
) {
    /** Chooses [pick]: for this song when it has a style of its own, else for every song. */
    fun choose(pick: LyricStyle) {
        if (forThisSong) Extras.settings.setLyricStyleFor(songId, pick.key) else {
            Extras.settings.setLyricStyle(pick.key)
            Extras.settings.setLyricStyleFor(songId, null)
        }
    }

    /** Gives this song the style it shows now as its own, or takes its own away so it follows the choice for every song. */
    fun setForThisSong(on: Boolean) {
        Extras.settings.setLyricStyleFor(songId, if (on) style.key else null)
    }
}

@Composable
fun rememberLyricStyleChoice(songId: String, lines: List<LyricLine>, surface: LyricSurface): LyricStyleChoice {
    val chosen by Extras.settings.lyricStyle.collectAsStateWithLifecycle()
    val songs by Extras.settings.lyricStyleSongs.collectAsStateWithLifecycle()
    val own = songs[songId]
    return remember(chosen, own, lines, surface, songId) {
        LyricStyleChoice(
            style = LyricStyles.resolve(LyricStyle.ofKey(chosen), LyricStyle.ofKey(own), lines, surface),
            forThisSong = LyricStyle.ofKey(own) != null,
            offered = LyricStyles.on(surface),
            songId = songId,
        )
    }
}

/**
 * The Style chip in the Lyrics row: the style's name, and a tap opens the six (an [OrbitMenu]), with a switch under them that keeps
 * the choice for this song alone.
 */
@Composable
internal fun LyricStyleChip(choice: LyricStyleChoice, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OrbitChip(
            choice.style.title, fonts, look, onClick = { open = true }, selected = open,
            leading = { Icon(CosmoIcons.Lyrics, null, modifier = Modifier.size(18.dp)) },
        )
        OrbitMenu(
            open = open, onDismiss = { open = false }, fonts = fonts, look = look, header = "Lyric style",
            items = styleMenuItems(choice),
        )
    }
}

/** The menu's lines: one per style (the one in force solid), then the switch for this song. */
internal fun styleMenuItems(choice: LyricStyleChoice): List<OrbitMenuItem> = buildList {
    for (style in choice.offered) {
        add(OrbitMenuItem(style.title, style.detail, selected = style == choice.style, testTag = "lyricStyle:${style.key}") { choice.choose(style) })
    }
    add(
        OrbitMenuItem(
            if (choice.forThisSong) "Just for this song" else "Keep for this song only",
            if (choice.forThisSong) "Tap to use the style for every song" else "Other songs keep their style",
            selected = choice.forThisSong, keepOpen = true, testTag = "lyricStyle:thisSong",
        ) { choice.setForThisSong(!choice.forThisSong) },
    )
}
