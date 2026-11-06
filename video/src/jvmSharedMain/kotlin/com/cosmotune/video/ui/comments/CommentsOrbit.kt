package com.cosmotune.video.ui.comments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * A quiet text action of a comment ("Read more", "Reply", "3 replies", "Try again"): the chip face in the page's peach accent, in a
 * 32 dp-high target. The words are the button's name.
 */
@Composable
internal fun CommentLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Box(
        modifier.heightIn(min = 32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = fonts.chip, color = look.accent, maxLines = 1) }
}
