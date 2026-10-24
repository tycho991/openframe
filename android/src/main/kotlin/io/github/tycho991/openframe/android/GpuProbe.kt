package io.github.tycho991.openframe.android

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Measures what a crossfade costs this device's GPU: two textures of the size of the screen are blended into a texture
 * of that size, frame after frame, outside any window. The slideshow cannot see this from the inside: the transitions
 * run on the render thread, and a GPU that is behind delays the screen without ever blocking the UI thread.
 */
object GpuProbe {
    /** Milliseconds one blended frame of [width] x [height] takes, or `null` if this device cannot be measured. */
    fun fadeFrameMs(
        width: Int,
        height: Int,
        frames: Int = 24,
    ): Float? =
        try {
            measure(width, height, frames)
        } catch (e: Throwable) {
            null
        }

    private fun measure(
        width: Int,
        height: Int,
        frames: Int,
    ): Float? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return null
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val wanted =
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_ALPHA_SIZE,
                    8,
                    EGL14.EGL_NONE,
                )
            if (!EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            context =
                EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0,
                )
            surface =
                EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            return draw(width, height, frames)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun draw(
        width: Int,
        height: Int,
        frames: Int,
    ): Float? {
        val program = program() ?: return null
        val textures = IntArray(3)
        GLES20.glGenTextures(3, textures, 0)
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until width * height) pixels.putInt((i * 2654435761L).toInt())
        for (i in 0 until 3) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            pixels.rewind()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                if (i <
                    2
                ) {
                    pixels
                } else {
                    null
                },
            )
        }
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[2], 0)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) return null

        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).rewind()
        val position = GLES20.glGetAttribLocation(program, "position")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "a"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "b"), 1)
        val mixAt = GLES20.glGetUniformLocation(program, "amount")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])

        val times = ArrayList<Long>()
        for (i in 0 until frames) {
            val start = System.nanoTime()
            GLES20.glUniform1f(mixAt, i / frames.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()
            if (i >= WARMUP) times += System.nanoTime() - start
        }
        GLES20.glDeleteFramebuffers(1, fbo, 0)
        GLES20.glDeleteTextures(3, textures, 0)
        GLES20.glDeleteProgram(program)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR || times.isEmpty()) return null
        times.sort()
        return times[times.size / 2] / 1_000_000f
    }

    private fun program(): Int? {
        val vertex =
            shader(
                GLES20.GL_VERTEX_SHADER,
                "attribute vec2 position; varying vec2 uv; void main() { uv = position * 0.5 + 0.5; gl_Position = vec4(position, 0.0, 1.0); }",
            )
        val fragment =
            shader(
                GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; varying vec2 uv; uniform sampler2D a; uniform sampler2D b; uniform float amount; " +
                    "void main() { gl_FragColor = mix(texture2D(a, uv), texture2D(b, uv), amount); }",
            )
        if (vertex == 0 || fragment == 0) return null
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        return if (linked[0] == GLES20.GL_TRUE) program else null
    }

    private fun shader(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        return if (compiled[0] == GLES20.GL_TRUE) shader else 0
    }

    private const val WARMUP = 4
}
