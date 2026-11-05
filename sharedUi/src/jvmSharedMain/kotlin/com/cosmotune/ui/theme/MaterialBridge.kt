package com.cosmotune.ui.theme

import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.cosmotune.design.MaterialShapeRadii
import com.cosmotune.design.TypeScale

/**
 * The Material bridge, second half (the first is [toColorScheme]): with the look on, every screen that has not been redrawn
 * yet still asks Material for its type, corners and ripple, and gets the new look's. [CosmoLookTheme] hands these to its
 * `MaterialTheme(...)` and to `LocalRippleConfiguration` when the switch is on, so the old screens need no edit.
 *
 * What the bridge does not do:
 *  - Status and navigation bar icons already follow the theme flag (`SystemBarIcons(dark)`).
 *  - Text selection colours come from `primary` (the accent) through the colour bridge ([toColorScheme]).
 *  - Buttons, switches and sliders keep Material's full-round geometry: that comes from component tokens, not from `Shapes`.
 *  - Colours that a call site passes explicitly win over everything here.
 *  - Line height is the Latin 1.42; text in Devanagari, Tamil and the other Indian scripts needs 1.5, and a Material `Text`
 *    that picks its style from the theme cannot know its text. The redrawn screens ask [CosmoType.forText].
 */

/**
 * The Material type scale in the new look. [base] is the legacy scale (SF Pro on the phone, the desktop's copy of it).
 *
 *  - Display and headline styles, and `titleLarge`, are the fluid scale of [CosmoType] and replace the legacy style.
 *  - The other title styles, the body styles and the label styles keep the legacy style's own `fontSize` (old rows, chips and
 *    segmented controls are laid out for those sizes) and change family, weight, tracking and line height only.
 *    Where the design draws no tracking (`titleMedium`, the body styles) it is zero, not whatever the legacy style carried
 *    (the phone's -0.2 sp, Material's +0.5 sp on the desktop); where it draws no line height (`titleSmall`, the labels) the
 *    legacy line height stays.
 *
 * Weights resolve to the instances that are bundled (Hanken Grotesk 400, 500, 600, 650, 680, 700; Archivo 300 to 820), so an
 * old `FontWeight.W800` copy falls to 700.
 */
fun CosmoType.toMaterialTypography(base: Typography): Typography = base.copy(
    displayLarge = base.displayLarge.takingThe(display(TypeScale.DISPLAY_M)),
    displayMedium = base.displayMedium.takingThe(display(TypeScale.DISPLAY_S)),
    displaySmall = base.displaySmall.takingThe(title(TypeScale.TITLE_L)),
    headlineLarge = base.headlineLarge.takingThe(display(TypeScale.DISPLAY_S)),
    headlineMedium = base.headlineMedium.takingThe(title(TypeScale.TITLE_M)),
    headlineSmall = base.headlineSmall.takingThe(title(TypeScale.TITLE_M)),
    titleLarge = base.titleLarge.takingThe(title(TypeScale.TITLE_S)),
    titleMedium = base.titleMedium.restyled(this, weight = 650, lineHeightFactor = TITLE_LINE, tracking = 0.sp),
    titleSmall = base.titleSmall.restyled(this, weight = 600, tracking = LABEL_TRACKING),
    bodyLarge = base.bodyLarge.restyled(this, weight = 400, lineHeightFactor = BODY_LINE, tracking = 0.sp),
    bodyMedium = base.bodyMedium.restyled(this, weight = 400, lineHeightFactor = BODY_LINE, tracking = 0.sp),
    bodySmall = base.bodySmall.restyled(this, weight = 400, lineHeightFactor = BODY_LINE, tracking = 0.sp),
    labelLarge = base.labelLarge.restyled(this, weight = 680, tracking = LABEL_TRACKING),
    labelMedium = base.labelMedium.restyled(this, weight = 600, tracking = LABEL_TRACKING),
    labelSmall = base.labelSmall.restyled(this, weight = 700, tracking = LABEL_TRACKING),
)

/** `--lh-body`. */
private const val BODY_LINE = 1.42f

/** `.mrow .ttl`: weight 650, size 15.5, line height 1.3. */
private const val TITLE_LINE = 1.3f

/** `--tr-label`, 0.01 em. */
private val LABEL_TRACKING: TextUnit = 0.01.em

/** [new] in place of this style, keeping what the new style leaves open (colour, alignment, line breaking) and zeroing the tracking it does not draw. */
private fun TextStyle.takingThe(new: TextStyle): TextStyle =
    merge(new).copy(letterSpacing = if (new.letterSpacing.isSpecified) new.letterSpacing else 0.sp)

/** This style's own size in the text family, at [weight], with the tracking and (when [lineHeightFactor] is given) the line height of the design. */
private fun TextStyle.restyled(type: CosmoType, weight: Int, tracking: TextUnit, lineHeightFactor: Float? = null): TextStyle {
    val size = if (fontSize.isSp) fontSize else type.size(TypeScale.BODY)
    return merge(
        TextStyle(
            fontFamily = type.textFamily,
            fontWeight = FontWeight(weight),
            fontSize = size,
            letterSpacing = tracking,
            lineHeight = if (lineHeightFactor != null) size * lineHeightFactor else lineHeight,
        ),
    )
}

/**
 * The Material `Shapes` in the new look: the five B+C radii (menu 22, chip 12, card 24, tile 28, sheet 36) as the circular
 * radii that read the same ([MaterialShapeRadii]). Dialogs and bottom sheets are `extraLarge` (28), menus and text fields
 * `extraSmall` (17), cards `medium` (18). Material's `Shapes` takes corner-based shapes only, so the superellipse itself
 * stays in the redrawn screens (`CosmoShapes`).
 */
val CosmoMaterialShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(MaterialShapeRadii.EXTRA_SMALL.dp),
    small = RoundedCornerShape(MaterialShapeRadii.SMALL.dp),
    medium = RoundedCornerShape(MaterialShapeRadii.MEDIUM.dp),
    large = RoundedCornerShape(MaterialShapeRadii.LARGE.dp),
    extraLarge = RoundedCornerShape(MaterialShapeRadii.EXTRA_LARGE.dp),
)

/**
 * The ripple of the old screens in the new look: the state layers of the B controls (`components-v3bc.css`: `.b:hover::before`
 * 0.08, `.b:active::before` 0.12; focus is drawn as pressed; dragged is Material's own 0.16). The colour stays unspecified, so
 * the ripple takes the content colour, as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
val CosmoRipple: RippleConfiguration = RippleConfiguration(
    color = Color.Unspecified,
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.12f, hoveredAlpha = 0.08f, pressedAlpha = 0.12f),
)
