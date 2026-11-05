package com.cosmotune.video.player.smooth

/**
 * The shaders of the phone's frame smoothing, as constants (the GL code compiles them; a JVM test checks their structure, since a
 * compile error is otherwise first seen on a phone). The maths is the desktop's SkSL and block search (`SmoothRenderer`,
 * `MotionEstimator`) moved onto the GPU:
 *
 * - **Picture passes** (GLSL ES 1.00, so Blend also runs where only OpenGL ES 2.0 is there): [OES] copies the decoded frame
 *   (an external texture) into a ring texture, [LUMA] makes its small grey copy (four samples per analysis pixel, as the CPU
 *   extractor), [COPY], [BLEND] and [WARP] draw to the screen. [WARP] reads the motion field as the desktop shader does: red and
 *   green are the move of the block (128 is none, the ends are plus and minus [MOTION_RANGE] analysis pixels), blue its trust.
 * - **Search passes** (GLSL ES 3.00): [SAD] computes, for every 8 x 8 block and every candidate displacement, the sum of absolute
 *   differences (one output pixel each, 16 bits split over red and green), [SELECT] takes the best candidate of each block, refines
 *   it to a fraction of a pixel by the same parabola, and turns its error into trust, [ZERO] is the cheaper pass Blend uses (only
 *   the difference at rest), [REDUCE] adds the blocks up into one pixel of flags: still, scene cut by the search, scene cut by the
 *   plain difference.
 *
 * Positions are in texture space (the bottom row first), both for the search and for the warp, so the flip of a GL texture never
 * enters the maths.
 */
object GlslSources {

    /** One program: its sources and the uniforms the code sets (so the test can see they are declared). */
    class Program(val name: String, val vertex: String, val fragment: String, val uniforms: List<String>)

    /** The analysis picture is searched in blocks of this many pixels (the CPU estimator's BLOCK). */
    const val BLOCK = 8

    /** The motion field is scaled so that this many analysis pixels fill the byte range (the widest search plus a fraction). */
    const val MOTION_RANGE = 13f

    /** A block whose difference at rest is this small has nothing to search for (one grey level a pixel). */
    const val FLAT_SAD = 64f

    /** The value an out-of-picture candidate gets: larger than any real sum (64 pixels of 255). */
    const val OUTSIDE = 65535f

    /** The mean grey difference of two whole frames above which they are different shots. */
    const val CUT_MEAN_DIFF = 45f

    /** The whole picture changes by less than this many grey levels on average: nothing moves. */
    const val STILL_MAD = 0.8f

    private const val PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    /** One vertex shader for every picture pass: a quad over the unit square, placed in clip space by [uPlace], texture matrix for the decoder's frame. */
    private const val VERTEX_100 = """#version 100
attribute vec2 aPos;
uniform vec4 uPlace;
uniform mat4 uTexMatrix;
varying vec2 vUv;
void main() {
    vec2 uv = aPos * 0.5 + 0.5;
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(mix(uPlace.xy, uPlace.zw, uv), 0.0, 1.0);
}
"""

    /** The vertex shader of the search passes: the quad over the whole target. */
    private const val VERTEX_300 = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val FRAGMENT_OES = """#version 100
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTex;
varying vec2 vUv;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);
}
"""

    private const val FRAGMENT_COPY = """#version 100
precision mediump float;
uniform sampler2D uTex;
varying vec2 vUv;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);
}
"""

    private const val FRAGMENT_BLEND = """#version 100
precision mediump float;
uniform sampler2D uOlder;
uniform sampler2D uNewer;
uniform float uWeight;
varying vec2 vUv;
void main() {
    vec3 a = texture2D(uOlder, vUv).rgb;
    vec3 b = texture2D(uNewer, vUv).rgb;
    gl_FragColor = vec4(mix(a, b, uWeight), 1.0);
}
"""

    private const val FRAGMENT_LUMA = """#version 100
$PRECISION
uniform sampler2D uTex;
uniform vec2 uCell;
varying vec2 vUv;
const vec3 WEIGHTS = vec3(0.30078125, 0.5859375, 0.11328125);
void main() {
    float s = dot(texture2D(uTex, vUv + vec2(-0.25, -0.25) * uCell).rgb, WEIGHTS)
            + dot(texture2D(uTex, vUv + vec2(0.25, -0.25) * uCell).rgb, WEIGHTS)
            + dot(texture2D(uTex, vUv + vec2(-0.25, 0.25) * uCell).rgb, WEIGHTS)
            + dot(texture2D(uTex, vUv + vec2(0.25, 0.25) * uCell).rgb, WEIGHTS);
    gl_FragColor = vec4(s * 0.25, 0.0, 0.0, 1.0);
}
"""

    private const val FRAGMENT_WARP = """#version 100
$PRECISION
uniform sampler2D uOlder;
uniform sampler2D uNewer;
uniform sampler2D uMotion;
uniform float uPhase;
uniform vec2 uInvLuma;
uniform float uMotionRange;
uniform float uBias;
varying vec2 vUv;
void main() {
    vec4 m = texture2D(uMotion, vUv);
    vec2 v = (m.rg - 128.0 / 255.0) * (255.0 / 127.0) * uMotionRange;
    v = v * uInvLuma * m.b;
    vec3 a = texture2D(uOlder, vUv - v * uPhase).rgb;
    vec3 b = texture2D(uNewer, vUv + v * (1.0 - uPhase)).rgb;
    float miss = smoothstep(0.08, 0.30, dot(abs(a - b), vec3(0.299, 0.587, 0.114)));
    float nearest = uPhase < 0.5 ? 0.0 : 1.0;
    float w = mix(uPhase, nearest, miss * uBias);
    gl_FragColor = vec4(mix(a, b, w), 1.0);
}
"""

    private const val HEADER_300 = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
"""

    private const val FRAGMENT_SAD = HEADER_300 + """
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform ivec2 uLuma;
uniform int uRange;
out vec4 outColor;
void main() {
    ivec2 o = ivec2(gl_FragCoord.xy);
    int side = 2 * uRange + 1;
    ivec2 block = o / ivec2(side);
    ivec2 cand = o - block * side - ivec2(uRange);
    ivec2 p = block * 8;
    ivec2 q = p + cand;
    float sad = 65535.0;
    if (q.x >= 0 && q.y >= 0 && q.x + 8 <= uLuma.x && q.y + 8 <= uLuma.y) {
        float sum = 0.0;
        for (int j = 0; j < 8; j++) {
            for (int i = 0; i < 8; i++) {
                float a = texelFetch(uCur, p + ivec2(i, j), 0).r;
                float b = texelFetch(uPrev, q + ivec2(i, j), 0).r;
                sum += floor(abs(a - b) * 255.0 + 0.5);
            }
        }
        sad = sum;
    }
    float hi = floor(sad / 256.0);
    outColor = vec4(hi / 255.0, (sad - hi * 256.0) / 255.0, 0.0, 1.0);
}
"""

    private const val FRAGMENT_SELECT = HEADER_300 + """
uniform sampler2D uSad;
uniform int uRange;
uniform float uTolerance;
uniform float uMotionRange;
layout(location = 0) out vec4 outMotion;
layout(location = 1) out vec4 outStat;

float sadAt(ivec2 block, ivec2 cand) {
    int side = 2 * uRange + 1;
    vec4 c = texelFetch(uSad, block * side + cand + ivec2(uRange), 0);
    return floor(c.r * 255.0 + 0.5) * 256.0 + floor(c.g * 255.0 + 0.5);
}

vec2 pack16(float v) {
    float hi = floor(v / 256.0);
    return vec2(hi, v - hi * 256.0) / 255.0;
}

float refine(ivec2 block, ivec2 d, ivec2 axis, float centre) {
    ivec2 lo = d - axis;
    ivec2 hi = d + axis;
    if (abs(lo.x) > uRange || abs(lo.y) > uRange || abs(hi.x) > uRange || abs(hi.y) > uRange) return 0.0;
    float a = sadAt(block, lo);
    float b = sadAt(block, hi);
    if (a >= 65535.0 || b >= 65535.0) return 0.0;
    float den = 2.0 * (a - 2.0 * centre + b);
    if (den <= 0.0) return 0.0;
    return clamp((a - b) / den, -0.5, 0.5);
}

void main() {
    ivec2 block = ivec2(gl_FragCoord.xy);
    float zero = sadAt(block, ivec2(0));
    float best = zero;
    ivec2 bd = ivec2(0);
    if (zero > 64.0) {
        for (int dy = -uRange; dy <= uRange; dy++) {
            for (int dx = -uRange; dx <= uRange; dx++) {
                if (dx == 0 && dy == 0) continue;
                float s = sadAt(block, ivec2(dx, dy));
                if (s < best) {
                    best = s;
                    bd = ivec2(dx, dy);
                }
            }
        }
        if (zero - best <= 64.0) {
            best = zero;
            bd = ivec2(0);
        }
    }
    vec2 f = vec2(bd);
    if ((bd.x != 0 || bd.y != 0) && best > 0.0) {
        f += vec2(refine(block, bd, ivec2(1, 0), best), refine(block, bd, ivec2(0, 1), best));
    }
    float mad = best / 64.0;
    float full = uTolerance * 0.5;
    float trust = mad <= full ? 1.0 : (mad >= uTolerance ? 0.0 : 1.0 - (mad - full) / (uTolerance - full));
    vec2 e = (128.0 + clamp(-f / uMotionRange, -1.0, 1.0) * 127.0) / 255.0;
    outMotion = vec4(e, trust, 1.0);
    outStat = vec4(pack16(zero), pack16(best));
}
"""

    private const val FRAGMENT_ZERO = HEADER_300 + """
uniform sampler2D uCur;
uniform sampler2D uPrev;
out vec4 outStat;

vec2 pack16(float v) {
    float hi = floor(v / 256.0);
    return vec2(hi, v - hi * 256.0) / 255.0;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) * 8;
    float sum = 0.0;
    for (int j = 0; j < 8; j++) {
        for (int i = 0; i < 8; i++) {
            float a = texelFetch(uCur, p + ivec2(i, j), 0).r;
            float b = texelFetch(uPrev, p + ivec2(i, j), 0).r;
            sum += floor(abs(a - b) * 255.0 + 0.5);
        }
    }
    outStat = vec4(pack16(sum), pack16(sum));
}
"""

    private const val FRAGMENT_REDUCE = HEADER_300 + """
uniform sampler2D uStat;
uniform ivec2 uBlocks;
uniform float uTolerance;
uniform float uCutFraction;
uniform float uCutMad;
out vec4 outFlags;

float unpack16(vec2 c) {
    return floor(c.x * 255.0 + 0.5) * 256.0 + floor(c.y * 255.0 + 0.5);
}

void main() {
    float zeroSum = 0.0;
    float bestSum = 0.0;
    float bad = 0.0;
    for (int y = 0; y < uBlocks.y; y++) {
        for (int x = 0; x < uBlocks.x; x++) {
            vec4 s = texelFetch(uStat, ivec2(x, y), 0);
            float best = unpack16(s.ba);
            zeroSum += unpack16(s.rg);
            bestSum += best;
            if (best / 64.0 >= uTolerance) bad += 1.0;
        }
    }
    float blocks = float(uBlocks.x * uBlocks.y);
    float pixels = blocks * 64.0;
    float meanZero = zeroSum / pixels;
    float meanBest = bestSum / pixels;
    bool still = meanZero < 0.8;
    bool searchCut = !still && (bad / blocks > uCutFraction || meanBest > uCutMad);
    bool plainCut = meanZero > 45.0;
    outFlags = vec4(still ? 1.0 : 0.0, searchCut ? 1.0 : 0.0, plainCut ? 1.0 : 0.0, 1.0);
}
"""

    val OES = Program("oes", VERTEX_100, FRAGMENT_OES, listOf("uPlace", "uTexMatrix", "uTex"))
    val LUMA = Program("luma", VERTEX_100, FRAGMENT_LUMA, listOf("uPlace", "uTexMatrix", "uTex", "uCell"))
    val COPY = Program("copy", VERTEX_100, FRAGMENT_COPY, listOf("uPlace", "uTexMatrix", "uTex"))
    val BLEND = Program("blend", VERTEX_100, FRAGMENT_BLEND, listOf("uPlace", "uTexMatrix", "uOlder", "uNewer", "uWeight"))
    val WARP = Program(
        "warp", VERTEX_100, FRAGMENT_WARP,
        listOf("uPlace", "uTexMatrix", "uOlder", "uNewer", "uMotion", "uPhase", "uInvLuma", "uMotionRange", "uBias"),
    )
    val SAD = Program("sad", VERTEX_300, FRAGMENT_SAD, listOf("uCur", "uPrev", "uLuma", "uRange"))
    val SELECT = Program("select", VERTEX_300, FRAGMENT_SELECT, listOf("uSad", "uRange", "uTolerance", "uMotionRange"))
    val ZERO = Program("zero", VERTEX_300, FRAGMENT_ZERO, listOf("uCur", "uPrev"))
    val REDUCE = Program("reduce", VERTEX_300, FRAGMENT_REDUCE, listOf("uStat", "uBlocks", "uTolerance", "uCutFraction", "uCutMad"))

    /** The programs a context of OpenGL ES 2.0 can run: Blend and the plain frame. */
    val ES2_PROGRAMS: List<Program> = listOf(OES, COPY, BLEND)

    /** The programs that need OpenGL ES 3.0 (plus the picture passes above). */
    val ES3_PROGRAMS: List<Program> = listOf(LUMA, WARP, SAD, SELECT, ZERO, REDUCE)

    val ALL: List<Program> = ES2_PROGRAMS + ES3_PROGRAMS
}
