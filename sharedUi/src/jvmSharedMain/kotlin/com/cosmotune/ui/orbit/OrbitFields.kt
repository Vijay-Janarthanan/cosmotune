package com.cosmotune.ui.orbit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * Public doors onto Orbit pieces that are internal to sharedUi, for the modes in other modules (video, manga). Each one only
 * calls the internal piece, so a change to the piece reaches every mode.
 */

/** The Orbit text field (the chip's pill, radius 22, at least 48 dp). [singleLine] false lets it grow with the text. */
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
    sentences: Boolean = false,
    minLines: Int = 1,
) = OrbitTextField(
    value, onValueChange, placeholder, fonts, look, modifier,
    error = error, secret = secret, singleLine = singleLine, sentences = sentences, minLines = minLines,
)

/** A dialog's pill action: 44 dp, [primary] the one peach action, the rest the quiet chip. */
@Composable
fun OrbitActionPill(
    label: String,
    onClick: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) = OrbitPillButton(label, onClick, fonts, look, modifier, primary, enabled, icon)

/** The ground of an Orbit sheet (a shade under a menu's). */
fun orbitSheetGroundColor(look: OrbitLook): Color = orbitSheetGround(look)

/** The ground of an Orbit menu, dialog or floating card. */
fun orbitFloatGroundColor(look: OrbitLook): Color = orbitFloatGround(look)

/** The peach action fill and its ink (dark: peach with a dark word; light: deep peach with white). */
fun orbitActionColors(look: OrbitLook): Pair<Color, Color> = orbitActionFill(look) to orbitActionInk(look)
