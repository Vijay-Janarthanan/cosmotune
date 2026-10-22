package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What no screenshot harness can check about the settings in the new look, read from the source: the phone's ten building blocks
 * and the desktop's three rows hand over to ours as their first statement (so with the look off the old code runs untouched), by
 * name, with every parameter; our blocks keep the old parameter lists; the colour intensity reaches the palette; and the blocks read
 * the live palette only while drawing.
 */
class SettingsBlocksContractTest {
    private val sheetPath = "app/src/main/java/com/cosmotune/music/ui/screens/SettingsSheet.kt"
    private val desktopPath = "desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"
    private val blocksPath = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/settings/CosmoSettingsBlocks.kt"
    private val themePath = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/theme/CosmoLookTheme.kt"
    private val loaderPath = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/theme/CoverPaletteLoader.kt"
    private val phoneRowsPath = "app/src/main/java/com/cosmotune/ui/CosmoLookSettingsRows.kt"
    private val desktopTogglesPath = "desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoLookDesktopToggles.kt"

    private val sheet = SourceContract.read(sheetPath)
    private val desktop = SourceContract.read(desktopPath)
    private val blocks = SourceContract.read(blocksPath)

    private val guard = "if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings."

    /** The phone's functions in `SettingsSheet.kt` and the block each one hands over to. */
    private val phone = listOf(
        "SettingsGroup" to "CosmoSettingsGroup",
        "RowDivider" to "CosmoRowDivider",
        "SettingsRow" to "CosmoSettingsRow",
        "SettingsSubRow" to "CosmoSettingsSubRow",
        "Badge" to "CosmoBadge",
        "Chevron" to "CosmoChevron",
        "SliderRow" to "CosmoSliderRow",
        "DestructiveRow" to "CosmoDestructiveRow",
        "SegmentedControl" to "CosmoSettingsSegmented",
        "AccountCard" to "CosmoProfileCard",
    )

    private fun firstOfPhone(name: String): String = SourceContract.firstStatement(SourceContract.functionBody(sheet, name))

    // ---- the phone ----------------------------------------------------------------------------------------------------------

    @Test fun eachOfTheTenPhoneFunctionsHandsOverToItsBlockAsItsFirstStatement() {
        for ((old, new) in phone) {
            val statement = firstOfPhone(old)
            assertTrue(statement.startsWith("$guard$new("), "$old: the first statement is the delegate to $new, not: $statement")
            val body = SourceContract.functionBody(sheet, old)
            assertTrue(SourceContract.code(body).length > statement.length + 20, "$old: the old body follows the delegate, untouched")
        }
    }

    @Test fun theDelegatesNameEveryParameterOfTheFunctionTheyReplace() {
        for ((old, new) in phone) {
            val names = SourceContract.names(SourceContract.parameters(sheet, old))
            val statement = firstOfPhone(old)
            if (old == "AccountCard") {
                // the card's arguments are built from the account: every parameter is used, by name
                for (name in names) assertTrue(Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(statement), "AccountCard: `$name` is used in the delegate")
            } else {
                SourceContract.assertPassesEveryParameter(statement.substringAfter("$new("), names)
            }
        }
    }

    @Test fun ourBlocksKeepTheOldParameterLists() {
        for ((old, new) in phone) {
            if (old == "AccountCard") continue
            val theirs = SourceContract.parameters(sheet, old)
            val ours = SourceContract.parameters(blocks, new)
            if (old == "SegmentedControl") {
                // one more, with a default: the small variant of the control that stands under a row
                assertEquals(theirs, ours.take(theirs.size), "$new starts with the old parameters")
                assertEquals(listOf("compact: Boolean = false"), ours.drop(theirs.size))
            } else {
                assertEquals(theirs, ours, "$new has the parameter list of $old")
            }
        }
    }

    @Test fun theAccountCardKeepsItsSignedOutTextsAndItsClickRule() {
        val statement = firstOfPhone("AccountCard")
        assertTrue(statement.contains("R.string.signed_in") && statement.contains("R.string.not_signed_in"), "the name when the account has none")
        assertTrue(statement.contains("R.string.youtube_music_account") && statement.contains("R.string.tap_to_sign_in_google"), "the subtitle when there is no email")
        assertTrue(statement.contains("account?.thumbnailUrl"), "the avatar")
        assertTrue(statement.contains("signedIn && onClick != null -> onClick") && statement.contains("!signedIn -> onSignIn") && statement.contains("else -> null"), "signed in with a click opens it, signed out signs in")
        assertTrue(statement.contains("grouped = grouped"))
        val oldCard = SourceContract.code(SourceContract.functionBody(sheet, "AccountCard"))
        assertTrue(oldCard.contains("Chevron()") && oldCard.contains("MaterialTheme.colorScheme.surfaceVariant"), "the old card is still there for the look off")
    }

    @Test fun theAppearanceGroupFindsTheLookByTheWordIntensity() {
        val line = sheet.lines().first { it.contains("\"new look\"") }
        assertTrue(line.contains("\"colours follow the cover\"") && line.contains("\"preview the look\"") && line.contains("\"colour\"") && line.contains("\"intensity\""), line)
    }

    // ---- the desktop -----------------------------------------------------------------------------------------------------------

    private val visibility = "if (!settingsRowVisible(title, subtitle)) return"

    @Test fun theDesktopRowsHandOverRightAfterTheirVisibilityCheck() {
        for (name in listOf("SettingsNavigationRow", "SettingsToggle", "SettingsRow")) {
            val body = SourceContract.functionBody(desktop, name)
            assertEquals(visibility, SourceContract.firstStatement(body), "$name starts by asking whether the row is visible")
            val rest = body.substringAfter(visibility)
            val statement = SourceContract.firstStatement(rest)
            assertTrue(statement.startsWith("${guard}CosmoSettingsRow("), "$name: the statement after the visibility check is the delegate, not: $statement")
            val names = SourceContract.names(SourceContract.parameters(desktop, name))
            for (parameter in names) assertTrue(Regex("""\b${Regex.escape(parameter)}\b""").containsMatchIn(statement), "$name: the delegate uses `$parameter`")
            assertTrue(SourceContract.code(rest).length > statement.length + 20, "$name: the old body follows")
        }
    }

    @Test fun theDesktopRowsPassWhatTheOldRowsShowed() {
        val navigation = SourceContract.firstStatement(SourceContract.functionBody(desktop, "SettingsNavigationRow").substringAfter(visibility))
        SourceContract.assertPassesEveryParameter(navigation, listOf("title", "subtitle", "onClick"))
        val toggle = SourceContract.firstStatement(SourceContract.functionBody(desktop, "SettingsToggle").substringAfter(visibility))
        assertTrue(toggle.contains("onClick = { onCheckedChange(!checked) }"), "the whole row toggles")
        assertTrue(toggle.contains("trailing = { com.cosmotune.ui.components.CosmoSwitch(checked = checked, onCheckedChange = onCheckedChange) }"), "the switch is the look's own")
        val row = SourceContract.firstStatement(SourceContract.functionBody(desktop, "SettingsRow").substringAfter(visibility))
        SourceContract.assertPassesEveryParameter(row, listOf("icon", "title", "subtitle", "onClick"))
    }

    @Test fun theDesktopGroupIsOneTileUnderItsNameAndKeepsItsFilter() {
        val body = SourceContract.code(SourceContract.functionBody(desktop, "SettingsGroup"))
        val flat = body.replace(Regex("\\s+"), " ")
        assertTrue(flat.contains("com.cosmotune.ui.settings.CosmoSettingsGroup(header = title.takeIf { visible }, topSpacing = 0.dp)"), "the group's name only while it is visible, no extra room above")
        val tile = flat.substringAfter("CosmoSettingsGroup(").substringBefore("return@Column")
        assertTrue(tile.contains("LocalSettingsQuery provides if (wholeGroup) \"\" else query") && tile.contains("LocalSettingsMatches provides matched") && tile.contains("content()"), "the same filter around the same content")
        assertEquals(2, Regex("LocalSettingsQuery provides").findAll(flat).count(), "the old branch is still there")
        assertTrue(flat.contains("if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) { "), "only while the look is on")
        assertTrue(flat.contains("title.uppercase()"), "the old heading is the look-off branch")
    }

    // ---- the intensity -------------------------------------------------------------------------------------------------------------

    @Test fun theIntensityIsKeptAndReachesTheEnginesSpec() {
        val loader = SourceContract.read(loaderPath)
        assertTrue(SourceContract.parameters(loader, "load").contains("intensity: Double = 1.0"), "Now Playing's call without an intensity keeps working")
        val body = SourceContract.code(SourceContract.functionBody(loader, "load"))
        assertTrue(body.contains("intensity") && body.substringBefore("cache[key]?.let").contains("intensity"), "a palette is cached per intensity")
        // P4-19: the setting moves the engine's ceilings too (`forIntensity`), not only the budget
        assertTrue(body.contains("spec = CoverPaletteSpec.forIntensity(intensity)"))
        for (store in listOf(
            "sharedUi/src/androidMain/kotlin/com/cosmotune/ui/settings/CosmoLookStore.android.kt",
            "sharedUi/src/jvmMain/kotlin/com/cosmotune/ui/settings/CosmoLookStore.jvm.kt",
        )) {
            val text = SourceContract.read(store)
            assertTrue(text.contains("override fun getInt(") && text.contains("override fun putInt("), "$store keeps the intensity")
        }
    }

    @Test fun theFollowerLoadsAgainWhenTheIntensityChanges() {
        val body = SourceContract.code(SourceContract.functionBody(SourceContract.read(themePath), "PaletteFollower"))
        assertTrue(body.contains("shell.settings.intensity"), "it listens to the setting")
        assertTrue(body.contains("Intensity.multiplier("), "and turns the index into the multiplier")
        assertEquals(2, Regex("""LaunchedEffect\([^)]*\bintensity\)""").findAll(body).count(), "both effects restart when it changes")
        assertEquals(2, Regex("""CoverPaletteLoader\.load\([^)]*\bintensity\)""").findAll(body).count(), "both loads pass it")
        assertTrue(body.contains("shownIntensity != intensity"), "a changed intensity is shown as it is, past the same-album settle")
    }

    @Test fun thePhoneAndDesktopHaveTheirIntensityRow() {
        val phoneRows = SourceContract.code(SourceContract.read(phoneRowsPath))
        assertTrue(phoneRows.contains("cosmo_intensity_summary") && phoneRows.contains("CosmoSettingsSegmented(") && phoneRows.contains("compact = true"))
        assertTrue(phoneRows.contains("val intensityLive = newLook && coverColours") && phoneRows.contains("enabled = intensityLive"), "live only while the look is on and the colours follow the cover")
        assertTrue(phoneRows.contains("CosmoSwitch(") && phoneRows.contains("Switch(") && phoneRows.contains("lookOn"), "the look's switch while the look is on, Material's while it is off")
        val desktopRows = SourceContract.code(SourceContract.read(desktopTogglesPath))
        assertTrue(desktopRows.contains("CosmoSettingsSegmented(") && desktopRows.contains("settings::setIntensity") && desktopRows.contains("enabled = coverColours"))
        assertTrue(desktopRows.contains("CosmoButton(") && desktopRows.contains("CosmoButtonKind.TONAL") && desktopRows.contains("CosmoButtonSize.SMALL"), "the preview button is the tonal small B button")
        assertFalse(desktopRows.contains("FilledTonalButton"))
    }

    // ---- the blocks -----------------------------------------------------------------------------------------------------------------

    /** The index of the `}` that closes the `{` at [open]. */
    private fun closing(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return text.length
    }

    @Test fun liveColoursAreReadOnlyInDrawPhaseLambdas() {
        val text = SourceContract.code(blocks)
        val openers = listOf("drawBehind {", "onDrawBehind {", "drawWithContent {", "drawWithCache {", "graphicsLayer {").flatMap { opener ->
            Regex(Regex.escape(opener)).findAll(text).map { it.range.last }.toList()
        }.sorted()
        var from = 0
        var found = 0
        while (true) {
            val at = text.indexOf(".live(", from)
            if (at < 0) break
            val inside = openers.filter { it < at }.any { closing(text, it) > at }
            assertTrue(inside, "the blocks read a live colour outside a draw-phase lambda, near char $at: ${text.substring(maxOf(0, at - 60), at + 30)}")
            found++
            from = at + 1
        }
        assertTrue(found >= 6, "the blocks do read their colours while drawing ($found)")
    }

    @Test fun noBrushOrLiveColourIsRememberedAtCreation() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush\.|SolidColor|\.live\(|cssGradient|Fill\()""")
        val bad = remembering.find(SourceContract.code(blocks))
        assertTrue(bad == null, "the blocks remember a brush or a live colour: ${bad?.value}")
    }

    @Test fun theGroupFindsItsTilesFromTheDividersAndTheTilesComeFromTheDesign() {
        val code = SourceContract.code(blocks)
        assertTrue(code.contains("GroupSegments.of("), "the tiles are the pure rectangles between the gaps")
        assertTrue(code.contains("height(GroupSegments.GAP_DP.dp)") && code.contains(".onPlaced { tiles?.dividerPlaced(key, it) }"), "a divider is a 3 dp spacer that reports where it was placed")
        assertTrue(code.contains("CoverField.SURFACE_HIGH") && code.contains("CoverField.SURFACE)") && code.contains("TILE_TOP_MIX = 0.4f"), "the tile fill is mix(surfaceHigh, surface, 0.4) to surface")
        assertTrue(code.contains("Brush.verticalGradient("), "at 180 degrees")
        assertTrue(code.contains("GroupSegments.drawnRadius(GroupSegments.OUTER_RADIUS_DP)") && code.contains("GroupSegments.drawnRadius(GroupSegments.INNER_RADIUS_DP)"), "circular corners at the radius over 1.3")
    }

    @Test fun theRowsAreButtonsWithAFocusRingAndTheSwitchRowIsASwitch() {
        val code = SourceContract.code(blocks)
        assertTrue(code.contains("Role.Button") && code.contains("Role.Switch") && code.contains("Role.Tab"))
        assertTrue(code.contains("selectableGroup()"), "a segmented control is a group of tabs")
        assertTrue(code.contains("CoverField.ACCENT_STRONG") && code.contains("innerRing("), "a row shows the keyboard focus, along the inside of its edge")
        assertTrue(code.contains("cosmoFocusRing("), "a segment shows the keyboard focus")
        assertTrue(code.contains("heading()"), "a group's header is a heading")
        assertTrue(code.contains("STATE_LAYER_PRESSED"), "a row has the state layer")
        assertTrue(code.contains("DISABLED_ALPHA"), "disabled is 38 %")
        assertTrue(code.contains("MaterialTheme.colorScheme.error"), "sign out takes the theme's error colour")
    }

    @Test fun theBlocksUseTheDesignsNumbers() {
        val code = SourceContract.code(blocks)
        for (fragment in listOf(
            "ROW_MIN_HEIGHT = 64.dp", "ROW_PADDING_H = 16.dp", "ROW_PADDING_V = 10.dp", "ROW_GAP = 14.dp", "LEAD_SIZE = 40.dp", "LEAD_ICON = 20.dp",
            "LEAD_RADIUS = 14.dp", "PROFILE_RADIUS = 26.dp", "AVATAR_SIZE = 48.dp", "BADGE_HEIGHT = 18.dp", "DESTRUCTIVE_MIN_HEIGHT = 52.dp",
            "HEADER_TOP = 22.dp", "HEADER_BOTTOM = 8.dp", "textStart: Dp = 70.dp", "MINI_SEGMENT_HEIGHT_DP = 32f",
        )) assertTrue(code.contains(fragment), "the blocks have $fragment")
        assertTrue(code.contains("type.settings(12f, 700") && code.contains("type.settings(17f, 700") && code.contains("type.settings(11.5f, 700"), "header, name and badge sizes")
        assertTrue(code.contains("settings(15.5f, TITLE_WEIGHT") && code.contains("TITLE_WEIGHT = 650"), "titles are 15.5 sp, weight 650")
        assertTrue(code.contains("maxLines = 5"), "a subtitle may take five lines")
    }
}
