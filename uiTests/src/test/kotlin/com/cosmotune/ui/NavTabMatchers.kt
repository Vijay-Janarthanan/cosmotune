package com.cosmotune.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText

/**
 * A tab of a mode's shared navigation (CosmoNavShell) named [name], in the bottom bar or the side rail alike. Each is a selectable
 * with the tab role and a name in text or in the accessibility tree. An unread count can follow the name in the description.
 */
fun hasNavTab(name: String): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
        (hasText(name, substring = false) or hasContentDescription(name, substring = false) or hasContentDescription("$name, ", substring = true))
