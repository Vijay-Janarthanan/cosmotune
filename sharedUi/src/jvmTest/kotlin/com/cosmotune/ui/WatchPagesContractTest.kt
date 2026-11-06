package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Watch's pages promise a person who presses things: nothing drawn as a button does nothing. */
class WatchPagesContractTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun read(path: String) = File(root, path).readText().replace(Regex("""\s+"""), " ")

    @Test fun theCastArePicturesNotButtonsThatDoNothing() {
        val title = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/watch/WatchTitlePage.kt")
        assertTrue("OrbitPortrait(person.name, person.photoUrl, 96.dp, fonts, look, onClick = null)" in title, "a cast portrait has no click")
        assertFalse(Regex("""OrbitPortrait\([^)]*onClick = \{\s*\}""").containsMatchIn(title), "no portrait with an empty click")
        val portrait = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitBrowse.kt")
        assertTrue("onClick: (() -> Unit)?," in portrait, "the portrait's click may be left out")
        assertTrue(".then(if (onClick != null) Modifier.longPressMenuClickable(onClick = onClick, onLongClick = onLongClick)" in portrait, "and then it is not clickable")
    }
}
