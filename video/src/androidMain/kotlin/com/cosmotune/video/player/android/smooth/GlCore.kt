package com.cosmotune.video.player.android.smooth

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import com.cosmotune.video.player.smooth.GlslSources

internal const val SMOOTH_TAG = "CosmoSmooth"

/** Anything that goes wrong in the GL path; it ends smoothing for the video and the plain surface takes over. */
internal class GlException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * An EGL display, a context (OpenGL ES 3.0 where the phone has it, else 2.0) and a 1 x 1 pbuffer to hold it while there is no
 * output window. One thread owns it: whichever calls [create] makes it current and keeps all GL calls.
 */
internal class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var current: EGLSurface = EGL14.EGL_NO_SURFACE

    /** 3 when the context is OpenGL ES 3.x, else 2. */
    var glesVersion = 2
        private set

    /** Whether `eglPresentationTimeANDROID` can hold a frame until its time. */
    var hasPresentationTime = false
        private set

    fun create() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw GlException("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw GlException("eglInitialize failed: ${error()}")
        hasPresentationTime = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)?.contains("EGL_ANDROID_presentation_time") == true
        var made = false
        for (wanted in intArrayOf(3, 2)) {
            val chosen = chooseConfig(wanted) ?: continue
            val ctx = EGL14.eglCreateContext(display, chosen, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, wanted, EGL14.EGL_NONE), 0)
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
                Log.w(SMOOTH_TAG, "no OpenGL ES $wanted context: ${error()}")
                continue
            }
            config = chosen
            context = ctx
            glesVersion = wanted
            made = true
            break
        }
        if (!made) throw GlException("no OpenGL ES context")
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (pbuffer == EGL14.EGL_NO_SURFACE) throw GlException("eglCreatePbufferSurface failed: ${error()}")
        makeCurrent(pbuffer)
    }

    private fun chooseConfig(version: Int): EGLConfig? {
        val renderable = if (version >= 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
        for (alpha in intArrayOf(8, 0)) {
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, alpha,
                EGL14.EGL_RENDERABLE_TYPE, renderable,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0 && configs[0] != null) return configs[0]
        }
        return null
    }

    /** A window surface on [surface] (the output view's). */
    fun createWindowSurface(surface: Surface): EGLSurface {
        val created = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (created == null || created == EGL14.EGL_NO_SURFACE) throw GlException("eglCreateWindowSurface failed: ${error()}")
        return created
    }

    fun destroySurface(surface: EGLSurface) {
        if (surface == EGL14.EGL_NO_SURFACE) return
        if (current == surface) makeCurrent(pbuffer)
        EGL14.eglDestroySurface(display, surface)
    }

    /** Makes the context current on [surface] (no call when it already is). */
    fun makeCurrent(surface: EGLSurface) {
        if (current == surface) return
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw GlException("eglMakeCurrent failed: ${error()}")
        current = surface
    }

    fun makeIdleCurrent() = makeCurrent(pbuffer)

    /** One swap per refresh of the screen (the default interval of a window surface, set explicitly). */
    fun setSwapInterval(interval: Int) {
        EGL14.eglSwapInterval(display, interval)
    }

    /** Asks the compositor to hold the next frame of [surface] until [nanos] (the frame's own time); ignored where it cannot. */
    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        if (hasPresentationTime && nanos > 0) EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        current = EGL14.EGL_NO_SURFACE
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        pbuffer = EGL14.EGL_NO_SURFACE
    }

    private fun error(): String = "0x" + Integer.toHexString(EGL14.eglGetError())
}

/** A linked program and where its uniforms are. */
internal class GlProgram(val id: Int, private val locations: Map<String, Int>) {
    fun uniform(name: String): Int = locations[name] ?: -1

    fun delete() {
        GLES20.glDeleteProgram(id)
    }

    companion object {
        /** Compiles and links [source]; any failure throws with the driver's own log (also written to the log). */
        fun build(source: GlslSources.Program): GlProgram {
            val vertex = compile(GLES20.GL_VERTEX_SHADER, source.vertex, "${source.name} vertex")
            val fragment = try {
                compile(GLES20.GL_FRAGMENT_SHADER, source.fragment, "${source.name} fragment")
            } catch (e: GlException) {
                GLES20.glDeleteShader(vertex)
                throw e
            }
            val program = GLES20.glCreateProgram()
            if (program == 0) throw GlException("glCreateProgram failed for ${source.name}")
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glBindAttribLocation(program, 0, "aPos")
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            if (status[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                Log.e(SMOOTH_TAG, "link of ${source.name} failed: $log")
                throw GlException("link of ${source.name} failed: $log")
            }
            val locations = HashMap<String, Int>()
            for (name in source.uniforms) locations[name] = GLES20.glGetUniformLocation(program, name)
            return GlProgram(program, locations)
        }

        private fun compile(type: Int, source: String, label: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) throw GlException("glCreateShader failed for $label")
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                Log.e(SMOOTH_TAG, "compile of $label failed: $log")
                throw GlException("compile of $label failed: $log")
            }
            return shader
        }
    }
}

/**
 * A texture of [width] x [height] RGBA with a framebuffer to draw into it. The search's field target has a second texture attached
 * beside the first (the numbers behind the field), shared by all of them and owned by the caller.
 */
internal class GlTarget(val width: Int, val height: Int, val fbo: Int, val texture: Int, private val ownsTexture: Boolean) {
    fun delete() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (ownsTexture) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    companion object {
        fun newTexture(width: Int, height: Int, linear: Boolean): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            val filter = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        /** A target with its own texture. */
        fun create(width: Int, height: Int, linear: Boolean): GlTarget {
            val texture = newTexture(width, height, linear)
            val fbo = framebuffer(texture, extra = 0)
            return GlTarget(width, height, fbo, texture, ownsTexture = true)
        }

        /** A target with its own texture and a shared [extra] texture (the same size) attached as the second colour output. */
        fun createWithExtra(width: Int, height: Int, linear: Boolean, extra: Int): GlTarget {
            val texture = newTexture(width, height, linear)
            val fbo = framebuffer(texture, extra)
            return GlTarget(width, height, fbo, texture, ownsTexture = true)
        }

        /** A target drawing into a texture someone else owns (and deletes). */
        fun around(width: Int, height: Int, texture: Int): GlTarget = GlTarget(width, height, framebuffer(texture, extra = 0), texture, ownsTexture = false)

        private fun framebuffer(texture: Int, extra: Int): Int {
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
            if (extra != 0) {
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES20.GL_TEXTURE_2D, extra, 0)
                GLES30.glDrawBuffers(2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1), 0)
            }
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glDeleteFramebuffers(1, ids, 0)
                throw GlException("framebuffer incomplete: 0x" + Integer.toHexString(status))
            }
            return ids[0]
        }
    }
}
