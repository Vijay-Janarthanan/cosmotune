package com.cosmotune.ui.components

import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * A filter chip in Orbit: [OrbitSelectChip], 40 dp, the ink at 8 % at rest and solid ink with the ground's colour for its words when
 * [selected]. [big] and [floating] are kept for the callers: Orbit's chips are one height and have no glass.
 *
 * It is a toggle for screen readers (`Role.Checkbox`) and plays the select haptic, unless [haptic] is false (a filter row passes
 * `haptic = !selected`, so a tap on the chip that is already chosen changes nothing and buzzes for nothing). It reserves a 48 dp
 * touch target.
 */
@Composable
fun CosmoChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    floating: Boolean = false,
    haptic: Boolean = true,
) {
    OrbitSelectChip(
        title = label,
        selected = selected,
        fonts = orbitFonts(),
        look = orbitLook(),
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics { role = Role.Checkbox; toggleableState = ToggleableState(selected) },
        haptic = haptic,
    )
}
