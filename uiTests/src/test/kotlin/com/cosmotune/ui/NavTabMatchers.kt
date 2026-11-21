package com.cosmotune.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText

/**
 * A tab of a mode's shared navigation (CosmoNavShell) named [name], in the bottom bar or the side rail alike. Each is a selectable
 * with the tab role; the chosen tab of the bottom bar shows its name as text, the others are their icon alone and carry the name as
 * their content description, and a rail item has both (its count, when it has one, follows the name in the description).
 */
fun hasNavTab(name: String): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
        (hasText(name, substring = false) or hasContentDescription(name, substring = false) or hasContentDescription("$name, ", substring = true))
