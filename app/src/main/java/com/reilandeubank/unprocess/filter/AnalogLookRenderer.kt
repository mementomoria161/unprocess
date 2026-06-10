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
 * The analogue looks the renderer can bake into a recording. Each look brings
 * its own fragment shader and native cadence:
 *
 * - [SUPER8]: Kodachrome-style reversal film at the format's 18 fps.
 * - [VHS]: PAL consumer camcorder at 25 fps (the European VHS standard).
 */
enum class AnalogLook(val fps: Int) {
    SUPER8(18),
    VHS(25),
}

/**
 * OpenGL ES 2.0 stage that bakes an analogue look ([AnalogLook]) into the
 * recorded video.
 *
 * Data flow:
 *
 *   Camera2 → [inputSurface] → SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
 *           → fragment shader (per-look: Super-8 film grade / VHS signal path)
 *           → encoder EGLSurface (MediaRecorder/MediaCodec hardware H.264)
 *
 * Deliberately the renderer does NOT touch the on-screen preview SurfaceView —
 * the preview remains a plain camera preview, exactly like the normal video
 * path. That keeps the fragile parts (EGL window surfaces, surface hand-off,
 * orientation) confined to the encoder's own persistent input surface, which
 * only exists while recording. The result: opening video mode is as safe as
 * the normal pipeline, and the analogue grade is rendered into the saved file.
 *
 * Everything GL runs on a dedicated thread, and every callback body is wrapped
 * so a stray throwable can never kill that thread (and with it the process).
 */
class AnalogLookRenderer private constructor(
    val look: AnalogLook,
    val videoWidth: Int,
    val videoHeight: Int,
) {
    private lateinit var inputSurfaceInternal: Surface
    private lateinit var surfaceTexture: SurfaceTexture

    private val thread = HandlerThread("AnalogLookGL").apply { start() }
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
    private var uVidXLoc = 0
    private var uVidYLoc = 0

    /** Clockwise rotation baked into [encoderMvp]; needed to map video-space
     *  directions back into texture space for the VHS shader. */
    private var encoderRotationDeg = 0

    /** Native cadence of the selected look (18 fps Super-8, 25 fps PAL VHS). */
    private val frameIntervalNs = 1_000_000_000.0 / look.fps

    private val stMatrix = FloatArray(16)
    private val quadVertices: FloatBuffer = floatBuffer(
        // x, y,    s, t   (full-screen triangle strip)
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )

    private var frameCount = 0L
    /** Timestamp (ns) of the last frame we actually rendered, for fps pacing. */
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
        program = buildProgram(
            VERTEX_SHADER,
            when (look) {
                AnalogLook.SUPER8 -> SUPER8_FRAGMENT_SHADER
                AnalogLook.VHS -> VHS_FRAGMENT_SHADER
            },
        )
        oesTextureId = createOesTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener { scheduleDraw() }
        }
        inputSurfaceInternal = Surface(surfaceTexture)
    }

    /**
     * Starts feeding the hardware encoder with [surface] (the MediaRecorder/
     * MediaCodec input surface). [rotationDeg] (0/90/180/270, clockwise) bakes
     * the device orientation into the encoded pixels so the recorder can use
     * orientationHint=0 — see CameraFragment for the derivation. Asynchronous.
     */
    fun startEncoder(surface: Surface, rotationDeg: Int) {
        handler.post {
            try {
                if (released) return@post
                encoderSurface = surface
                encoderEgl = createWindowSurface(surface)
                encoderSurfW = querySurface(encoderEgl, EGL14.EGL_WIDTH)
                encoderSurfH = querySurface(encoderEgl, EGL14.EGL_HEIGHT)
                // Negative because gl uses CCW-positive rotation while the
                // orientation contract is clockwise.
                Matrix.setRotateM(encoderMvp, 0, -rotationDeg.toFloat(), 0f, 0f, 1f)
                encoderRotationDeg = rotationDeg
                Log.d(
                    TAG,
                    "startEncoder: requested=${videoWidth}x$videoHeight " +
                        "actual EGL surface=${encoderSurfW}x$encoderSurfH rot=$rotationDeg",
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
            // in sync. We also pace to the look's fps off the same wall clock.
            val nowNs = System.nanoTime()
            if (lastDrawTs > 0 &&
                nowNs - lastDrawTs < (frameIntervalNs * 0.85).toLong()
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

        // The look's native frame clock drives flicker/grain/glitch timing.
        val t = frameCount / look.fps.toFloat()
        GLES20.glUniform1f(uTimeLoc, t)
        GLES20.glUniform1f(uSeedLoc, (frameCount * 0.6180339887f) % 1000f)
        when (look) {
            AnalogLook.SUPER8 -> {
                // Gate weave: real Super-8 transport drifts slowly (pressure
                // plate) with small per-frame randomness on top (claw
                // registration), and the vertical axis moves more than the
                // horizontal. Pure sinusoids read as artificial, so layer
                // incommensurate sines with seeded noise, plus an occasional
                // one-frame vertical hop when the claw re-seats.
                val rnd = java.util.Random(frameCount * 7919L)
                val driftX = Math.sin(frameCount * 0.23) * 0.6 + Math.sin(frameCount * 0.71) * 0.4
                val driftY = Math.sin(frameCount * 0.19 + 1.3) * 0.6 + Math.sin(frameCount * 0.53) * 0.4
                val jx = (driftX * 0.5 + (rnd.nextDouble() - 0.5)).toFloat() * 0.0016f
                var jy = (driftY * 0.5 + (rnd.nextDouble() - 0.5)).toFloat() * 0.0026f
                if (rnd.nextDouble() < 0.04) jy += (rnd.nextFloat() - 0.5f) * 0.012f
                GLES20.glUniform2f(uJitterLoc, jx, jy)
            }
            AnalogLook.VHS -> {
                // VHS has no gate: geometric instability (per-line time-base
                // error, head-switch tearing, vertical-hold bounce) lives in
                // the fragment shader, in video space.
                GLES20.glUniform2f(uJitterLoc, 0f, 0f)
            }
        }
        GLES20.glUniform2f(uResolutionLoc, width.toFloat(), height.toFloat())

        // Texture-space direction vectors for one full video-frame step right
        // (uVidX) and up (uVidY). The VHS shader displaces its samples along
        // the *video* axes (scanlines are horizontal in the final file), but
        // vTexCoord lives in camera-buffer space, which is rotated by the MVP
        // and remapped by the SurfaceTexture matrix. Derivation: videoUV →
        // clip (×2−1) → position (inverse MVP = CCW rotation by rotationDeg)
        // → quad texcoord ((pos+1)/2) → vTexCoord (2x2 block of stMatrix,
        // column-major).
        val rad = Math.toRadians(encoderRotationDeg.toDouble())
        val rc = Math.cos(rad).toFloat()
        val rs = Math.sin(rad).toFloat()
        GLES20.glUniform2f(
            uVidXLoc,
            stMatrix[0] * rc + stMatrix[4] * rs,
            stMatrix[1] * rc + stMatrix[5] * rs,
        )
        GLES20.glUniform2f(
            uVidYLoc,
            stMatrix[0] * -rs + stMatrix[4] * rc,
            stMatrix[1] * -rs + stMatrix[5] * rc,
        )

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
        // -1 (silently ignored by glUniform*) for looks that don't declare them.
        uVidXLoc = GLES20.glGetUniformLocation(prog, "uVidX")
        uVidYLoc = GLES20.glGetUniformLocation(prog, "uVidY")
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
        private const val TAG = "AnalogLookRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /**
         * Creates a renderer and blocks (briefly, with a timeout) until the GL
         * context and camera-facing input surface are ready. Returns null if GL
         * initialisation fails on this device — callers must fall back to the
         * normal (non-GL) pipeline so video still works.
         */
        fun createOrNull(look: AnalogLook, videoWidth: Int, videoHeight: Int): AnalogLookRenderer? {
            val renderer = AnalogLookRenderer(look, videoWidth, videoHeight)
            val latch = CountDownLatch(1)
            var ok = false
            renderer.handler.post {
                try {
                    renderer.initOnGlThread()
                    ok = true
                } catch (t: Throwable) {
                    Log.e(TAG, "$look GL init failed", t)
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
         * Super-8 look, computed entirely in the shader (procedural — no .cube
         * file). Unlike a flat "grade on top of video" filter, the stages are
         * ordered the way light actually moves through a Super-8 camera and an
         * aged reversal print:
         *
         *  1. Lens: radial chromatic aberration, softness rising to the corners
         *     (the tiny f/1.8 zooms in Super-8 cameras were never sharp wide
         *     open, and the 5.8x4mm frame can't resolve digital sharpness).
         *  2. Light domain (linear): exposure/projector flicker, halation
         *     (an actual blur of neighbouring highlights bleeding red-orange
         *     through the emulsion — not a per-pixel brightness hack), warm
         *     daylight-filter balance, dye-crosstalk matrix (what really gives
         *     film its colour separation, far more than any curve).
         *  3. Emulsion response (per channel): S-curve with the blue layer
         *     rolling off first → warm highlights, brown-warm shadows, the
         *     Kodachrome signature.
         *  4. Age: lifted warm blacks + creamy whites (print fade), hue-aware
         *     saturation (reds stay vivid, skies wash out, highlights desaturate).
         *  5. Mechanics: vignette, multi-octave soft grain (luma-weighted, with
         *     a chroma component), persistent wobbling scratches (bright base-
         *     side and dark emulsion-side), round dark/bright dust, gate weave
         *     (vertex stage, fed per-frame from Kotlin).
         *
         * highp where the GPU has it: the grain/hash math overflows strict
         * mediump (half-float) ranges on some Mali GPUs; the hash also wraps
         * its domain so the mediump fallback stays artefact-free.
         */
        private const val SUPER8_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uTime;
            uniform float uSeed;
            uniform vec2 uResolution;

            float hash(vec2 p) {
                // Domain-wrapped so intermediates stay finite even in mediump.
                p = mod(p, 512.0);
                vec3 p3 = fract(vec3(p.x, p.y, p.x) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            // Bilinear value noise — soft blobs instead of per-pixel salt,
            // which is what real silver-halide grain clumps look like.
            float vnoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash(i);
                float b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0));
                float d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            void main() {
                vec2 uv = clamp(vTexCoord, 0.0, 1.0);
                vec2 q = uv - 0.5;
                float r2 = dot(q, q);
                vec2 px = 1.0 / uResolution;

                // --- Lens: radial chromatic aberration (zero at centre) ---
                vec2 ca = q * r2 * 0.010;
                vec3 col;
                col.r = texture2D(sTexture, clamp(uv + ca, 0.0, 1.0)).r;
                col.g = texture2D(sTexture, uv).g;
                col.b = texture2D(sTexture, clamp(uv - ca, 0.0, 1.0)).b;

                // --- Lens/film MTF + halation source, one hexagonal ring ---
                // Inner taps soften the image (more toward the corners), the
                // outer taps collect neighbouring highlights for halation.
                vec3 blurAcc = vec3(0.0);
                float halAcc = 0.0;
                for (int i = 0; i < 6; i++) {
                    float ang = float(i) * 1.0471976;
                    vec2 d = vec2(cos(ang), sin(ang));
                    blurAcc += texture2D(sTexture, clamp(uv + d * px * 1.6, 0.0, 1.0)).rgb;
                    vec3 h = texture2D(sTexture, clamp(uv + d * px * 6.0, 0.0, 1.0)).rgb;
                    halAcc += max(dot(h, vec3(0.299, 0.587, 0.114)) - 0.62, 0.0);
                }
                blurAcc /= 6.0;
                halAcc /= 6.0;
                float softAmt = 0.35 + 0.40 * smoothstep(0.05, 0.45, r2);
                col = mix(col, blurAcc, softAmt);

                // --- Light domain (approx. linear) ---
                vec3 lin = col * col;

                // Projector/shutter flicker is an exposure change, so it
                // belongs on linear light, not on graded pixels.
                float flick = 1.0
                    + 0.045 * sin(uTime * 6.7)
                    + 0.020 * sin(uTime * 17.3)
                    + (hash(vec2(uSeed, 1.7)) - 0.5) * 0.07;
                lin *= flick;

                // Halation: highlights bleed through the base and expose the
                // red-sensitive layer from behind — warm glow around lights.
                lin += vec3(1.0, 0.30, 0.08) * (halAcc * halAcc) * 1.6;

                // Warm daylight-filter balance (tungsten film + Wratten 85).
                lin *= vec3(1.06, 1.00, 0.90);

                // Dye crosstalk: film dyes are impure, and masking pushes the
                // channels apart. This matrix (rows sum to 1 → neutrals stay
                // neutral) is what makes colour read as "film" rather than
                // "video with a tint".
                lin = vec3(
                    dot(lin, vec3(1.14, -0.10, -0.04)),
                    dot(lin, vec3(-0.05, 1.13, -0.08)),
                    dot(lin, vec3(-0.03, -0.12, 1.15)));
                lin = max(lin, 0.0);

                col = sqrt(lin);

                // --- Emulsion response ---
                // Reversal-film S-curve, mid-tones anchored so exposure stays
                // honest; then per-channel gamma — the blue layer rolls off
                // first, which is where the warm Kodachrome cast really lives.
                vec3 s = col * col * (3.0 - 2.0 * col);
                col = mix(col, s, 0.42);
                col = pow(col, vec3(0.985, 1.0, 1.06));

                // Aged print fade: warm lifted blacks, creamy (not clipped)
                // whites. Replaces a flat "milky" lift.
                col = col * vec3(0.94, 0.92, 0.88) + vec3(0.050, 0.042, 0.032);

                // Hue-aware saturation: Kodachrome reds stay punchy, blue skies
                // wash out, and highlights desaturate toward warm white.
                float luma = dot(col, vec3(0.299, 0.587, 0.114));
                float redness = smoothstep(0.0, 0.4, col.r - max(col.g, col.b));
                float blueness = smoothstep(0.0, 0.4, col.b - max(col.r, col.g));
                float sat = 0.85 + 0.25 * redness - 0.20 * blueness
                    - 0.30 * smoothstep(0.75, 1.0, luma);
                col = mix(vec3(luma), col, clamp(sat, 0.0, 1.15));

                // --- Vignette: smooth roll-off, never a hard ring ---
                col *= 1.0 - smoothstep(0.10, 0.62, r2) * 0.45;

                // --- Film grain: two octaves of soft value noise, strongest
                // in shadows/mids (reversal stock), with a slight chroma
                // component so it doesn't read as digital luma noise.
                float gsz = max(uResolution.y / 360.0, 1.0);
                vec2 gp = uv * uResolution / gsz;
                vec2 gShift = vec2(hash(vec2(uSeed, 3.1)), hash(vec2(uSeed, 7.7))) * 100.0;
                float gn = vnoise(gp + gShift) * 0.65
                         + vnoise(gp * 0.5 + gShift.yx + 31.7) * 0.35;
                float gAmp = mix(0.085, 0.030, smoothstep(0.10, 0.85, luma));
                col += (gn - 0.5) * gAmp;
                float gc = vnoise(gp * 0.7 + gShift + 57.3);
                col += (gc - 0.5) * gAmp * vec3(0.35, -0.20, 0.30);

                // --- Scratches: persist for ~2 s, wobble per frame, intensity
                // broken along their length. Bright = base-side, dark =
                // emulsion-side (both exist on real prints).
                float ep = floor(uTime * 0.45);
                if (hash(vec2(ep, 11.0)) > 0.65) {
                    float sx = 0.08 + 0.84 * hash(vec2(ep, 23.0))
                        + (hash(vec2(uSeed, 31.0)) - 0.5) * 0.008;
                    float w = (0.6 + 0.9 * hash(vec2(ep, 41.0))) * px.x;
                    float li = 1.0 - smoothstep(0.0, w, abs(uv.x - sx));
                    li *= smoothstep(0.25, 0.6, vnoise(vec2(uv.y * 70.0, ep * 13.0 + uSeed)));
                    col += li * 0.30;
                }
                if (hash(vec2(ep, 53.0)) > 0.78) {
                    float sx2 = 0.08 + 0.84 * hash(vec2(ep, 67.0))
                        + (hash(vec2(uSeed, 71.0)) - 0.5) * 0.006;
                    float w2 = (0.8 + 1.2 * hash(vec2(ep, 83.0))) * px.x;
                    float li2 = 1.0 - smoothstep(0.0, w2, abs(uv.x - sx2));
                    li2 *= smoothstep(0.3, 0.7, vnoise(vec2(uv.y * 50.0, ep * 17.0 + uSeed * 1.3)));
                    col *= 1.0 - li2 * 0.5;
                }

                // --- Dust: round specks, mostly dark dirt with the odd bright
                // fleck, roughly one per frame at 18 fps.
                float cells = 24.0;
                vec2 duv = vec2(uv.x * uResolution.x / uResolution.y, uv.y) * cells;
                vec2 cid = floor(duv);
                vec2 seedC = vec2(floor(uSeed * 91.0), floor(uSeed * 57.0));
                if (hash(cid + seedC) > 0.9985) {
                    vec2 c = vec2(hash(cid + seedC + 11.1), hash(cid + seedC + 17.7));
                    float rad = 0.10 + 0.22 * hash(cid + seedC + 23.3);
                    float spec = 1.0 - smoothstep(rad * 0.35, rad, length(fract(duv) - c));
                    if (hash(cid + seedC + 29.9) > 0.75) {
                        col += spec * 0.5;
                    } else {
                        col = mix(col, col * 0.2, spec * 0.9);
                    }
                }

                gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
        """

        /**
         * PAL consumer-camcorder VHS, modelled on the actual signal path
         * rather than "video with noise on top":
         *
         *  1. Bandwidth split — VHS records luma FM at ~3 MHz (≈240 lines)
         *     and chroma "color-under" at ~0.6 MHz (≈40 lines). Luma gets a
         *     mild horizontal soften plus the camcorder detail circuit's
         *     overshoot ringing; chroma is a wide LEFT-weighted horizontal
         *     average, so colour smears and trails to the right of edges —
         *     the classic VHS colour bleed.
         *  2. Geometry — per-scanline time-base error (each line lands
         *     slightly off), head-switching tear shearing the bottom ~15
         *     lines, a tracking "wrinkle" band that occasionally scrolls
         *     through the frame, and a rare one-frame vertical-hold bounce.
         *     All of it operates in VIDEO space via gl_FragCoord + uVidX/uVidY
         *     (texture-space directions of the video axes), so the artefacts
         *     stay horizontal in the final file in every device orientation.
         *  3. Colour — muted chroma, lifted video-style black, murky
         *     green-blue shadows, slight magenta push in highlights, slow
         *     AWB wander, FM white-clip compression, and per-line chroma
         *     gain wiggle (colour-under phase noise → faint rainbow shimmer).
         *  4. Tape damage — horizontally-streaked snow (strong in darks and
         *     inside the tracking band), and dropout "pop lines": bright
         *     (rarely dark) partial-width streaks with ragged tails.
         */
        private const val VHS_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uTime;
            uniform float uSeed;
            uniform vec2 uResolution;
            uniform vec2 uVidX;
            uniform vec2 uVidY;

            float hash(vec2 p) {
                p = mod(p, 512.0);
                vec3 p3 = fract(vec3(p.x, p.y, p.x) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            float vnoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash(i);
                float b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0));
                float d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            float lum(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            vec3 tap(vec2 base, float dx) {
                return texture2D(sTexture, clamp(base + uVidX * dx, 0.0, 1.0)).rgb;
            }

            void main() {
                // Video-space position: gl_FragCoord maps 1:1 onto the encoded
                // frame, y = 0 at the bottom of the displayed picture.
                vec2 vuv = gl_FragCoord.xy / uResolution;
                float line = floor(gl_FragCoord.y);

                // --- Time-base error: each scanline lands a touch off ---
                float jit = (hash(vec2(line, uSeed)) - 0.5) * 0.002
                          + sin(vuv.y * 9.0 + uTime * 1.1) * 0.0006;

                // --- Vertical-hold bounce: rare one-frame vertical jump
                // (~0.75% of frames) ---
                float jv = 0.0;
                if (hash(vec2(uSeed, 77.0)) > 0.9925) {
                    jv = (hash(vec2(uSeed, 78.0)) - 0.5) * 0.012;
                }

                // --- Head-switching tear: bottom lines shear sideways ---
                float hsBand = 1.0 - smoothstep(0.0, 0.035, vuv.y);
                if (hsBand > 0.0) {
                    jit += hsBand * (0.012
                        + (hash(vec2(line, uSeed + 9.0)) - 0.5) * 0.05 * hsBand);
                }

                // --- Tracking wrinkle: a distortion band that occasionally
                // scrolls down through the picture (~19% of 7 s windows) ---
                float ep = floor(uTime / 7.0);
                float trk = 0.0;
                if (hash(vec2(ep, 31.0)) > 0.81) {
                    float trkY = 1.1 - fract(uTime / 7.0) * 1.3;
                    float trkW = 0.02 + 0.025 * hash(vec2(ep, 37.0));
                    trk = 1.0 - smoothstep(0.0, trkW, abs(vuv.y - trkY));
                    jit += (vnoise(vec2(vuv.y * 160.0, uTime * 11.0)) - 0.5) * 0.10 * trk;
                }

                vec2 base = vTexCoord + uVidX * jit + uVidY * jv;

                // --- Bandwidth split: luma ~3 MHz, chroma ~0.6 MHz ---
                float px = 1.0 / uResolution.x;
                vec3 t3 = tap(base, -5.0 * px);
                vec3 t2 = tap(base, -2.5 * px);
                vec3 t1 = tap(base, -1.2 * px);
                vec3 t0 = tap(base, 0.0);
                vec3 u1 = tap(base, 1.2 * px);
                vec3 u2 = tap(base, 2.5 * px);
                vec3 u3 = tap(base, 5.0 * px);

                float yNarrow = lum(t0) * 0.5 + (lum(t1) + lum(u1)) * 0.25;
                float yWide = yNarrow * 0.6 + (lum(t2) + lum(u2)) * 0.2;
                // Camcorder "detail" circuit: unsharp overshoot → edge ringing.
                float ySharp = yNarrow + (yNarrow - yWide) * 1.1;

                // Left-weighted wide average → colour bleeds to the right.
                vec3 chroma = t3 * 0.16 + t2 * 0.24 + t1 * 0.22 + t0 * 0.18
                            + u1 * 0.12 + u2 * 0.06 + u3 * 0.02;

                // Smeared colour carried by the sharpened luma.
                vec3 col = chroma + (ySharp - lum(chroma));

                // Colour-under phase noise: per-line chroma gain wiggle.
                float cg = 0.82 + 0.36 * hash(vec2(line, uSeed + 13.0));
                col = vec3(ySharp) + (col - vec3(ySharp)) * cg;

                // --- VHS colour ---
                col = clamp(col, 0.0, 1.25);
                float y = lum(col);
                col = mix(vec3(y), col, 0.72);
                col = col * 0.93 + 0.035;
                col += vec3(0.012, -0.002, 0.009) * smoothstep(0.55, 1.0, y);
                col += vec3(-0.004, 0.006, 0.011) * (1.0 - smoothstep(0.0, 0.45, y));
                col.r *= 1.0 + 0.013 * sin(uTime * 0.31);
                col.b *= 1.0 + 0.013 * sin(uTime * 0.23 + 2.0);
                // FM white clip: highlights compress, never crisp.
                col -= max(col - 0.86, 0.0) * 0.45;

                // --- Tape noise: horizontally streaked snow ---
                float n = hash(vec2(floor(gl_FragCoord.x / 1.5), line) + uSeed);
                float nAmp = mix(0.055, 0.018, smoothstep(0.0, 0.7, y))
                           + trk * 0.22 + hsBand * 0.15;
                col += (n - 0.5) * nAmp;
                // The tracking band also rips the colour out.
                col = mix(col, vec3(lum(col)), trk * 0.6);

                // --- Dropout "pop lines": lost FM for part of a line
                // (~0.075% of line pairs per frame) ---
                float dline = floor(line / 2.0);
                if (hash(vec2(dline, floor(uSeed * 17.0))) > 0.99925) {
                    float x0 = hash(vec2(dline, uSeed + 3.3)) * 0.9;
                    float len = 0.04
                        + 0.6 * hash(vec2(dline, uSeed + 5.1)) * hash(vec2(dline, uSeed + 6.2));
                    float inSeg = step(x0, vuv.x) * step(vuv.x, x0 + len);
                    float tail = 1.0 - smoothstep(x0 + len * 0.4, x0 + len, vuv.x);
                    float dropI = inSeg * max(tail, 0.35);
                    if (hash(vec2(dline, uSeed + 8.8)) > 0.15) {
                        col = mix(col, vec3(0.95), dropI * 0.9);
                    } else {
                        col = mix(col, vec3(0.04), dropI * 0.85);
                    }
                }

                // --- Interlace hint: every other line a touch darker (kept
                // subtle so playback scaling can't moiré) ---
                col *= 1.0 - 0.035 * step(0.5, fract(line * 0.5));

                gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
        """
    }
}
