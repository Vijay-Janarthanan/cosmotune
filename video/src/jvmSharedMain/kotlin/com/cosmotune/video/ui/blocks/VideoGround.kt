package com.cosmotune.video.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.theme.cosmoGroundUnder

/**
 * The ground a sticky or floating row of a Video page is painted with: the app's own ground where the row stands
 * ([cosmoGroundUnder]), so what scrolls under it is not seen through it and no band of another colour shows.
 */
@Composable
internal fun Modifier.videoSolidGround(): Modifier = cosmoGroundUnder()
