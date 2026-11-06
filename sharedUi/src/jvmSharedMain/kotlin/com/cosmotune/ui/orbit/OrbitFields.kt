package com.cosmotune.ui.orbit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Orbit text field for other modules (the kit's own [OrbitTextField] is internal): the chip's pill, the [placeholder] in the dim
 * ink while empty, [error] rings it, [secret] hides what is typed. [singleLine] false with [minLines] makes it a taller box for pasted text.
 */
@Composable
fun OrbitInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    fonts: OrbitFonts = orbitFonts(),
    look: OrbitLook = orbitLook(),
    modifier: Modifier = Modifier,
    error: Boolean = false,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
) = OrbitTextField(value, onValueChange, placeholder, fonts, look, modifier, error, secret, singleLine, minLines)
