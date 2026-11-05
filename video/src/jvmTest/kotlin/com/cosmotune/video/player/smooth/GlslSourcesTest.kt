package com.cosmotune.video.player.smooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shaders cannot be compiled on the JVM, so their structure is checked: a version line first, balanced braces and brackets,
 * a `main`, every uniform the code sets declared, the two stages of a program agreeing on version and on the varyings between them,
 * and the numbers the CPU side shares with the shaders.
 */
class GlslSourcesTest {
    private fun version(source: String): String = source.lineSequence().first()

    private fun uncommented(source: String): String = source.lines().joinToString("\n") { it.substringBefore("//") }

    private fun declared(source: String, kind: String): Set<String> =
        Regex("""\b$kind\s+(?:(?:lowp|mediump|highp)\s+)?\w+\s+(\w+)\s*(?:\[\w+])?;""").findAll(source).map { it.groupValues[1] }.toSet()

    @Test
    fun everyShaderStartsWithItsVersion() {
        for (p in GlslSources.ALL) {
            for ((stage, source) in listOf("vertex" to p.vertex, "fragment" to p.fragment)) {
                assertTrue(version(source).startsWith("#version "), "${p.name} $stage starts with a version line")
            }
            assertEquals(version(p.vertex), version(p.fragment), "${p.name}: both stages are one version")
        }
    }

    @Test
    fun theSearchPassesNeedEs3AndThePicturePassesNeedOnlyEs1() {
        for (p in GlslSources.ES3_PROGRAMS.filter { it.vertex.startsWith("#version 300 es") }) {
            assertTrue(p.fragment.startsWith("#version 300 es"), p.name)
        }
        for (p in GlslSources.ES2_PROGRAMS) {
            assertTrue(p.vertex.startsWith("#version 100") && p.fragment.startsWith("#version 100"), "${p.name} runs on OpenGL ES 2.0")
        }
        assertTrue(GlslSources.SAD.vertex.startsWith("#version 300 es"))
        assertTrue(GlslSources.SELECT.fragment.startsWith("#version 300 es"))
    }

    @Test
    fun bracesBracketsAndParenthesesBalance() {
        for (p in GlslSources.ALL) {
            for ((stage, source) in listOf("vertex" to p.vertex, "fragment" to p.fragment)) {
                val text = uncommented(source)
                for ((open, close) in listOf('{' to '}', '(' to ')', '[' to ']')) {
                    assertEquals(text.count { it == open }, text.count { it == close }, "${p.name} $stage: $open$close")
                }
                var depth = 0
                for (c in text) {
                    if (c == '{') depth++
                    if (c == '}') depth--
                    assertTrue(depth >= 0, "${p.name} $stage: a brace closes before it opens")
                }
            }
        }
    }

    @Test
    fun everyStageHasAMain() {
        for (p in GlslSources.ALL) {
            assertTrue(Regex("""void\s+main\s*\(\s*\)\s*\{""").containsMatchIn(p.vertex), "${p.name} vertex main")
            assertTrue(Regex("""void\s+main\s*\(\s*\)\s*\{""").containsMatchIn(p.fragment), "${p.name} fragment main")
        }
    }

    @Test
    fun everyUniformTheCodeSetsIsDeclared() {
        for (p in GlslSources.ALL) {
            val seen = declared(p.vertex, "uniform") + declared(p.fragment, "uniform")
            for (name in p.uniforms) assertTrue(name in seen, "${p.name}: uniform $name is declared (found $seen)")
        }
    }

    @Test
    fun everyDeclaredUniformIsOneTheCodeKnows() {
        for (p in GlslSources.ALL) {
            val seen = declared(p.vertex, "uniform") + declared(p.fragment, "uniform")
            for (name in seen) assertTrue(name in p.uniforms, "${p.name}: uniform $name is declared but the code does not set it")
        }
    }

    @Test
    fun theVaryingsOfTheFragmentComeFromTheVertex() {
        for (p in GlslSources.ES2_PROGRAMS + listOf(GlslSources.LUMA, GlslSources.WARP)) {
            val out = declared(p.vertex, "varying")
            val inn = declared(p.fragment, "varying")
            assertTrue(inn.isNotEmpty(), "${p.name} reads a varying")
            assertTrue(out.containsAll(inn), "${p.name}: $inn is written by the vertex shader ($out)")
        }
    }

    @Test
    fun theVertexInputIsAtLocationZeroInEveryProgram() {
        for (p in GlslSources.ALL) {
            val ok = p.vertex.contains("attribute vec2 aPos;") || p.vertex.contains("layout(location = 0) in vec2 aPos;")
            assertTrue(ok, "${p.name}: aPos is attribute 0 (bound by name before linking)")
        }
    }

    @Test
    fun theExternalTextureIsOnlyInTheCopyOfTheDecodedFrame() {
        for (p in GlslSources.ALL) {
            val external = p.fragment.contains("samplerExternalOES")
            assertEquals(p === GlslSources.OES, external, p.name)
            assertEquals(external, p.fragment.contains("#extension GL_OES_EGL_image_external : require"), p.name)
            if (external) assertTrue(p.fragment.indexOf("#extension") < p.fragment.indexOf("precision"), "extension before other code")
        }
    }

    @Test
    fun theFragmentColourOfEachStageIsWritten() {
        for (p in GlslSources.ALL) {
            if (p.fragment.startsWith("#version 100")) {
                assertTrue(p.fragment.contains("gl_FragColor ="), p.name)
            } else {
                assertTrue(p.fragment.contains("out vec4 out"), p.name)
            }
        }
        // the search writes two targets: the field and the numbers behind it
        assertTrue(GlslSources.SELECT.fragment.contains("layout(location = 0) out vec4 outMotion;"))
        assertTrue(GlslSources.SELECT.fragment.contains("layout(location = 1) out vec4 outStat;"))
    }

    @Test
    fun theNumbersTheCpuSideSharesWithTheShadersAgree() {
        assertEquals(MotionEstimator.BLOCK, GlslSources.BLOCK)
        assertTrue(GlslSources.SAD.fragment.contains("block * 8"))
        assertTrue(GlslSources.SAD.fragment.contains("+ 8 <="))
        assertEquals(GlslSources.BLOCK * GlslSources.BLOCK * 1f, GlslSources.FLAT_SAD)
        assertTrue(GlslSources.SELECT.fragment.contains("zero > 64.0"))
        assertTrue(GlslSources.SELECT.fragment.contains("zero - best <= 64.0"))
        assertTrue(GlslSources.SAD.fragment.contains("float sad = ${GlslSources.OUTSIDE.toInt()}.0;"))
        assertTrue(GlslSources.REDUCE.fragment.contains("meanZero < ${GlslSources.STILL_MAD}"))
        assertTrue(GlslSources.REDUCE.fragment.contains("meanZero > ${GlslSources.CUT_MEAN_DIFF.toInt()}.0"))
        // the widest search of any preset fits the byte range of the field, and the largest SAD of a block fits two bytes
        assertTrue(SmoothPolicy.motionParams(com.cosmotune.video.settings.SmoothPreset.ANIME, 100).range < GlslSources.MOTION_RANGE)
        assertTrue(GlslSources.BLOCK * GlslSources.BLOCK * 255 < 65535)
    }

    @Test
    fun theWarpIsTheDesktopsShaderMath() {
        val warp = GlslSources.WARP.fragment
        assertTrue(warp.contains("smoothstep(0.08, 0.30,"))
        assertTrue(warp.contains("mix(uPhase, nearest, miss * uBias)"))
        assertTrue(warp.contains("vUv - v * uPhase"))
        assertTrue(warp.contains("vUv + v * (1.0 - uPhase)"))
    }
}
