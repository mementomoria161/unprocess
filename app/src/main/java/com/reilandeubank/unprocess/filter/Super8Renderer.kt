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

/**
 * OpenGL ES 2.0 video pipeline that turns the raw camera stream into a
 * Super-8-style image and feeds it to both the on-screen preview and the
 * hardware encoder.
 *
 * Data flow:
 *
 *   Camera2 → [inputSurface] → SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
 *           → fragment shader (procedural LUT + grain + vignette + flicker
 *             + halation + gate-weave + scratches/dust)
 *           → preview EGLSurface  (rotated for the portrait viewfinder)
 *           → encoder EGLSurface  (sensor orientation; MediaRecorder applies
 *             its rotation hint at playback, exactly like the direct path)
 *
 * Everything runs on a dedicated GL thread. The camera writes frames to
 * [inputSurface]; each frame triggers [SurfaceTexture.OnFrameAvailableListener]
 * which schedules a draw on that thread.
 *
 * The Super-8 look is fully procedural — no .cube LUT file is needed, the
 * colour transform is computed in the shader (see [FRAGMENT_SHADER]).
 */
class Super8Renderer(
    val videoWidth: Int,
    val videoHeight: Int,
) {
    /** Surface the camera capture session draws into. Stable for the lifetime
     *  of this renderer, so the camera session can be re-created (e.g. on a
     *  lens switch) without tearing the renderer down. */
    lateinit var inputSurface: Surface
        private set

    private lateinit var surfaceTexture: SurfaceTexture

    private val thread = HandlerThread("Super8GL").apply { start() }
    private val handler = Handler(thread.looper)

    // EGL state
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    /** 1x1 offscreen surface kept current whenever no real target is bound,
     *  so the GL context (and the external texture) stays valid. */
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var previewSurface: Surface? = null
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewW = 0
    private var previewH = 0
    private val previewMvp = FloatArray(16)

    private var encoderSurface: Surface? = null
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private val encoderMvp = FloatArray(16)
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

    init {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                initEgl()
                makeCurrent(pbufferSurface)
                program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                oesTextureId = createOesTexture()
                surfaceTexture = SurfaceTexture(oesTextureId).apply {
                    setDefaultBufferSize(videoWidth, videoHeight)
                    setOnFrameAvailableListener { scheduleDraw() }
                }
                inputSurface = Surface(surfaceTexture)
            } catch (e: Exception) {
                Log.e(TAG, "GL init failed", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    /** Binds the on-screen preview output. [rotationDeg] rotates the frame for
     *  the portrait viewfinder; [mirror] flips horizontally for the selfie cam. */
    fun setPreview(surface: Surface?, width: Int, height: Int, rotationDeg: Int, mirror: Boolean) {
        handler.post {
            if (released) return@post
            releasePreviewEgl()
            previewSurface = surface
            previewW = width
            previewH = height
            // Geometry transform: rotate the textured quad so the landscape
            // camera frame lands upright in the portrait surface, mirroring
            // for the front lens. Texture sampling itself is handled by the
            // SurfaceTexture transform matrix in the vertex shader.
            Matrix.setIdentityM(previewMvp, 0)
            if (mirror) Matrix.scaleM(previewMvp, 0, -1f, 1f, 1f)
            Matrix.rotateM(previewMvp, 0, rotationDeg.toFloat(), 0f, 0f, 1f)
            if (surface != null && surface.isValid) {
                try {
                    previewEgl = createWindowSurface(surface)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create preview EGL surface", e)
                    previewEgl = EGL14.EGL_NO_SURFACE
                }
            }
        }
    }

    /** Starts/stops feeding the hardware encoder. Pass the encoder's input
     *  surface (from MediaRecorder/MediaCodec) to begin, or null to stop. */
    fun setEncoder(surface: Surface?) {
        // Stop new encoder draws as early as possible so MediaRecorder.stop()
        // isn't racing with a frame still being submitted.
        if (surface == null) recording = false
        handler.post {
            if (released) return@post
            if (surface == null) {
                recording = false
                releaseEncoderEgl()
                encoderSurface = null
                return@post
            }
            encoderSurface = surface
            // Encoder receives the frame in sensor orientation; the recorder's
            // orientation hint rotates it on playback (matches the non-GL path).
            Matrix.setIdentityM(encoderMvp, 0)
            try {
                encoderEgl = createWindowSurface(surface)
                recording = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create encoder EGL surface", e)
                encoderEgl = EGL14.EGL_NO_SURFACE
                recording = false
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                releaseEncoderEgl()
                releasePreviewEgl()
                if (program != 0) GLES20.glDeleteProgram(program)
                if (oesTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                if (::surfaceTexture.isInitialized) {
                    surfaceTexture.setOnFrameAvailableListener(null)
                    surfaceTexture.release()
                }
                if (::inputSurface.isInitialized) inputSurface.release()
                releaseEgl()
            } catch (e: Exception) {
                Log.w(TAG, "Error during release", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
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
            // updateTexImage needs a current context; keep the pbuffer current
            // when there's no preview surface yet.
            val anchor = if (previewEgl != EGL14.EGL_NO_SURFACE) previewEgl else pbufferSurface
            makeCurrent(anchor)
            // Always consume the buffer (releases it back to the camera) before
            // deciding whether to draw it.
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
            val timestampNs = surfaceTexture.timestamp

            // Pace to ~18 fps regardless of the sensor's actual rate, so the
            // Super-8 cadence (and its choppy motion) is consistent. Allow a
            // small slack so we don't accidentally halve the rate.
            if (timestampNs > 0 && lastDrawTs > 0 &&
                timestampNs - lastDrawTs < (FRAME_INTERVAL_NS * 0.85).toLong()
            ) {
                return
            }
            lastDrawTs = timestampNs
            frameCount++

            previewSurface?.let { surf ->
                if (previewEgl != EGL14.EGL_NO_SURFACE && surf.isValid) {
                    makeCurrent(previewEgl)
                    // Query the live surface size — the fixed-size change on the
                    // SurfaceView is async, so don't trust a cached width/height.
                    render(surfaceWidth(previewEgl), surfaceHeight(previewEgl), previewMvp)
                    EGL14.eglSwapBuffers(eglDisplay, previewEgl)
                }
            }

            if (recording && encoderEgl != EGL14.EGL_NO_SURFACE) {
                makeCurrent(encoderEgl)
                render(videoWidth, videoHeight, encoderMvp)
                EGLExt_setPresentationTime(encoderEgl, if (timestampNs > 0) timestampNs else System.nanoTime())
                EGL14.eglSwapBuffers(eglDisplay, encoderEgl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "drawFrame error", e)
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
    }

    private fun createWindowSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val egl = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
        if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
        }
        return egl
    }

    private fun surfaceWidth(surface: EGLSurface): Int {
        val v = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, surface, EGL14.EGL_WIDTH, v, 0)
        return if (v[0] > 0) v[0] else previewW
    }

    private fun surfaceHeight(surface: EGLSurface): Int {
        val v = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, surface, EGL14.EGL_HEIGHT, v, 0)
        return if (v[0] > 0) v[0] else previewH
    }

    private fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed: ${EGL14.eglGetError()}")
        }
    }

    private fun EGLExt_setPresentationTime(surface: EGLSurface, nsecs: Long) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, surface, nsecs)
    }

    private fun releasePreviewEgl() {
        if (previewEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, previewEgl)
            previewEgl = EGL14.EGL_NO_SURFACE
        }
    }

    private fun releaseEncoderEgl() {
        if (encoderEgl != EGL14.EGL_NO_SURFACE) {
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

                // --- Procedural LUT: warm, faded film grade ---
                // Lift blacks for the milky-shadow look.
                col = col * 0.93 + 0.055;
                // Gentle S-curve for soft film contrast.
                col = (col - 0.5) * 1.10 + 0.5;
                // Warm white balance (boost red, pull blue) — Kodak warmth.
                col.r *= 1.07;
                col.g *= 1.015;
                col.b *= 0.90;
                // Slight overall desaturation.
                float luma = dot(col, vec3(0.299, 0.587, 0.114));
                col = mix(vec3(luma), col, 0.84);
                // Warm split-tone: amber highlights, brown-ish shadows.
                col += vec3(0.04, 0.015, -0.02) * smoothstep(0.45, 1.0, luma);
                col += vec3(0.03, 0.008, -0.015) * (1.0 - smoothstep(0.0, 0.5, luma));

                // --- Halation: warm glow bleeding from highlights ---
                col += vec3(0.10, 0.035, 0.0) * pow(max(luma - 0.62, 0.0), 2.0) * 3.0;

                // --- Vignette ---
                vec2 q = uv - 0.5;
                float vig = 1.0 - dot(q, q) * 1.25;
                vig = clamp(vig, 0.0, 1.0);
                col *= mix(0.45, 1.0, vig);

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
