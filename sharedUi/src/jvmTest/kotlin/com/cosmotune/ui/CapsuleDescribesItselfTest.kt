package com.cosmotune.ui

import com.cosmotune.design.CapsuleGeometry
import com.cosmotune.design.PlayerBarMetrics
import com.cosmotune.design.ShellMetrics
import com.cosmotune.ui.orbit.OrbitBarMetrics
import com.cosmotune.ui.shell.COVER_SIZE
import com.cosmotune.ui.orbit.OrbitMiniMetrics
import com.cosmotune.ui.theme.CapsuleLabel
import com.cosmotune.ui.theme.CosmoRadii
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capsule describes itself (`CapsuleGeometry`): the numbers the player's grow starts from are the ones the capsules are
 * built of, and the overlay reads nothing about a capsule that the capsule did not say. The maths is `CapsuleGeometryTest`'s
 * (in `:shared`); a real composition of the grow is `PlayerOverlayTest`'s.
 */
class CapsuleDescribesItselfTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun shell(name: String): String =
        File(root, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/shell/$name").readText().replace("\r\n", "\n")

    private fun code(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    // ---- the numbers are the components' ---------------------------------------------------------------------------------

    @Test fun thePhoneGeometryIsTheSmallPlayersTokens() {
        val g = OrbitMiniMetrics.geometry
        assertEquals(OrbitMiniMetrics.DISC_DP / 2f, g.coverRadiusDp, 0f, "the cover is a disc")
        assertEquals(COVER_SIZE.value, g.coverSizeDp, 0f)
        assertEquals(OrbitMiniMetrics.RADIUS_DP, g.cornerRadiusDp, 0f)
        assertEquals((OrbitMiniMetrics.HEIGHT_DP - OrbitMiniMetrics.DISC_DP) / 2f, g.coverInsetTopDp, 0f, "centred in the card's height")
        assertEquals(OrbitMiniMetrics.PAD_START_DP + OrbitMiniMetrics.RING_DP + OrbitMiniMetrics.GAP_DP, g.textStartDp, 0f, "the words start after the orbit")
        assertEquals(OrbitMiniMetrics.END_PAD_DP + OrbitMiniMetrics.BUTTON_DP + 8f, g.textEndDp, 0f, "and end before next and the bite")
    }

    @Test fun theDesktopBarsGeometryIsTheBarsTokens() {
        val g = OrbitBarMetrics.geometry(OrbitBarMetrics.planFor(PlayerBarMetrics.Tier.FULL), 908f)
        assertEquals(OrbitBarMetrics.DISC_DP / 2f, g.coverRadiusDp, 0f, "the cover is a disc")
        assertEquals(OrbitBarMetrics.DISC_DP, g.coverSizeDp, 0f)
        assertEquals(OrbitBarMetrics.RADIUS_DP, g.cornerRadiusDp, 0f)
        assertEquals((OrbitBarMetrics.HEIGHT_DP - OrbitBarMetrics.DISC_DP) / 2f, g.coverInsetTopDp, 0f, "centred in the bar's height")
        assertEquals(OrbitBarMetrics.PAD_START_DP + OrbitBarMetrics.RING_DP + OrbitBarMetrics.GAP_DP, g.textStartDp, 0f, "the words start after the orbit")
    }

    @Test fun aLabelThatSaysNothingIsThePhonesAsItAlwaysWas() {
        val label = CapsuleLabel("A song", "An artist", null)
        assertEquals(CapsuleGeometry.PHONE, label.geometry)
        assertEquals(null, label.titleStyle)
        assertEquals(null, label.artistStyle)
    }

    // ---- who says what ---------------------------------------------------------------------------------------------------

    @Test fun theMiniCapsuleSaysItsOwnGeometryAndIsBuiltOfIt() {
        val src = code(shell("CosmoBottomChrome.kt"))
        assertTrue(src.contains("CapsuleLabel(song.title, song.artist, coverUrl, OrbitMiniMetrics.geometry, capsuleTitleStyle(type.textFamily), capsuleArtistStyle(type.textFamily))"))
        assertTrue(src.contains("padding(start = OrbitMiniMetrics.PAD_START_DP.dp, end = OrbitMiniMetrics.END_PAD_DP.dp)") && src.contains("Spacer(Modifier.width(OrbitMiniMetrics.GAP_DP.dp))"), "the layout is made of the geometry's insets")
        assertTrue(src.contains("COVER_SIZE = OrbitMiniMetrics.DISC_DP.dp"))
    }

    @Test fun theDesktopBarSaysItsOwnGeometryAtItsOwnWidthAndPlan() {
        val src = code(shell("CosmoPlayerBar.kt"))
        assertTrue(src.contains("OrbitBarMetrics.geometry(plan, maxWidth.value)"))
        assertTrue(src.contains("remember(song.title, song.artist, coverUrl, plan, maxWidth, titleStyle, artistStyle)"), "a new width or plan is a new label")
        assertTrue(src.contains("type.forText(barTitleStyle(fonts), song.title)"), "the words travel in the styles they sit in")
    }

    @Test fun theOverlayTakesEverythingAboutTheCapsuleFromTheCapsule() {
        val raw = shell("CosmoPlayerOverlay.kt")
        val src = code(raw)
        // nothing of the phone's is left in it but the default a capsule that says nothing gets
        for (phoneOnly in listOf("CAPSULE_RADIUS_DP", "CAPSULE_TEXT_START", "CAPSULE_TEXT_END", "CAPSULE_PADDING", "COVER_SIZE", "coverMini")) {
            assertFalse(src.contains(phoneOnly), "$phoneOnly is the phone capsule's, and the overlay must not assume it")
        }
        assertEquals(2, Regex("""CapsuleGeometry\.PHONE""").findAll(src).count(), "the phone's is the default of the card and of the travelling layer, in two places")
        assertTrue(src.contains("(shell.capsuleLabel?.geometry ?: CapsuleGeometry.PHONE).cornerRadiusDp * density"), "the card's corners, read when drawing")
        for (fn in listOf("Morph.insetFrom(", "Morph.coverScale(", "Morph.coverRadius(", "Morph.textWidth(")) assertTrue(src.contains(fn), "$fn is the tested maths")
        assertTrue(src.contains("capsule?.height"), "the words are centred in the capsule's own height, not 64 dp")
        assertTrue(src.contains("label.titleStyle ?: capsuleTitleStyle(type.textFamily)"), "and set in the capsule's own styles")
    }
}
