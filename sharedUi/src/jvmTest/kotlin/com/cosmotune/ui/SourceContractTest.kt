package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The helper of the contract tests, checked on the real Now Playing switch and on small samples of what it has to read. */
class SourceContractTest {

    private val oldScreenPath = "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt"
    private val switchPath = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/NowPlayingSwitch.kt"

    // ---- on the real switch ---------------------------------------------------------------------------------------------

    @Test fun theRootIsTheRepositoryRoot() {
        assertTrue(SourceContract.root.resolve("settings.gradle.kts").isFile)
        assertFalse(SourceContract.read("FORK.md").contains("\r"), "line endings are turned into \n")
    }

    @Test fun nowPlayingSwitchHasMoreThanFortyParametersAndTheOldScreensList() {
        val old = SourceContract.parameters(SourceContract.read(oldScreenPath), "NowPlayingScreen")
        val switch = SourceContract.parameters(SourceContract.read(switchPath), "NowPlayingSwitch")
        assertTrue(switch.size > 40, "the switch's list was read (${switch.size})")
        assertEquals(old, switch)
        assertEquals(old.size, SourceContract.names(switch).toSet().size, "every name is its own")
        assertEquals("song", SourceContract.names(switch).first())
        assertEquals("modifier", SourceContract.names(switch).last())
    }

    @Test fun bothBranchesOfTheSwitchPassEveryParameterByName() {
        val src = SourceContract.read(switchPath)
        val names = SourceContract.names(SourceContract.parameters(src, "NowPlayingSwitch"))
        val statement = SourceContract.firstStatement(SourceContract.functionBody(src, "NowPlayingSwitch"))
        assertTrue(statement.startsWith("if (LocalCosmoShell.current != null) {"), "the switch is its one statement: $statement")
        assertTrue(statement.endsWith("}"), "and the whole if/else is read, to its closing brace")
        val newBranch = statement.substringBefore("} else {")
        val oldBranch = statement.substringAfter("} else {")
        assertTrue(newBranch.contains("CosmoNowPlaying("))
        assertTrue(oldBranch.contains("NowPlayingScreen("))
        SourceContract.assertPassesEveryParameter(newBranch, names)
        SourceContract.assertPassesEveryParameter(oldBranch, names)
    }

    // ---- on samples -----------------------------------------------------------------------------------------------------

    private val sample = """
        /** Docs that mention fun nope(a: Int) and { braces. */
        @Composable
        fun Thing(
            title: String,
            onOpen: (Int) -> Unit = {},
            items: Map<String, List<Int>> = emptyMap(), // the items, with a comma, in a comment
            modifier: Modifier = Modifier,
        ) {
            if (LocalCosmoShell.current != null) return com.cosmotune.pkg.CosmoThing(
                title = title, onOpen = onOpen,
                items = items, modifier = modifier,
            )
            val text = "a { brace in a string, a quote \" and ' too"
            other()
        }

        fun <T> List<T>.second(index: Int): T {
            return this[index]
        }

        fun expression(a: Int) = a + 1
    """.trimIndent()

    @Test fun parametersAreReadWithTheirDefaultsAndWhiteSpaceCollapsed() {
        assertEquals(
            listOf("title: String", "onOpen: (Int) -> Unit = {}", "items: Map<String, List<Int>> = emptyMap()", "modifier: Modifier = Modifier"),
            SourceContract.parameters(sample, "Thing"),
        )
        assertEquals(listOf("title", "onOpen", "items", "modifier"), SourceContract.names(SourceContract.parameters(sample, "Thing")))
    }

    @Test fun parametersOfAFunctionWithTypeParametersAndAReceiverAreFound() {
        assertEquals(listOf("index: Int"), SourceContract.parameters(sample, "second"))
    }

    @Test fun aFunctionThatIsNotThereFails() {
        assertFailsWith<AssertionError> { SourceContract.parameters(sample, "Missing") }
        assertFailsWith<AssertionError> { SourceContract.functionBody(sample, "Missing") }
        assertFailsWith<AssertionError> { SourceContract.parameters(sample, "hing") }
    }

    @Test fun commentsAreRemovedFromTheCode() {
        val code = SourceContract.code(sample)
        assertFalse(code.contains("Docs that mention"))
        assertFalse(code.contains("in a comment"))
        assertTrue(code.contains("fun Thing("))
    }

    @Test fun theBodyRunsFromTheOpeningBraceToTheMatchingOne() {
        val body = SourceContract.functionBody(sample, "Thing")
        assertTrue(body.startsWith("{") && body.endsWith("}"))
        assertTrue(body.contains("other()"))
        assertFalse(body.contains("fun second"), "it stops at its own closing brace")
        assertEquals("{\n    return this[index]\n}", SourceContract.functionBody(sample, "second"))
    }

    @Test fun anExpressionBodyIsRefused() {
        assertFailsWith<AssertionError> { SourceContract.functionBody(sample, "expression") }
    }

    @Test fun theFirstStatementIsTheDelegateCallOnOneLine() {
        val first = SourceContract.firstStatement(SourceContract.functionBody(sample, "Thing"))
        assertEquals(
            "if (LocalCosmoShell.current != null) return com.cosmotune.pkg.CosmoThing( title = title, onOpen = onOpen, items = items, modifier = modifier, )",
            first,
        )
        SourceContract.assertPassesEveryParameter(first, SourceContract.names(SourceContract.parameters(sample, "Thing")))
    }

    @Test fun anIfWithAnElseIsOneStatement() {
        val src = "fun f(a: Int) {\n    if (a > 0) {\n        x(a = a)\n    } else {\n        y(a = a)\n    }\n    z()\n}"
        assertEquals("if (a > 0) { x(a = a) } else { y(a = a) }", SourceContract.firstStatement(SourceContract.functionBody(src, "f")))
    }

    @Test fun aBodyWithOneStatementAndNoBracesGivesThatStatement() {
        assertEquals("val a = 1", SourceContract.firstStatement("val a = 1\nval b = 2"))
        assertEquals("", SourceContract.firstStatement("{}"))
    }

    @Test fun aMissingArgumentIsCaughtAndAWholeWordIsRequired() {
        SourceContract.assertPassesEveryParameter("f(title = title, onOpen = onOpen)", listOf("title", "onOpen"))
        assertFailsWith<AssertionError> { SourceContract.assertPassesEveryParameter("f(title = title)", listOf("title", "onOpen")) }
        assertFailsWith<AssertionError> { SourceContract.assertPassesEveryParameter("f(subtitle = subtitle)", listOf("title")) }
        assertFailsWith<AssertionError> { SourceContract.assertPassesEveryParameter("f(title = other)", listOf("title")) }
    }
}
