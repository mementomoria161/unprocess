package com.reilandeubank.unprocess.filter

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OpenGL ES 2.0 stage that bakes a Super-8 film look into the recorded video.
 *
 * Data flow:
 *
 *   Camera2 → [inputSurface] → SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
 *           → fragment shader (procedural LUT + grain + vignette + flicker
 *             + halation + gate-weave + scratches/dust)
 *           → encoder EGLSurface (MediaRecorder/MediaCodec hardware H.264)
 *
 * Deliberately the renderer does NOT touch the on-screen preview SurfaceView —
 * the preview remains a plain camera preview, exactly like the normal video
 * path. That keeps the fragile parts (EGL window surfaces, surface hand-off,
 * orientation) confined to the encoder's own persistent input surface, which
 * only exists while recording. The result: opening video mode is as safe as
 * the normal pipeline, and the Super-8 grade is rendered into the saved file.
 *
 * Everything GL runs on a dedicated thread, and every callback body is wrapped
 * so a stray throwable can never kill that thread (and with it the process).
 */
class Super8Renderer private constructor(
    val videoWidth: Int,
    val videoHeight: Int,
) {
    private lateinit var inputSurfaceInternal: Surface
    private lateinit var surfaceTexture: SurfaceTexture

    private val thread = HandlerThread("Super8GL").apply { start() }
    private val handler = Handler(thread.looper)

    // EGL state
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    /** 1x1 offscreen surface kept current when nothing else is bound, so the
     *  context (and the external texture) stays valid for updateTexImage. */
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var encoderSurface: Surface? = null
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private val encoderMvp = FloatArray(16)
    /** Actual EGL surface size of the encoder input (queried, not assumed). */
    private var encoderSurfW = 0
    private var encoderSurfH = 0
    @Volatile private var recording = false

    // GL program
    private var program = 0
    private var oesTextureId = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uMvpLoc = 0
    private var uTexMatrixLoc = 0
    private var uTimeLoc = 0
    private var uSeedLoc = 0
    private var uJitterLoc = 0
    private var uResolutionLoc = 0

    private val stMatrix = FloatArray(16)
    private val quadVertices: FloatBuffer = floatBuffer(
        // x, y,    s, t   (full-screen triangle strip)
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )

    private var frameCount = 0L
    /** Timestamp (ns) of the last frame we actually rendered, for 18 fps pacing. */
    private var lastDrawTs = 0L
    @Volatile private var released = false

    /** The camera-facing input surface, or null if GL init failed. Stable for
     *  the renderer's lifetime, so the camera session can be re-created without
     *  tearing the renderer down. */
    fun inputSurfaceOrNull(): Surface? =
        if (!released && ::inputSurfaceInternal.isInitialized) inputSurfaceInternal else null

    private fun initOnGlThread() {
        Matrix.setIdentityM(encoderMvp, 0)
        initEgl()
        makeCurrent(pbufferSurface)
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        oesTextureId = createOesTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener { scheduleDraw() }
        }
        inputSurfaceInternal = Surface(surfaceTexture)
    }

    /**
     * Starts feeding the hardware encoder with [surface] (the MediaRecorder/
     * MediaCodec input surface). Asynchronous.
     */
    fun startEncoder(surface: Surface) {
        handler.post {
            try {
                if (released) return@post
                encoderSurface = surface
                encoderEgl = createWindowSurface(surface)
                encoderSurfW = querySurface(encoderEgl, EGL14.EGL_WIDTH)
                encoderSurfH = querySurface(encoderEgl, EGL14.EGL_HEIGHT)
                Log.d(
                    TAG,
                    "startEncoder: requested=${videoWidth}x$videoHeight " +
                        "actual EGL surface=${encoderSurfW}x$encoderSurfH",
                )
                lastDrawTs = 0L
                recording = true
            } catch (t: Throwable) {
                Log.e(TAG, "startEncoder failed", t)
                recording = false
                encoderEgl = EGL14.EGL_NO_SURFACE
            }
        }
    }

    private fun querySurface(surface: EGLSurface, what: Int): Int {
        val v = IntArray(1)
        return if (EGL14.eglQuerySurface(eglDisplay, surface, what, v, 0) && v[0] > 0) v[0] else 0
    }

    /**
     * Stops feeding the encoder and BLOCKS (bounded) until the GL thread has
     * finished any in-flight draw and destroyed the encoder EGL surface. The
     * caller must invoke this BEFORE MediaRecorder.stop() so a swapBuffers on
     * the GL thread can never race with the surface being torn down — that
     * race is a native crash, uncatchable by try/catch.
     */
    fun stopEncoder() {
        // Halt new encoder draws immediately (volatile), then drain the GL queue.
        recording = false
        if (released) return
        val latch = CountDownLatch(1)
        handler.post {
            try {
                recording = false
                releaseEncoderEgl()
                encoderSurface = null
            } catch (t: Throwable) {
                Log.w(TAG, "stopEncoder error", t)
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun release() {
        if (released) return
        released = true
        recording = false
        val latch = CountDownLatch(1)
        handler.post {
            try {
                releaseEncoderEgl()
                if (program != 0) GLES20.glDeleteProgram(program)
                if (oesTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                if (::surfaceTexture.isInitialized) {
                    surfaceTexture.setOnFrameAvailableListener(null)
                    surfaceTexture.release()
                }
                if (::inputSurfaceInternal.isInitialized) inputSurfaceInternal.release()
                releaseEgl()
            } catch (t: Throwable) {
                Log.w(TAG, "Error during release", t)
            } finally {
                latch.countDown()
            }
        }
        // Bounded wait so a wedged GL thread can never ANR the caller.
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread.quitSafely()
    }

    // ---------------- GL thread internals ----------------

    private fun scheduleDraw() {
        if (released) return
        handler.post { drawFrame() }
    }

    private fun drawFrame() {
        if (released) return
        try {
            // updateTexImage needs a current context; the pbuffer is always valid.
            makeCurrent(pbufferSurface)
            // Always consume the buffer (releases it back to the camera) even
            // when we're not recording or decide to drop it for pacing.
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)

            if (!recording || encoderEgl == EGL14.EGL_NO_SURFACE) return
            val surf = encoderSurface
            if (surf == null || !surf.isValid) return

            // Timestamp in CLOCK_MONOTONIC (System.nanoTime). We deliberately do
            // NOT use SurfaceTexture.timestamp: the camera reports it in the
            // sensor's clock (often CLOCK_BOOTTIME, which includes time the
            // device spent asleep), whereas MediaRecorder's MIC audio track is
            // in CLOCK_MONOTONIC. Mixing the two makes the muxer think the
            // tracks are minutes apart → a bogus multi-minute duration and an
            // unplayable file. nanoTime matches the audio clock and keeps A/V
            // in sync. We also pace to ~18 fps off the same wall clock.
            val nowNs = System.nanoTime()
            if (lastDrawTs > 0 &&
                nowNs - lastDrawTs < (FRAME_INTERVAL_NS * 0.85).toLong()
            ) {
                return
            }
            lastDrawTs = nowNs
            frameCount++

            makeCurrent(encoderEgl)
            // Use the encoder surface's ACTUAL size so the viewport always
            // fills it exactly (prevents squished/stretched output if it ever
            // differs from the requested video size).
            val ew = if (encoderSurfW > 0) encoderSurfW else videoWidth
            val eh = if (encoderSurfH > 0) encoderSurfH else videoHeight
            render(ew, eh, encoderMvp)
            setPresentationTime(encoderEgl, nowNs)
            EGL14.eglSwapBuffers(eglDisplay, encoderEgl)
            // Return to the pbuffer so the encoder surface isn't left current
            // (it may be torn down by stopEncoder() at any time).
            makeCurrent(pbufferSurface)
        } catch (t: Throwable) {
            Log.w(TAG, "drawFrame error", t)
        }
    }

    private fun render(width: Int, height: Int, mvp: FloatArray) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        quadVertices.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, stMatrix, 0)

        // 18 fps clock drives flicker/grain so the look is paced like real film.
        val t = frameCount / 18f
        GLES20.glUniform1f(uTimeLoc, t)
        GLES20.glUniform1f(uSeedLoc, (frameCount * 0.6180339887f) % 1000f)
        // Gate weave: tiny per-frame translation of the sampled image.
        val jx = (Math.sin(frameCount * 0.7).toFloat()) * 0.0015f
        val jy = (Math.cos(frameCount * 1.1).toFloat()) * 0.0020f
        GLES20.glUniform2f(uJitterLoc, jx, jy)
        GLES20.glUniform2f(uResolutionLoc, width.toFloat(), height.toFloat())

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    // ---------------- EGL helpers ----------------

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // Required so the same config works for the encoder input surface.
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0
        ) {
            throw RuntimeException("eglChooseConfig failed")
        }
        eglConfig = configs[0]
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttribs, 0)
        if (pbufferSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreatePbufferSurface failed")
        }
    }

    private fun createWindowSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val egl = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
        if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
        }
        return egl
    }

    private fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed: ${EGL14.eglGetError()}")
        }
    }

    private fun setPresentationTime(surface: EGLSurface, nsecs: Long) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, surface, nsecs)
    }

    private fun releaseEncoderEgl() {
        if (encoderEgl != EGL14.EGL_NO_SURFACE) {
            try {
                // Don't destroy whatever is current.
                EGL14.eglMakeCurrent(
                    eglDisplay, pbufferSurface, pbufferSurface, eglContext,
                )
            } catch (_: Throwable) {
            }
            EGL14.eglDestroySurface(eglDisplay, encoderEgl)
            encoderEgl = EGL14.EGL_NO_SURFACE
        }
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                pbufferSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    // ---------------- shader plumbing ----------------

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        aPositionLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        uMvpLoc = GLES20.glGetUniformLocation(prog, "uMvp")
        uTexMatrixLoc = GLES20.glGetUniformLocation(prog, "uTexMatrix")
        uTimeLoc = GLES20.glGetUniformLocation(prog, "uTime")
        uSeedLoc = GLES20.glGetUniformLocation(prog, "uSeed")
        uJitterLoc = GLES20.glGetUniformLocation(prog, "uJitter")
        uResolutionLoc = GLES20.glGetUniformLocation(prog, "uResolution")
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("shader compile failed: $log")
        }
        return shader
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer {
        return ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
    }

    companion object {
        private const val TAG = "Super8Renderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** Target Super-8 cadence: 18 fps → ~55.5 ms between frames. */
        private const val FRAME_INTERVAL_NS = 1_000_000_000.0 / 18.0

        /**
         * Creates a renderer and blocks (briefly, with a timeout) until the GL
         * context and camera-facing input surface are ready. Returns null if GL
         * initialisation fails on this device — callers must fall back to the
         * normal (non-GL) pipeline so video still works.
         */
        fun createOrNull(videoWidth: Int, videoHeight: Int): Super8Renderer? {
            val renderer = Super8Renderer(videoWidth, videoHeight)
            val latch = CountDownLatch(1)
            var ok = false
            renderer.handler.post {
                try {
                    renderer.initOnGlThread()
                    ok = true
                } catch (t: Throwable) {
                    Log.e(TAG, "Super8 GL init failed", t)
                } finally {
                    latch.countDown()
                }
            }
            val completed = try {
                latch.await(3, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!completed || !ok || renderer.inputSurfaceOrNull() == null) {
                renderer.release()
                return null
            }
            return renderer
        }

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            uniform vec2 uJitter;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vec2 tc = (uTexMatrix * aTexCoord).xy;
                vTexCoord = tc + uJitter;
            }
        """

        /**
         * Super-8 look, computed entirely in the shader (procedural LUT — no
         * .cube file). The colour transform targets the warm, slightly faded,
         * lifted-black Kodachrome/Ektachrome signature of consumer Super-8
         * stock; on top of it sit the mechanical/optical artefacts of the
         * format: vignette, animated grain, projector flicker, halation glow,
         * gate weave (in the vertex stage) and the occasional scratch/dust.
         */
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uTime;
            uniform float uSeed;
            uniform vec2 uResolution;

            float hash(vec2 p) {
                p = fract(p * vec2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            void main() {
                vec2 uv = clamp(vTexCoord, 0.0, 1.0);
                vec3 col = texture2D(sTexture, uv).rgb;

                // --- Procedural LUT: warm, faded Super-8 grade (deliberately
                //     strong so the look is unmistakable on the recording) ---
                // Faded film: lift blacks noticeably, pull highlights a touch.
                col = col * 0.82 + 0.10;
                // Soft S-curve for film contrast.
                col = (col - 0.5) * 1.12 + 0.5;
                // Moderate desaturation toward the muted film palette.
                float luma = dot(col, vec3(0.299, 0.587, 0.114));
                col = mix(vec3(luma), col, 0.68);
                // Strong warm white balance (Kodak amber): push red, cut blue.
                col.r *= 1.18;
                col.g *= 1.02;
                col.b *= 0.80;
                // Amber highlights + brown shadows split-tone.
                col += vec3(0.08, 0.035, -0.02) * smoothstep(0.40, 1.0, luma);
                col += vec3(0.06, 0.02, -0.015) * (1.0 - smoothstep(0.0, 0.55, luma));

                // --- Halation: warm glow bleeding from highlights ---
                col += vec3(0.12, 0.04, 0.0) * pow(max(luma - 0.6, 0.0), 2.0) * 3.0;

                // --- Vignette (clearly visible darkened corners) ---
                vec2 q = uv - 0.5;
                float vig = 1.0 - dot(q, q) * 1.8;
                vig = clamp(vig, 0.0, 1.0);
                col *= mix(0.30, 1.0, vig);

                // --- Projector flicker (brightness fluctuation) ---
                float flick = 1.0
                    + 0.05 * sin(uTime * 7.0)
                    + 0.025 * sin(uTime * 19.0)
                    + (hash(vec2(uSeed, 1.7)) - 0.5) * 0.06;
                col *= flick;

                // --- Animated film grain ---
                float g = hash(uv * uResolution * 0.65 + uSeed);
                col += (g - 0.5) * 0.13;

                // --- Occasional vertical scratch ---
                float scratchX = hash(vec2(floor(uSeed * 7.0), 3.0));
                float scratch = smoothstep(0.0025, 0.0, abs(uv.x - scratchX));
                float scratchOn = step(0.72, hash(vec2(floor(uSeed * 3.0), 9.0)));
                col += scratch * scratchOn * 0.22;

                // --- Sparse dust specks ---
                float dust = hash(floor(uv * uResolution / 3.0) + floor(uSeed * 53.0));
                if (dust > 0.9965) {
                    col += 0.45;
                }

                gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
        """
    }
}
