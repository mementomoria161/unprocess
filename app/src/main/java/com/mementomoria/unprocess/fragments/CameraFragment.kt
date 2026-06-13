/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mementomoria.unprocess.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.widget.Toast
import android.content.res.ColorStateList
import android.view.HapticFeedbackConstants
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.mementomoria.unprocess.utils.computeExifOrientation
import com.mementomoria.unprocess.utils.computeJpegOrientation
import com.mementomoria.unprocess.utils.getPreviewOutputSize
import com.mementomoria.unprocess.utils.OrientationLiveData
import com.mementomoria.unprocess.R
import com.mementomoria.unprocess.databinding.FragmentCameraBinding
import com.mementomoria.unprocess.filter.FilmFilter
import com.mementomoria.unprocess.filter.FilmSimulation
import com.mementomoria.unprocess.filter.AnalogLook
import com.mementomoria.unprocess.filter.AnalogLookRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import android.widget.LinearLayout
import java.io.FileInputStream

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics
        get() = cameraManager.getCameraCharacteristics(currentCameraId)

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /**
     * Smooth white shutter flash at the moment the HAL signals capture start.
     * Triggers a brief bright "blink" of the preview, then fades out — the
     * dim overlay (managed separately) takes over right after for the rest
     * of the save.
     */
    private val shutterFlashTask: Runnable by lazy {
        Runnable {
            val overlay = _fragmentCameraBinding?.overlay ?: return@Runnable
            overlay.setBackgroundColor(Color.WHITE)
            overlay.alpha = 1.0f
            overlay.animate()
                .alpha(0f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    _fragmentCameraBinding?.overlay?.setBackgroundColor(Color.TRANSPARENT)
                }
                .start()
        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Job used to manage camera initialization */
    private var cameraJob: kotlinx.coroutines.Job? = null

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    private var currentCameraId: String = ""
    private enum class OutputFormat { RAW, JPEG, WEBP }
    private var outputFormat: OutputFormat = OutputFormat.JPEG
    private var flashMode: Int = CaptureRequest.CONTROL_AE_MODE_ON
    private enum class AspectRatio { RATIO_1_1, RATIO_4_3, RATIO_16_9 }
    private var aspectRatio: AspectRatio = AspectRatio.RATIO_4_3
    private var photoAspectRatio: AspectRatio = AspectRatio.RATIO_4_3
    private var videoAspectRatio: AspectRatio = AspectRatio.RATIO_4_3

    private enum class VideoResolution { MAX, QHD_1440P, FHD_1080P, SD_480P }
    private var videoResolution: VideoResolution = VideoResolution.MAX
    private var videoFrameRate: Int = 30

    /** Video looks. NORMAL is the direct (unprocessed) pipeline; SUPER8 and
     *  VHS route the camera through [AnalogLookRenderer] for a procedural
     *  Super-8 film / PAL-VHS camcorder look baked into the recording. */
    private enum class VideoPreset { NORMAL, SUPER8, VHS }
    private var videoPreset: VideoPreset = VideoPreset.NORMAL

    /** GL pipeline used only in SUPER8/VHS video mode; null otherwise. */
    private var analogRenderer: AnalogLookRenderer? = null



    private var isSettingsMode = false
    private var isAnimatingSettings = false
    private var isCameraInitializing = false

    private var isVideoMode: Boolean = false
    private var isRecordingVideo: Boolean = false
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var videoUri: android.net.Uri? = null
    private var videoFileDescriptor: android.os.ParcelFileDescriptor? = null
    private var currentVideoFile: File? = null

    private var persistentSurface: Surface? = null
    private var isCameraClosed = true

    /** Currently selected film simulation. Cycled via the filter toggle. */
    private var filmSimulation: FilmSimulation = FilmSimulation.NORMAL
    private var savedPhotoFilmSimulation: FilmSimulation = FilmSimulation.NORMAL

    private val physicalToLogicalMap = HashMap<String, String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val sharedPrefs = requireContext().getSharedPreferences("unprocess_settings", Context.MODE_PRIVATE)
        val savedFormat = sharedPrefs.getString("pref_output_format", null)
        outputFormat = when (savedFormat) {
            "RAW" -> OutputFormat.RAW
            "WEBP" -> OutputFormat.WEBP
            "JPEG" -> OutputFormat.JPEG
            else -> when (args.outputFormat.uppercase()) {
                "RAW" -> OutputFormat.RAW
                "WEBP" -> OutputFormat.WEBP
                else -> OutputFormat.JPEG
            }
        }
        val savedPhotoRatio = sharedPrefs.getString("pref_photo_aspect_ratio", AspectRatio.RATIO_4_3.name)
        photoAspectRatio = try {
            AspectRatio.valueOf(savedPhotoRatio ?: AspectRatio.RATIO_4_3.name)
        } catch (e: Exception) {
            if (sharedPrefs.getBoolean("pref_is_square", false)) {
                AspectRatio.RATIO_1_1
            } else {
                AspectRatio.RATIO_4_3
            }
        }

        val savedVideoRatio = sharedPrefs.getString("pref_video_aspect_ratio", AspectRatio.RATIO_4_3.name)
        videoAspectRatio = try {
            AspectRatio.valueOf(savedVideoRatio ?: AspectRatio.RATIO_4_3.name)
        } catch (e: Exception) {
            AspectRatio.RATIO_4_3
        }

        flashMode = sharedPrefs.getInt("pref_flash_mode", CaptureRequest.CONTROL_AE_MODE_ON)
        isVideoMode = sharedPrefs.getBoolean("pref_is_video_mode", false)
        // Must come AFTER isVideoMode is loaded — reading it earlier made a
        // cold start in video mode lay out with the photo aspect ratio.
        aspectRatio = if (isVideoMode) videoAspectRatio else photoAspectRatio
        
        videoFrameRate = sharedPrefs.getInt("pref_video_fps", 30)
        val savedResName = sharedPrefs.getString("pref_video_resolution", VideoResolution.MAX.name)
        videoResolution = try {
            VideoResolution.valueOf(savedResName ?: VideoResolution.MAX.name)
        } catch (e: Exception) {
            VideoResolution.MAX
        }

        val savedPreset = sharedPrefs.getString("pref_video_preset", VideoPreset.NORMAL.name)
        videoPreset = try {
            VideoPreset.valueOf(savedPreset ?: VideoPreset.NORMAL.name)
        } catch (e: Exception) {
            VideoPreset.NORMAL
        }
        // The analogue presets are defined by their native cadence — lock it in.
        when (videoPreset) {
            VideoPreset.SUPER8 -> videoFrameRate = 18
            VideoPreset.VHS -> videoFrameRate = 25
            VideoPreset.NORMAL -> {}
        }
        // The analogue presets are locked to 4:3. Apply that before the first
        // updateViewfinderRatio() below so the container never starts out with
        // a shape the camera pipeline will immediately abandon.
        if (isVideoMode && videoPreset != VideoPreset.NORMAL) {
            aspectRatio = AspectRatio.RATIO_4_3
        }

        val savedFilm = sharedPrefs.getString("pref_film_simulation", null)
        val loadedFilm = if (savedFilm != null) {
            try {
                FilmSimulation.valueOf(savedFilm)
            } catch (e: Exception) {
                FilmSimulation.NORMAL
            }
        } else {
            FilmSimulation.NORMAL
        }

        if (isVideoMode) {
            savedPhotoFilmSimulation = loadedFilm
            filmSimulation = FilmSimulation.NORMAL
        } else {
            filmSimulation = loadedFilm
            savedPhotoFilmSimulation = loadedFilm
        }
        
        if (outputFormat == OutputFormat.RAW) {
            filmSimulation = FilmSimulation.NORMAL
            savedPhotoFilmSimulation = FilmSimulation.NORMAL
        }

        currentCameraId = args.cameraId

        updateViewfinderRatio()
        updateModeToggleUI()
        updateFlashUI()
        updateAspectRatioUI()
        updateMovieToggleUI()
        updateSettingsUI()
        // Set the capture button label to match the persisted mode on cold
        // launch (otherwise it keeps the XML default "Capture" even in video mode).
        updateCaptureButtonForState()
        setupLensSelector()

        fragmentCameraBinding.modeToggle?.setOnClickListener {
            if (isVideoMode) return@setOnClickListener
            outputFormat = when (outputFormat) {
                OutputFormat.RAW -> OutputFormat.JPEG
                OutputFormat.JPEG -> OutputFormat.WEBP
                OutputFormat.WEBP -> OutputFormat.RAW
            }
            if (outputFormat == OutputFormat.RAW) {
                filmSimulation = FilmSimulation.NORMAL
                savedPhotoFilmSimulation = FilmSimulation.NORMAL
            }
            updateModeToggleUI()
            updateSettingsUI()
            saveSettings()
            // The capture format is always RAW_SENSOR — only the save path
            // differs (DNG vs DNG→Bitmap→JPEG/WEBP). No need to restart the session.
        }

        fragmentCameraBinding.aspectRatioToggle?.setOnClickListener {
            if (isRecordingVideo || isProcessing) return@setOnClickListener
            // The analogue presets are locked to 4:3.
            if (usesGlPipeline()) return@setOnClickListener
            aspectRatio = when (aspectRatio) {
                AspectRatio.RATIO_4_3 -> AspectRatio.RATIO_1_1
                AspectRatio.RATIO_1_1 -> AspectRatio.RATIO_16_9
                AspectRatio.RATIO_16_9 -> AspectRatio.RATIO_4_3
            }
            if (isVideoMode) {
                videoAspectRatio = aspectRatio
            } else {
                photoAspectRatio = aspectRatio
            }
            updateAspectRatioUI()
            if (!isShowingDone) {
                updateViewfinderRatio()
                initializeCamera()
            }
            saveSettings()
        }

        fragmentCameraBinding.resolutionToggle?.setOnClickListener {
            if (isRecordingVideo || isProcessing || !isVideoMode) return@setOnClickListener
            val supported = getSupportedVideoResolutions()
            if (supported.isNotEmpty()) {
                val nextIndex = (supported.indexOf(videoResolution) + 1) % supported.size
                videoResolution = supported[nextIndex]
                updateSettingsUI()
                saveSettings()
                if (!isShowingDone) {
                    initializeCamera()
                }
            }
        }

        fragmentCameraBinding.framerateToggle?.setOnClickListener {
            if (isRecordingVideo || isProcessing || !isVideoMode) return@setOnClickListener
            val supported = getSupportedVideoFramerates()
            if (supported.isNotEmpty()) {
                val nextIndex = (supported.indexOf(videoFrameRate) + 1) % supported.size
                videoFrameRate = supported[nextIndex]
                updateSettingsUI()
                saveSettings()
                if (!isShowingDone) {
                    initializeCamera()
                }
            }
        }

        fragmentCameraBinding.flashToggle?.setOnClickListener {
            flashMode = if (flashMode == CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH) {
                CaptureRequest.CONTROL_AE_MODE_ON
            } else {
                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            }
            updateFlashUI()
            updateSettingsUI()
            saveSettings()

            // While the review overlay is up the preview is intentionally
            // stopped — the new flash mode is applied by resumePreview().
            if (isShowingDone) return@setOnClickListener

            // Try to update the existing session if possible
            try {
                if (::session.isInitialized) {
                    val template = if (isVideoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                    val captureRequest = camera.createCaptureRequest(template).apply {
                        addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                        if (isRecordingVideo) {
                            val recordTarget = if (usesGlPipeline()) {
                                analogRenderer?.inputSurfaceOrNull()
                            } else {
                                persistentSurface
                            }
                            recordTarget?.let { addTarget(it) }
                        }

                        applyCaptureRequestSettings(this)
                    }
                    session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
                } else {
                    initializeCamera()
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to update flash mode on existing session, re-initializing", exc)
                initializeCamera()
            }
        }

        fragmentCameraBinding.cameraToggle?.isHapticFeedbackEnabled = false
        fragmentCameraBinding.cameraToggle?.isSoundEffectsEnabled = false
        fragmentCameraBinding.cameraToggle?.setOnClickListener {
            if (isRecordingVideo || isProcessing) return@setOnClickListener
            if (isVideoMode) {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                toggleCameraVideoMode(false)
            }
        }

        fragmentCameraBinding.videoToggle?.isHapticFeedbackEnabled = false
        fragmentCameraBinding.videoToggle?.isSoundEffectsEnabled = false
        fragmentCameraBinding.videoToggle?.setOnClickListener {
            if (isRecordingVideo || isProcessing) return@setOnClickListener
            if (!isVideoMode) {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                toggleCameraVideoMode(true)
            }
        }

        fragmentCameraBinding.settingsToggle?.setOnClickListener {
            toggleSettingsMode()
        }

        fragmentCameraBinding.settingsDimOverlay?.setOnClickListener {
            if (isSettingsMode) {
                toggleSettingsMode()
            }
        }

        fragmentCameraBinding.filterToggle?.setOnClickListener {
            if (isVideoMode) return@setOnClickListener
            filmSimulation = filmSimulation.next()
            savedPhotoFilmSimulation = filmSimulation
            updateFilterUI()
            updateSettingsUI()
            saveSettings()
        }

        fragmentCameraBinding.presetToggle?.setOnClickListener {
            if (isRecordingVideo || isProcessing || !isVideoMode) return@setOnClickListener
            videoPreset = when (videoPreset) {
                VideoPreset.NORMAL -> VideoPreset.SUPER8
                VideoPreset.SUPER8 -> VideoPreset.VHS
                VideoPreset.VHS -> VideoPreset.NORMAL
            }
            // The analogue presets force their cadence + a capped, encoder-safe
            // resolution; leaving them restores a sensible frame-rate default.
            when (videoPreset) {
                VideoPreset.SUPER8 -> coerceSuper8VideoSettings()
                VideoPreset.VHS -> coerceVhsVideoSettings()
                VideoPreset.NORMAL -> if (videoFrameRate == 18 || videoFrameRate == 25) {
                    videoFrameRate = if (30 in getSupportedVideoFramerates()) 30 else getSupportedVideoFramerates().firstOrNull() ?: 30
                }
            }
            updateSettingsUI()
            saveSettings()
            // The pipeline differs between presets — rebuild the session.
            if (!isShowingDone) {
                initializeCamera()
            }
        }

        val navOffsetListener = androidx.core.view.OnApplyWindowInsetsListener { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                if (v.id == R.id.gallery_button) {
                    lp.bottomMargin = 48.dpToPx() + systemBars.bottom
                    v.layoutParams = lp
                } else if (v.id == R.id.capture_button) {
                    lp.marginEnd = 32.dpToPx() + systemBars.right
                    v.layoutParams = lp
                }
            }
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(fragmentCameraBinding.captureButton, navOffsetListener)
        fragmentCameraBinding.galleryButton?.let {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(it, navOffsetListener)
        }

        fragmentCameraBinding.galleryButton?.setOnClickListener {
            openRecentPhoto()
        }

        // Capture button — registered ONCE. It does double duty:
        //   - normal mode: trigger a capture
        //   - Done-overlay mode: act as "Take new" (dismisses the saved
        //     image, restores the live preview, re-arms capture)
        // The button stays disabled while a save is in flight; the handler
        // defensively checks the session before doing any work.
        fragmentCameraBinding.captureButton.setOnClickListener { button ->
            if (isCameraInitializing) return@setOnClickListener
            if (isShowingDone) {
                hideProgress()
            } else if (isVideoMode) {
                if (isRecordingVideo) {
                    stopRecordingVideo()
                } else {
                    startRecordingVideo()
                }
            } else {
                handleCaptureClick(button)
            }
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Initialize immediately: the container/viewfinder geometry is
                // derived from the selected aspect ratio (not from layout
                // timing), and the preview buffer has a fixed size the
                // compositor scales correctly regardless of when layout
                // settles — so no settle delays or double initialization.
                if (_fragmentCameraBinding != null && isAdded && !isShowingDone) {
                    initializeCamera()
                }
            }
        })

        // Camera-independent device-rotation source. The final JPEG orientation
        // is computed per-capture using the *current* lens's characteristics —
        // critical because switching front ↔ back changes the sensor orientation.
        relativeOrientation = OrientationLiveData(requireContext()).apply {
            observe(viewLifecycleOwner, Observer { deviceCw ->
                Log.d(TAG, "Device rotation changed: ${deviceCw}° CW")
            })
        }
    }

    private fun updateModeToggleUI() {
        val binding = _fragmentCameraBinding ?: return
        if (isVideoMode) {
            binding.modeToggle?.text = "File Format MP4"
            binding.modeToggle?.isEnabled = false
            binding.modeToggle?.let { setButtonActiveStyle(it, false) }
        } else {
            binding.modeToggle?.text = "File Format ${outputFormat.name}"
            val modeToggleAvailable = !isProcessing && !isShowingDone
            binding.modeToggle?.isEnabled = modeToggleAvailable
            binding.modeToggle?.let { setButtonActiveStyle(it, modeToggleAvailable) }
        }
    }

    private fun updateFlashUI() {
        val iconRes = if (flashMode == CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH) {
            R.drawable.ic_flash_on
        } else {
            R.drawable.ic_flash_off
        }
        fragmentCameraBinding.flashToggle?.text = ""
        fragmentCameraBinding.flashToggle?.setIconResource(iconRes)
    }

    private fun updateAspectRatioUI() {
        val ratioText = when (aspectRatio) {
            AspectRatio.RATIO_1_1 -> getString(R.string.aspect_ratio_square)
            AspectRatio.RATIO_4_3 -> getString(R.string.aspect_ratio_full)
            AspectRatio.RATIO_16_9 -> getString(R.string.aspect_ratio_16_9)
        }
        fragmentCameraBinding.aspectRatioToggle?.text = "Aspect Ratio $ratioText"
    }

    private var allCameraIds: List<String> = emptyList()

    private data class LensEntry(
        val id: String,
        val focalLength: Float,
        val label: String,
        val effectiveZoom: Float,
    )

    private fun setupLensSelector() {
        val container = fragmentCameraBinding.lensSelectorContainer
        container?.removeAllViews()

        val cameraIdList = cameraManager.cameraIdList
        Log.d(TAG, "Lens selector — cameras found: ${cameraIdList.joinToString()}")

        // Expand logical BACK cameras into their physical children where
        // possible (so the user gets per-focal-length buttons on multi-lens
        // back setups).
        //
        // FRONT cameras are intentionally NOT expanded — opening a physical
        // sub-camera of a logical front camera (e.g. on Pixel) bypasses the
        // system's tuned preview pipeline.
        physicalToLogicalMap.clear()
        val expandedIds = LinkedHashSet<String>()
        for (id in cameraIdList) {
            val ch = cameraManager.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
            val physicalIds = if (!isFront && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ch.physicalCameraIds
            } else emptySet()
            
            for (pid in physicalIds) {
                physicalToLogicalMap[pid] = id
            }
            
            if (physicalIds.isNotEmpty()) {
                expandedIds.addAll(physicalIds)
            } else {
                expandedIds.add(id)
            }
        }

        // Drop any logical BACK camera whose physical children we already
        // included — keeps the selector free of duplicates.
        val filteredIds = expandedIds.filter { id ->
            val ch = cameraManager.getCameraCharacteristics(id)
            val isFront = ch.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            if (isFront) return@filter true
            val children = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ch.physicalCameraIds
            } else emptySet()
            children.isEmpty() || children.none { it in expandedIds }
        }

        // Build list of raw entries with characteristics
        val rawEntries = filteredIds.map { id ->
            val ch = cameraManager.getCameraCharacteristics(id)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            Triple(id, focal, facing)
        }

        // Sort: Front facing first (priority 0), then Back facing (priority 1).
        // Within each category, sort by focal length ascending.
        val sortedEntries = rawEntries.sortedWith(compareBy<Triple<String, Float, Int>> {
            if (it.third == CameraCharacteristics.LENS_FACING_FRONT) 0 else 1
        }.thenBy { it.second })

        // Check if there are multiple front cameras
        val frontCount = sortedEntries.count { it.third == CameraCharacteristics.LENS_FACING_FRONT }

        val usedLabels = HashSet<String>()
        val entries = sortedEntries.map { (id, focal, facing) ->
            val ch = cameraManager.getCameraCharacteristics(id)
            val mainIdForFacing = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "1" else "0"
            val (mainFocal, mainSensorWidth) = try {
                val mainCh = cameraManager.getCameraCharacteristics(mainIdForFacing)
                val f = mainCh.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.3f
                val physicalSize = mainCh.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val activeRect = mainCh.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val width = physicalSize?.width ?: activeRect?.width()?.toFloat() ?: 1f
                Pair(f, width)
            } catch (e: Exception) {
                Pair(4.3f, 1f)
            }

            val currentSensorWidth = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
                ?: ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.width()?.toFloat()
                ?: 1f
            
            // Calculate total effective zoom (optical * digital sensor crop)
            val opticalZoom = if (focal > 0f && mainFocal > 0f) focal / mainFocal else 1f
            val cropZoom = if (mainSensorWidth > 0f && currentSensorWidth > 0f) mainSensorWidth / currentSensorWidth else 1f
            val effectiveZoom = opticalZoom * cropZoom

            val base = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                if (frontCount > 1) {
                    "F (${labelForZoom(effectiveZoom)})"
                } else {
                    "F"
                }
            } else {
                labelForZoom(effectiveZoom)
            }

            val label = if (usedLabels.add(base)) base else "$base ($id)".also { usedLabels.add(it) }
            LensEntry(id, focal, label, effectiveZoom)
        }

        allCameraIds = entries.map { it.id }
        Log.d(TAG, "Lens selector — final entries: $entries")

        if (entries.isEmpty()) {
            fragmentCameraBinding.lensSelectorCard?.visibility = View.GONE
            return
        }
        fragmentCameraBinding.lensSelectorCard?.visibility = View.VISIBLE

        entries.forEach { entry ->
            val button = com.google.android.material.button.MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = entry.label
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()).apply {
                    setMargins(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
                }
                setPadding(0, 0, 0, 0)
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minHeight = 0
                cornerRadius = 22.dpToPx()
                strokeWidth = 0
                setOnClickListener {
                    if (currentCameraId != entry.id) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        switchCamera(entry.id)
                    }
                }
            }
            container?.addView(button)
        }

        // Resolve logical camera ID to the matching physical camera ID on startup
        val matchingEntries = entries.filter { it.id == currentCameraId || physicalToLogicalMap[it.id] == currentCameraId }
        val resolvedEntry = if (matchingEntries.isNotEmpty()) {
            matchingEntries.minByOrNull { kotlin.math.abs(it.effectiveZoom - 1.0f) }
        } else {
            entries.firstOrNull()
        }
        if (resolvedEntry != null && currentCameraId != resolvedEntry.id) {
            currentCameraId = resolvedEntry.id
        }

        updateLensHighlight()
    }

    /** Dynamically calculates zoom factor label. */
    private fun labelForZoom(zoom: Float): String {
        val formatted = String.format(Locale.US, "%.1f", zoom)
        return if (formatted.endsWith(".0")) {
            formatted.substringBefore(".0") + "x"
        } else {
            "${formatted}x"
        }
    }

    fun triggerCapture() {
        if (isShowingDone) return
        val button = _fragmentCameraBinding?.captureButton ?: return
        if (button.isEnabled) {
            button.performClick()
        }
    }

    private fun toggleSettingsMode() {
        isSettingsMode = !isSettingsMode
        animateSettingsMenu(isSettingsMode)
        updateSettingsUI()
    }

    private fun animateSettingsMenu(open: Boolean) {
        val binding = _fragmentCameraBinding ?: return
        val panel = binding.settingsPanel ?: return
        val overlay = binding.settingsDimOverlay ?: return
        
        val toggles = listOfNotNull(
            binding.filterToggle,
            binding.presetToggle,
            binding.aspectRatioToggle,
            binding.resolutionToggle,
            binding.framerateToggle,
            binding.modeToggle
        ).filter { it.visibility == View.VISIBLE }
        
        if (toggles.isEmpty()) {
            isAnimatingSettings = false
            if (!open) {
                panel.visibility = View.GONE
                overlay.visibility = View.GONE
            }
            return
        }
        
        isAnimatingSettings = true
        
        if (open) {
            // Cancel any running animations
            overlay.animate().cancel()
            toggles.forEach { it.animate().cancel() }
            
            // Set initial state
            overlay.visibility = View.VISIBLE
            overlay.alpha = 0f
            overlay.animate()
                .alpha(1f)
                .setDuration(250L)
                .setListener(null)
                .start()
                
            panel.visibility = View.VISIBLE
            
            val startTranslationX = 100.dpToPx().toFloat()
            toggles.forEachIndexed { index, button ->
                button.alpha = 0f
                button.scaleX = 0.3f
                button.scaleY = 0.3f
                button.translationX = startTranslationX
                button.translationY = 0f
                
                button.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(280L)
                    .setStartDelay(index * 60L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
                    .setListener(if (index == toggles.lastIndex) {
                        object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                isAnimatingSettings = false
                            }
                        }
                    } else null)
                    .start()
            }
        } else {
            // Cancel any running animations
            overlay.animate().cancel()
            toggles.forEach { it.animate().cancel() }
            
            overlay.animate()
                .alpha(0f)
                .setDuration(250L)
                .setListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        overlay.visibility = View.GONE
                    }
                })
                .start()
                
            val endTranslationX = 100.dpToPx().toFloat()
            val reversedToggles = toggles.reversed()
            reversedToggles.forEachIndexed { index, button ->
                button.animate()
                    .alpha(0f)
                    .scaleX(0.3f)
                    .scaleY(0.3f)
                    .translationX(endTranslationX)
                    .translationY(0f)
                    .setDuration(200L)
                    .setStartDelay(index * 50L)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .setListener(if (index == reversedToggles.lastIndex) {
                        object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                panel.visibility = View.GONE
                                isAnimatingSettings = false
                            }
                        }
                    } else null)
                    .start()
            }
        }
    }

    private fun updateSettingsUI() {
        val binding = _fragmentCameraBinding ?: return
        
        // Always show the standard settings gear icon
        binding.settingsToggle?.setIconResource(R.drawable.ic_settings)
        
        // Settings gear button is highlighted (active) when settings mode is OFF (inactive),
        // and grayed out (inactive) when settings mode is ON (active).
        // Disabled during processing, video recording, and while the review
        // overlay is up (changing session-affecting settings there would
        // desync them from the still-configured camera session).
        val settingsAvailable = !isProcessing && !isRecordingVideo && !isShowingDone
        binding.settingsToggle?.isEnabled = settingsAvailable
        binding.settingsToggle?.let { setButtonActiveStyle(it, !isSettingsMode && settingsAvailable) }
        
        // Flash toggle is always visible and always active
        binding.flashToggle?.visibility = View.VISIBLE
        setButtonActiveStyle(binding.flashToggle, true)

        // Update selector visibilities based on Video Mode
        if (isVideoMode) {
            binding.modeToggle?.visibility = View.GONE
            binding.resolutionToggle?.visibility = View.VISIBLE
            binding.framerateToggle?.visibility = View.VISIBLE
            binding.presetToggle?.visibility = View.VISIBLE
            binding.filterToggle?.visibility = View.GONE
        } else {
            binding.modeToggle?.visibility = View.VISIBLE
            binding.resolutionToggle?.visibility = View.GONE
            binding.framerateToggle?.visibility = View.GONE
            binding.presetToggle?.visibility = View.GONE
            binding.filterToggle?.visibility = View.VISIBLE
        }
        
        if (!isAnimatingSettings) {
            binding.settingsPanel?.visibility = if (isSettingsMode) View.VISIBLE else View.GONE
            binding.settingsDimOverlay?.visibility = if (isSettingsMode) View.VISIBLE else View.GONE
            
            if (isSettingsMode) {
                listOfNotNull(
                    binding.aspectRatioToggle,
                    binding.resolutionToggle,
                    binding.framerateToggle,
                    binding.presetToggle,
                    binding.modeToggle,
                    binding.filterToggle
                ).forEach { button ->
                    button.alpha = 1f
                    button.scaleX = 1f
                    button.scaleY = 1f
                    button.translationX = 0f
                    button.translationY = 0f
                }
            }
        }

        // The analogue presets are locked to 4:3, so the aspect-ratio toggle
        // is disabled there.
        val aspectAvailable = !isProcessing && !isRecordingVideo && !isShowingDone && !usesGlPipeline()
        binding.aspectRatioToggle?.isEnabled = aspectAvailable
        setButtonActiveStyle(binding.aspectRatioToggle, aspectAvailable)

        val videoSettingsAvailable = !isProcessing && !isRecordingVideo && !isShowingDone && isVideoMode

        // SUPER8/VHS lock both resolution (encoder-safe 480p) and frame rate
        // (18/25 fps), so those toggles are disabled while a preset is active.
        val normalVideoSettings = videoSettingsAvailable && videoPreset == VideoPreset.NORMAL
        binding.resolutionToggle?.isEnabled = normalVideoSettings
        setButtonActiveStyle(binding.resolutionToggle, normalVideoSettings)

        binding.framerateToggle?.isEnabled = normalVideoSettings
        setButtonActiveStyle(binding.framerateToggle, normalVideoSettings)

        binding.presetToggle?.isEnabled = videoSettingsAvailable
        setButtonActiveStyle(binding.presetToggle, videoSettingsAvailable)
        val presetText = when (videoPreset) {
            VideoPreset.NORMAL -> getString(R.string.preset_normal)
            VideoPreset.SUPER8 -> getString(R.string.preset_super8)
            VideoPreset.VHS -> getString(R.string.preset_vhs)
        }
        binding.presetToggle?.text = "Film Mode $presetText"

        // The filter only takes effect on the RAW→Bitmap→JPEG conversion
        // path. In pure RAW (DNG) mode the saved file is just the sensor
        // mosaic + metadata, which can't carry these Lightroom-style
        // adjustments — so the filter button is greyed out there to make
        // its non-effect visually obvious.
        //
        // Also disabled during the save flow: the selected filter is
        // captured at shutter press and used through the whole pipeline,
        // letting the user change it mid-flight would be confusing (was
        // the saved image processed with the old or the new filter?).
        //
        // Also disabled during video recording.
        val filterAvailable = !isProcessing && !isRecordingVideo && !isShowingDone && !isVideoMode && (outputFormat == OutputFormat.JPEG || outputFormat == OutputFormat.WEBP)
        setButtonActiveStyle(binding.filterToggle, filterAvailable)
        binding.filterToggle?.isEnabled = filterAvailable

        // Update resolution toggle text
        val resText = when (videoResolution) {
            VideoResolution.MAX -> "${getMaxResolutionHeight()}p"
            VideoResolution.QHD_1440P -> "1440p"
            VideoResolution.FHD_1080P -> "1080p"
            VideoResolution.SD_480P -> "480p"
        }
        binding.resolutionToggle?.text = "Resolution $resText"
        
        // Update framerate toggle text
        binding.framerateToggle?.text = "Frame Rate ${videoFrameRate} FPS"

        updateAspectRatioUI()
        updateFlashUI()
        updateMovieToggleUI()
        updateModeToggleUI()
        updateFilterUI()
    }

    private fun updateFilterUI() {
        val cleanName = filmSimulation.displayName.replace("Film ", "")
        fragmentCameraBinding.filterToggle?.text = "Film Mode $cleanName"
    }

    private fun updateMovieToggleUI() {
        val binding = _fragmentCameraBinding ?: return
        val movieToggleEnabled = !isRecordingVideo && !isProcessing && !isShowingDone
        
        binding.cameraToggle?.isEnabled = movieToggleEnabled
        binding.videoToggle?.isEnabled = movieToggleEnabled
        
        val activeColor = getOnPrimaryColor()
        val inactiveColor = getOnSecondaryContainerColor()
        
        if (!isVideoMode) {
            // Camera is selected
            binding.cameraToggle?.backgroundTintList = ColorStateList.valueOf(getPrimaryColor())
            binding.cameraToggle?.iconTint = ColorStateList.valueOf(activeColor)
            
            // Video is unselected
            binding.videoToggle?.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            binding.videoToggle?.iconTint = ColorStateList.valueOf(inactiveColor)
        } else {
            // Video is selected
            binding.videoToggle?.backgroundTintList = ColorStateList.valueOf(getPrimaryColor())
            binding.videoToggle?.iconTint = ColorStateList.valueOf(activeColor)
            
            // Camera is unselected
            binding.cameraToggle?.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            binding.cameraToggle?.iconTint = ColorStateList.valueOf(inactiveColor)
        }
    }

    private fun toggleCameraVideoMode(toVideoMode: Boolean) {
        if (isRecordingVideo) return
        isVideoMode = toVideoMode
        if (isVideoMode) {
            savedPhotoFilmSimulation = filmSimulation
            filmSimulation = FilmSimulation.NORMAL
            aspectRatio = videoAspectRatio
            when (videoPreset) {
                VideoPreset.SUPER8 -> coerceSuper8VideoSettings()
                VideoPreset.VHS -> coerceVhsVideoSettings()
                VideoPreset.NORMAL -> {}
            }
        } else {
            filmSimulation = savedPhotoFilmSimulation
            aspectRatio = photoAspectRatio
        }
        updateSettingsUI()
        updateCaptureButtonForState()
        saveSettings()
        if (!isShowingDone) {
            initializeCamera()
        }
    }

    private fun getMaxResolutionHeight(): Int {
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return 1080
        val choices = streamMap.getOutputSizes(android.media.MediaRecorder::class.java) ?: return 1080
        val targetRatio = when (aspectRatio) {
            AspectRatio.RATIO_1_1 -> 1.0f
            AspectRatio.RATIO_4_3 -> 4.0f / 3.0f
            AspectRatio.RATIO_16_9 -> 16.0f / 9.0f
        }
        val tolerance = 0.05f
        val matchingChoices = choices.filter {
            val ratio = it.width.toFloat() / it.height.toFloat()
            kotlin.math.abs(ratio - targetRatio) < tolerance || kotlin.math.abs((1f / ratio) - targetRatio) < tolerance
        }
        val sortedChoices = if (matchingChoices.isNotEmpty()) matchingChoices else choices.toList()
        val maxSize = sortedChoices.maxByOrNull { it.width * it.height } ?: return 1080
        return maxSize.height
    }

    private fun getSupportedVideoResolutions(): List<VideoResolution> {
        val maxHeight = getMaxResolutionHeight()
        val supported = mutableListOf<VideoResolution>()
        
        if (maxHeight > 1440) {
            supported.add(VideoResolution.MAX)
            supported.add(VideoResolution.QHD_1440P)
            supported.add(VideoResolution.FHD_1080P)
            supported.add(VideoResolution.SD_480P)
        } else if (maxHeight == 1440) {
            supported.add(VideoResolution.QHD_1440P)
            supported.add(VideoResolution.FHD_1080P)
            supported.add(VideoResolution.SD_480P)
        } else if (maxHeight > 1080) {
            supported.add(VideoResolution.MAX)
            supported.add(VideoResolution.FHD_1080P)
            supported.add(VideoResolution.SD_480P)
        } else if (maxHeight == 1080) {
            supported.add(VideoResolution.FHD_1080P)
            supported.add(VideoResolution.SD_480P)
        } else if (maxHeight > 480) {
            supported.add(VideoResolution.MAX)
            supported.add(VideoResolution.SD_480P)
        } else { // maxHeight <= 480
            supported.add(VideoResolution.SD_480P)
        }
        
        return supported.distinct()
    }

    private fun getSupportedVideoFramerates(): List<Int> {
        return listOf(18, 24, 30, 60)
    }

    /** True when the SUPER8 GL pipeline should be active. */
    private fun isSuper8(): Boolean = isVideoMode && videoPreset == VideoPreset.SUPER8

    /** True when the VHS GL pipeline should be active. */
    private fun isVhs(): Boolean = isVideoMode && videoPreset == VideoPreset.VHS

    /** True when any GL look (camera → renderer → encoder) is active. */
    private fun usesGlPipeline(): Boolean = isSuper8() || isVhs()

    /**
     * Super-8 is defined by 18 fps and is inherently low-resolution. We also
     * cap it to 1080p because the hardware H.264 encoder can't configure the
     * sensor's MAX video size (e.g. 4032×3024) — at MAX the encoder fails to
     * start and no valid file is produced. 1080p is encoder-safe everywhere and
     * still far beyond real Super-8 detail.
     */
    private fun coerceSuper8VideoSettings() {
        videoFrameRate = 18
        // Real Super-8 is a ~4:3 frame — lock the effective aspect to 4:3 (the
        // saved videoAspectRatio preference for NORMAL is left untouched).
        aspectRatio = AspectRatio.RATIO_4_3
        // Super-8 was a low-resolution format — record at 480p (also keeps the
        // grain chunky and the encoder happy).
        videoResolution = VideoResolution.SD_480P
    }

    /**
     * VHS is defined by PAL: 25 fps, a 4:3 frame, and standard-definition
     * resolution (the tape only resolves ~240 luma lines anyway — the shader
     * band-limits below 480p). Same encoder-safe envelope as Super-8.
     */
    private fun coerceVhsVideoSettings() {
        videoFrameRate = 25
        aspectRatio = AspectRatio.RATIO_4_3
        videoResolution = VideoResolution.SD_480P
    }

    /**
     * Camera2 exposure shaping for the Super-8 look. We keep auto-exposure on
     * for robustness across devices/lighting, but bias it slightly dense
     * (negative EV) for a filmic feel and steer white balance toward daylight
     * so the shader's warm grade reads as film, not as a colour cast. The
     * 18 fps target range (applied in [applyCaptureRequestSettings]) also caps
     * the shutter, adding natural motion blur like a real Super-8 gate.
     */
    private fun applySuper8Exposure(builder: CaptureRequest.Builder) {
        try {
            val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            if (range != null && step != null && step.toFloat() > 0f) {
                val desiredEv = -0.7f
                val steps = (desiredEv / step.toFloat()).toInt()
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    steps.coerceIn(range.lower, range.upper),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply Super8 exposure settings", e)
        }
    }

    private fun chooseFpsRange(): android.util.Range<Int>? {
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        val target = videoFrameRate
        
        var bestRange = fpsRanges.filter { it.upper == target }
            .maxByOrNull { it.lower }
            
        if (bestRange == null) {
            bestRange = fpsRanges.minByOrNull { kotlin.math.abs(it.upper - target) }
        }
        return bestRange
    }

    private fun applyCaptureRequestSettings(builder: CaptureRequest.Builder) {
        if (isVideoMode) {
            if (flashMode == CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            val fpsRange = chooseFpsRange()
            if (fpsRange != null) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            }
            if (isSuper8()) {
                applySuper8Exposure(builder)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, flashMode)
        }
    }

    private fun chooseVideoSize(choices: Array<android.util.Size>): android.util.Size {
        val targetRatio = when (aspectRatio) {
            AspectRatio.RATIO_1_1 -> 1.0f
            AspectRatio.RATIO_4_3 -> 4.0f / 3.0f
            AspectRatio.RATIO_16_9 -> 16.0f / 9.0f
        }
        val tolerance = 0.05f
        val matchingChoices = choices.filter {
            val ratio = it.width.toFloat() / it.height.toFloat()
            kotlin.math.abs(ratio - targetRatio) < tolerance || kotlin.math.abs((1f / ratio) - targetRatio) < tolerance
        }
        val sortedChoices = if (matchingChoices.isNotEmpty()) matchingChoices else choices.toList()
        
        if (videoResolution == VideoResolution.MAX) {
            return sortedChoices.maxByOrNull { it.width * it.height } ?: choices.first()
        }
        
        val targetHeight = when (videoResolution) {
            VideoResolution.QHD_1440P -> 1440
            VideoResolution.FHD_1080P -> 1080
            VideoResolution.SD_480P -> 480
            else -> 1080
        }
        
        val matchingResolutionChoices = sortedChoices.filter {
            it.width == targetHeight || it.height == targetHeight
        }
        
        if (matchingResolutionChoices.isNotEmpty()) {
            return matchingResolutionChoices.maxByOrNull { it.width * it.height }!!
        }
        
        // Fallback: find closest size by height
        val closest = sortedChoices.minByOrNull { kotlin.math.abs(it.height - targetHeight) }
        if (closest != null) {
            return closest
        }
        
        return sortedChoices.maxByOrNull { it.width * it.height } ?: choices.first()
    }

    private fun setupMediaRecorder(videoSize: android.util.Size, rotation: Int, isTemp: Boolean = false) {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (exc: Throwable) {
            Log.w(TAG, "Failed to reset/release old mediaRecorder: ${exc.message}")
        }
        mediaRecorder = null

        if (isTemp) {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(requireContext())
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }
            val tempFile = File(requireContext().cacheDir, "temp_video.mp4")
            mediaRecorder?.setOutputFile(tempFile.absolutePath)
        } else {
            val filename = "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(requireContext())
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }

            val resolver = requireContext().contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("Failed to create MediaStore entry")
                videoUri = uri
                val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Failed to open file descriptor")
                videoFileDescriptor = pfd
                mediaRecorder?.setOutputFile(pfd.fileDescriptor)
                
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                currentVideoFile = File(File(dcim, "Camera"), filename)
            } else {
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val dir = File(dcim, "Camera").apply { if (!exists()) mkdirs() }
                val file = File(dir, filename)
                currentVideoFile = file
                mediaRecorder?.setOutputFile(file.absolutePath)
            }
        }

        // Try preparing with the requested target size. If the device H.264 encoder
        // rejects the size/framerate combination, catch it and try robust fallbacks.
        try {
            configureAndPrepareMediaRecorder(videoSize, videoFrameRate, videoResolution, rotation)
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder failed to prepare with size ${videoSize.width}x${videoSize.height} at $videoFrameRate FPS: ${e.message}. Trying 1080p fallback...", e)
            
            try {
                val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val videoSizes = streamMap?.getOutputSizes(android.media.MediaRecorder::class.java) ?: emptyArray()
                
                val targetRatio = when (aspectRatio) {
                    AspectRatio.RATIO_1_1 -> 1.0f
                    AspectRatio.RATIO_4_3 -> 4.0f / 3.0f
                    AspectRatio.RATIO_16_9 -> 16.0f / 9.0f
                }
                val tolerance = 0.05f
                val matchingChoices = videoSizes.filter {
                    val ratio = it.width.toFloat() / it.height.toFloat()
                    kotlin.math.abs(ratio - targetRatio) < tolerance || kotlin.math.abs((1f / ratio) - targetRatio) < tolerance
                }
                val sortedChoices = if (matchingChoices.isNotEmpty()) matchingChoices else videoSizes.toList()
                val fallbackSize = sortedChoices.firstOrNull { it.height == 1080 || it.width == 1080 }
                    ?: sortedChoices.maxByOrNull { it.width * it.height }
                    ?: android.util.Size(1920, 1080)

                configureAndPrepareMediaRecorder(fallbackSize, videoFrameRate, VideoResolution.FHD_1080P, rotation)
            } catch (e2: Exception) {
                Log.w(TAG, "MediaRecorder failed fallback to 1080p: ${e2.message}. Trying safe defaults...", e2)
                
                try {
                    val safeSize = android.util.Size(1920, 1080)
                    configureAndPrepareMediaRecorder(safeSize, 30, VideoResolution.FHD_1080P, rotation)
                } catch (e3: Exception) {
                    Log.e(TAG, "MediaRecorder absolute fallback failed", e3)
                    throw e3
                }
            }
        }
    }

    private fun configureAndPrepareMediaRecorder(videoSize: android.util.Size, fps: Int, res: VideoResolution, rotation: Int) {
        // SUPER8/VHS bake orientation into the pixels via the GL stage: the
        // camera's SurfaceTexture transform already delivers a display-upright
        // image, so applying the recorder's rotation hint on top would
        // double-rotate (and squash the upright portrait frame into a landscape
        // buffer). Instead we size the encoder buffer to the final display
        // orientation (portrait when the device is upright) and write NO
        // rotation hint. The direct (NORMAL) path is unchanged: sensor-oriented
        // frames + the rotation hint.
        val portrait = usesGlPipeline() && (rotation == 90 || rotation == 270)
        val encW = if (portrait) videoSize.height else videoSize.width
        val encH = if (portrait) videoSize.width else videoSize.height
        val hint = if (usesGlPipeline()) 0 else rotation
        mediaRecorder?.apply {
            setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            setInputSurface(persistentSurface!!)
            setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            setVideoSize(encW, encH)
            setVideoFrameRate(fps)
            val bitrate = when (res) {
                VideoResolution.MAX -> 20000000
                VideoResolution.QHD_1440P -> 16000000
                VideoResolution.FHD_1080P -> 10000000
                VideoResolution.SD_480P -> 4000000
            }
            setVideoEncodingBitRate(bitrate)
            if (isVhs()) {
                // VHS linear audio: mono, ~100 Hz–8 kHz bandwidth and plenty of
                // hiss. AAC-LC at 16 kHz mono with a low bitrate reproduces the
                // muffled camcorder sound and is universally encoder-safe.
                setAudioEncodingBitRate(32000)
                setAudioChannels(1)
                setAudioSamplingRate(16000)
            } else {
                setAudioEncodingBitRate(96000)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
            }
            setOrientationHint(hint)
            prepare()
        }
    }

    private fun startRecordingVideo() {
        if (!::session.isInitialized) return
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                fragmentCameraBinding.captureButton.isEnabled = false
                
                val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw RuntimeException("Stream configuration map not available")
                val videoSizes = streamMap.getOutputSizes(android.media.MediaRecorder::class.java)
                val videoSize = chooseVideoSize(videoSizes)
                
                val deviceCw = relativeOrientation.value ?: when (
                    fragmentCameraBinding.viewFinder.display?.rotation ?: Surface.ROTATION_0
                ) {
                    Surface.ROTATION_0 -> 0
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val videoRotation = computeJpegOrientation(characteristics, deviceCw)
                
                setupMediaRecorder(videoSize, videoRotation)

                if (usesGlPipeline()) {
                    val renderInput = analogRenderer?.inputSurfaceOrNull()
                        ?: throw RuntimeException("Analog look renderer unavailable")
                    // Feed the renderer's SurfaceTexture FIRST so camera frames
                    // are already flowing when the encoder starts — otherwise
                    // the clip begins with a stretch of audio-only before the
                    // first video frame (a visibly choppy start). The renderer
                    // safely consumes (and drops) frames while not recording.
                    // The encoder itself still starts BEFORE the renderer
                    // attaches its EGL surface, preserving the stable
                    // recorder-then-EGL ordering.
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                        addTarget(renderInput)
                        applyCaptureRequestSettings(this)
                    }
                    session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

                    mediaRecorder?.start()
                    // The GL stage already delivers a display-upright (natural
                    // portrait) frame, so we only need to add the device-rotation
                    // delta to make landscape (and upside-down) recordings upright
                    // too. encoderBuffer dims + orientationHint=0 are set in
                    // configureAndPrepareMediaRecorder.
                    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    val encoderRotation = ((videoRotation - sensorOrientation) + 360) % 360
                    persistentSurface?.let { analogRenderer?.startEncoder(it, encoderRotation) }
                } else {
                    // Update the repeating request to target both the viewfinder and the persistent surface.
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                        addTarget(persistentSurface!!)
                        applyCaptureRequestSettings(this)
                    }
                    session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

                    mediaRecorder?.start()
                }
                isRecordingVideo = true


                
                // Add haptic feedback for record start
                fragmentCameraBinding.captureButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                
                // Automatically close settings panel if open
                if (isSettingsMode) {
                    toggleSettingsMode()
                }
                

                
                updateCaptureButtonForState()
                fragmentCameraBinding.captureButton.isEnabled = true
                
                updateSettingsUI()
                fragmentCameraBinding.galleryButton?.isEnabled = false
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to start video recording", exc)
                Toast.makeText(requireContext(), "Failed to start recording: ${exc.message}", Toast.LENGTH_SHORT).show()
                initializeCamera()
            }
        }
    }

    private fun stopRecordingVideo() {
        if (!isRecordingVideo) return
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                _fragmentCameraBinding?.captureButton?.isEnabled = false
                setProcessing(true)
                
                // Add haptic feedback for record stop
                _fragmentCameraBinding?.captureButton?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)



                try {
                    // In SUPER8/VHS detach the encoder from the renderer first
                    // (blocks until the GL thread has stopped drawing into it) so
                    // no swapBuffers can race with MediaRecorder.stop().
                    if (usesGlPipeline()) {
                        analogRenderer?.stopEncoder()
                    }
                    mediaRecorder?.stop()

                    // Revert the repeating request to preview-only — stops feeding
                    // the record target (persistent surface or renderer input).
                    if (::session.isInitialized && _fragmentCameraBinding != null) {
                        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(_fragmentCameraBinding!!.viewFinder.holder.surface)
                            applyCaptureRequestSettings(this)
                        }
                        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
                    }

                    _fragmentCameraBinding?.viewFinder?.post(shutterFlashTask)
                } catch (exc: RuntimeException) {
                    Log.e(TAG, "RuntimeException stopping MediaRecorder: dynamic check, might be too short", exc)
                    currentVideoFile?.delete()
                }
                mediaRecorder?.reset()
                mediaRecorder?.release()
                mediaRecorder = null
                
                videoFileDescriptor?.close()
                videoFileDescriptor = null
                
                isRecordingVideo = false
                updateCaptureButtonForState()
                
                var thumbnail: Bitmap? = null
                val file = currentVideoFile
                if (file != null && file.exists()) {
                    withContext(Dispatchers.IO) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            val context = context ?: return@suspendCancellableCoroutine
                            android.media.MediaScannerConnection.scanFile(
                                context,
                                arrayOf(file.absolutePath),
                                arrayOf("video/mp4")
                            ) { _, _ ->
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                    }
                    thumbnail = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            android.media.ThumbnailUtils.createVideoThumbnail(
                                file,
                                android.util.Size(960, 1280),
                                null
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            android.media.ThumbnailUtils.createVideoThumbnail(
                                file.absolutePath,
                                MediaStore.Video.Thumbnails.MINI_KIND
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create video thumbnail", e)
                        null
                    }
                }
                
                // Add a small delay so the user clearly sees the "Developing..." state feedback
                kotlinx.coroutines.delay(1000)

                if (thumbnail != null) {
                    showProgress(CaptureProgress.Done(thumbnail))
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to stop video recording", exc)
            } finally {
                setProcessing(false)

                updateCaptureButtonForState()
                if (_fragmentCameraBinding != null) {
                    reEnableUI()
                }
                
                updateSettingsUI()
                _fragmentCameraBinding?.galleryButton?.isEnabled = true
            }
        }
    }



    /** Flips [isProcessing] and refreshes the settings UI (which gates the
     *  filter toggle's isEnabled state on this flag). */
    private fun setProcessing(value: Boolean) {
        isProcessing = value
        updateSettingsUI()
    }

    private fun getPrimaryColor(): Int {
        val typedValue = android.util.TypedValue()
        val theme = requireContext().theme
        return if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)) {
            typedValue.data
        } else {
            ContextCompat.getColor(requireContext(), R.color.primary)
        }
    }

    private fun getErrorColor(): Int {
        val typedValue = android.util.TypedValue()
        val theme = requireContext().theme
        return if (theme.resolveAttribute(com.google.android.material.R.attr.colorError, typedValue, true)) {
            typedValue.data
        } else {
            Color.parseColor("#E53935")
        }
    }

    private fun getOnErrorColor(): Int {
        val typedValue = android.util.TypedValue()
        val theme = requireContext().theme
        return if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnError, typedValue, true)) {
            typedValue.data
        } else {
            Color.WHITE
        }
    }

    private fun getSecondaryContainerColor(): Int {
        val typedValue = android.util.TypedValue()
        val theme = requireContext().theme
        return if (theme.resolveAttribute(com.google.android.material.R.attr.colorSecondaryContainer, typedValue, true)) {
            typedValue.data
        } else {
            Color.parseColor("#33FFFFFF")
        }
    }

    private fun getOnPrimaryColor(): Int {
        val typedValue = android.util.TypedValue()
        val theme = requireContext().theme
        return if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimary, typedValue, true)) {
            typedValue.data
        } else {
            Color.WHITE
        }
    }

    private fun getOnSecondaryContainerColor(): Int {
        val typedValue = android.util.TypedValue()
        val theme = requireContext().theme
        return if (theme.resolveAttribute(com.google.android.material.R.attr.colorOnSecondaryContainer, typedValue, true)) {
            typedValue.data
        } else {
            Color.GRAY
        }
    }

    private fun setButtonActiveStyle(button: com.google.android.material.button.MaterialButton?, active: Boolean) {
        if (button == null) return
        if (active) {
            val textIconColor = getOnPrimaryColor()
            button.backgroundTintList = ColorStateList.valueOf(getPrimaryColor())
            button.setTextColor(textIconColor)
            button.iconTint = ColorStateList.valueOf(textIconColor)
        } else {
            val textIconColor = getOnSecondaryContainerColor()
            button.backgroundTintList = ColorStateList.valueOf(getSecondaryContainerColor())
            button.setTextColor(textIconColor)
            button.iconTint = ColorStateList.valueOf(textIconColor)
        }
    }

    private fun updateViewfinderRatio() {
        try {
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val needsSwap = (sensorOrientation == 90 || sensorOrientation == 270)
            // The container is sized purely from the SELECTED aspect ratio,
            // never from per-lens stream sizes: different lenses report
            // slightly different native sizes, and deriving the ratio from
            // them moved/resized the container on every lens switch and
            // between photo and video mode. The AutoFitSurfaceView inside
            // center-crops any small mismatch between the camera buffer and
            // this container, so the image is never stretched — at most
            // minimally cropped.
            val (targetW, targetH) = when (aspectRatio) {
                AspectRatio.RATIO_1_1 -> Pair(1, 1)
                AspectRatio.RATIO_16_9 -> if (needsSwap) Pair(9, 16) else Pair(16, 9)
                AspectRatio.RATIO_4_3 -> if (needsSwap) Pair(3, 4) else Pair(4, 3)
            }

            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(fragmentCameraBinding.root)
            val systemBars = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val bottomInset = systemBars?.bottom ?: 0
            val wMax = maxOf(1, resources.displayMetrics.widthPixels - 32.dpToPx())
            val hMax = maxOf(1, resources.displayMetrics.heightPixels - 311.dpToPx() - bottomInset)
            val availableRatio = wMax.toFloat() / hMax.toFloat()
            val targetRatio = targetW.toFloat() / targetH.toFloat()

            val ratio = if (targetRatio > availableRatio) {
                "H,$targetW:$targetH"
            } else {
                "W,$targetW:$targetH"
            }

            Log.d(TAG, "updateViewfinderRatio | container=$ratio (target=$targetW:$targetH, availableRatio=$availableRatio, targetRatio=$targetRatio)")

            val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
            constraintSet.clone(fragmentCameraBinding.root as androidx.constraintlayout.widget.ConstraintLayout)
            constraintSet.setDimensionRatio(R.id.view_finder_container, ratio)
            constraintSet.constrainedWidth(R.id.view_finder_container, true)
            constraintSet.constrainedHeight(R.id.view_finder_container, true)

            if (aspectRatio == AspectRatio.RATIO_16_9) {
                val ratio43Ratio = if (needsSwap) 3f / 4f else 4f / 3f
                val height43 = if (ratio43Ratio > availableRatio) {
                    (wMax / ratio43Ratio).toInt()
                } else {
                    hMax
                }
                constraintSet.constrainMaxHeight(R.id.view_finder_container, height43)
            } else {
                constraintSet.constrainMaxHeight(R.id.view_finder_container, Int.MAX_VALUE)
            }

            constraintSet.applyTo(fragmentCameraBinding.root as androidx.constraintlayout.widget.ConstraintLayout)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update viewfinder ratio constraints", e)
        }
    }

    /**
     * Suspends until the viewfinder surface actually has the fixed buffer size
     * requested via [AutoFitSurfaceView.setBufferSize]. SurfaceHolder.setFixedSize
     * is applied on the next layout traversal, so callers that need the final
     * surface geometry (capture-session creation) must not read the surface
     * before this returns. Returns immediately when the size already matches;
     * the timeout is only a safety net so camera init can never hang on layout.
     */
    private suspend fun awaitViewfinderBufferSize(width: Int, height: Int) {
        val holder = fragmentCameraBinding.viewFinder.holder
        fun matches(): Boolean {
            val frame = holder.surfaceFrame
            return frame.width() == width && frame.height() == height
        }
        if (matches()) return
        var callback: SurfaceHolder.Callback? = null
        try {
            val reached = kotlinx.coroutines.withTimeoutOrNull(500L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val cb = object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            w: Int,
                            h: Int
                        ) {
                            if (w == width && h == height && cont.isActive) cont.resume(Unit)
                        }
                    }
                    callback = cb
                    holder.addCallback(cb)
                    // The traversal may have applied the size between the check
                    // above and registering the callback.
                    if (matches() && cont.isActive) cont.resume(Unit)
                }
            }
            if (reached == null) {
                Log.w(TAG, "Viewfinder surface did not reach ${width}x$height in time; continuing")
            }
        } finally {
            callback?.let { holder.removeCallback(it) }
        }
    }

    // -------- Capture progress UI --------

    /** Bitmap currently shown in the Done overlay. Recycled when we leave
     *  the Done state (Take-new pressed, error, fragment torn down). */
    private var doneThumbnail: Bitmap? = null

    private var frozenThumbnail: Bitmap? = null

    /** True while the Done overlay (saved image) is visible — in that state
     *  the capture button doubles as "Take new". */
    private var isShowingDone: Boolean = false

    /** True from the moment of the capture press until the save (or error
     *  fallback) has finished — drives the "Developing…" button label. */
    private var isProcessing: Boolean = false

    /** Lifecycle stage of a capture, drives the overlay state machine. */
    private sealed class CaptureProgress {
        /** HAL is exposing + the file is being saved. UI: just a dim. */
        data class Saving(val frozenBitmap: Bitmap?) : CaptureProgress()
        /** Save complete — show the saved image fitCenter at preview size. */
        data class Done(val thumbnail: Bitmap?) : CaptureProgress()
        /** Save failed — overlay flashes off after the error indicator. */
        data class Failed(val message: String) : CaptureProgress()
    }

    /**
     * Renders the [CaptureProgress] state onto the overlay. Must be called on
     * the main thread. Safe to call after the view is destroyed (no-op).
     *
     * The overlay only exists in the portrait layout (`layout/`), not in
     * `layout-land/`, so ViewBinding types it as nullable — if missing we
     * bail out and capture still works without the overlay.
     */
    private fun showProgress(state: CaptureProgress) {
        val binding = _fragmentCameraBinding ?: return
        val overlay = binding.captureProgressOverlay ?: return
        val thumbnail = binding.captureProgressThumbnail ?: return

        // Hide lens selector card during progress/review
        binding.lensSelectorCard?.visibility = View.GONE

        when (state) {
            is CaptureProgress.Saving -> {
                val oldFrozen = frozenThumbnail
                frozenThumbnail = state.frozenBitmap
                if (state.frozenBitmap != null) {
                    thumbnail.setImageBitmap(state.frozenBitmap)
                    thumbnail.visibility = View.VISIBLE
                    thumbnail.alpha = 1.0f
                    // Apply a dark color filter (80% black) on top of the opaque frozen preview image
                    thumbnail.setColorFilter(Color.argb(204, 0, 0, 0))
                } else {
                    thumbnail.visibility = View.GONE
                    thumbnail.setImageDrawable(null)
                    thumbnail.clearColorFilter()
                }
                oldFrozen?.takeIf { it !== state.frozenBitmap && !it.isRecycled }?.recycle()

                overlay.visibility = View.VISIBLE
                overlay.alpha = 0f
                overlay.animate().alpha(1f).setDuration(180L).start()
                isShowingDone = false
                updateCaptureButtonForState()
            }
            is CaptureProgress.Done -> {
                thumbnail.alpha = 1.0f
                thumbnail.clearColorFilter() // Clear the color filter for the final saved image
                overlay.visibility = View.VISIBLE
                overlay.alpha = 1f
                val oldBitmap = doneThumbnail
                doneThumbnail = state.thumbnail
                if (state.thumbnail != null) {
                    // The container's ratio is deliberately NOT changed here:
                    // it stays exactly where the live preview was, and the
                    // result is shown fitCenter inside it. (Mutating the
                    // container to the thumbnail ratio made the whole layout
                    // jump on every capture.)
                    thumbnail.setImageBitmap(state.thumbnail)
                    thumbnail.visibility = View.VISIBLE
                } else {
                    // Decoder failed (typical for DNG-only on some devices).
                    // Keep the dim — there's just no preview image to show.
                    thumbnail.setImageDrawable(null)
                    thumbnail.visibility = View.GONE
                }
                oldBitmap?.takeIf { it !== state.thumbnail && !it.isRecycled }?.recycle()

                frozenThumbnail?.takeIf { !it.isRecycled }?.recycle()
                frozenThumbnail = null

                // Freeze the camera while the user reviews the result. Nothing
                // live is visible behind the opaque overlay anyway, and not
                // running the ISP/preview pipeline saves a meaningful amount
                // of power. resumePreview() restarts it on "Take new".
                try {
                    if (::session.isInitialized) session.stopRepeating()
                } catch (exc: Exception) {
                    Log.w(TAG, "stopRepeating for review failed: ${exc.message}")
                }

                if (isVideoMode) {
                    setupVideoReviewPlayback(binding)
                } else {
                    binding.captureProgressPlayButton?.visibility = View.GONE
                }

                isShowingDone = true
                updateCaptureButtonForState()
                updateSettingsUI()
            }
            is CaptureProgress.Failed -> {
                // Show dim briefly then bail; caller schedules hideProgress.
                thumbnail.visibility = View.GONE
                overlay.visibility = View.VISIBLE
                overlay.alpha = 1f
                isShowingDone = false
                updateCaptureButtonForState()
            }
        }
    }

    private fun hideProgress() {
        val binding = _fragmentCameraBinding ?: return
        val overlay = binding.captureProgressOverlay ?: return
        val thumbnail = binding.captureProgressThumbnail ?: return
 
        // Restore lens selector card visibility if camera entries exist
        if (allCameraIds.isNotEmpty()) {
            binding.lensSelectorCard?.visibility = View.VISIBLE
        }
 
        overlay.animate().cancel()
        overlay.visibility = View.GONE
        overlay.alpha = 1f
        thumbnail.visibility = View.GONE
        thumbnail.setImageDrawable(null)
        thumbnail.alpha = 1.0f
        thumbnail.clearColorFilter()

        binding.captureProgressVideo?.apply {
            setOnPreparedListener(null)
            setOnCompletionListener(null)
            // stopPlayback also releases a merely-paused MediaPlayer.
            try { stopPlayback() } catch (exc: Exception) {
                Log.w(TAG, "stopPlayback failed: ${exc.message}")
            }
            visibility = View.GONE
            val lp = layoutParams as? android.widget.FrameLayout.LayoutParams
            if (lp != null) {
                lp.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                layoutParams = lp
            }
        }
        binding.captureProgressPlayButton?.visibility = View.GONE

        doneThumbnail?.takeIf { !it.isRecycled }?.recycle()
        doneThumbnail = null
        frozenThumbnail?.takeIf { !it.isRecycled }?.recycle()
        frozenThumbnail = null
        isShowingDone = false
        updateCaptureButtonForState()
        updateSettingsUI()

        // Restore the live preview. The session is usually still fully
        // configured (it was only stopped for the review overlay), so a
        // repeating request is all that's needed — full re-initialization
        // only happens as a fallback.
        resumePreview()
    }

    /**
     * Restarts the repeating preview request on the existing session. Falls
     * back to a full [initializeCamera] if the session/device is gone (e.g.
     * the app was stopped while the review overlay was up).
     */
    private fun resumePreview() {
        try {
            if (::session.isInitialized && ::camera.isInitialized && !isCameraClosed) {
                val template = if (isVideoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                val captureRequest = camera.createCaptureRequest(template).apply {
                    addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                    applyCaptureRequestSettings(this)
                }
                session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
            } else {
                initializeCamera()
            }
        } catch (exc: Exception) {
            Log.w(TAG, "resumePreview failed, re-initializing camera", exc)
            initializeCamera()
        }
    }

    /**
     * Wires the review overlay's play button + VideoView for the just-recorded
     * clip. Two quirks this handles explicitly:
     *
     *  - The VideoView is a SurfaceView; when it becomes visible its surface
     *    is transparent until the first decoded frame arrives, exposing the
     *    viewfinder surface behind the overlay (a brief "live preview flash"
     *    on Play). The thumbnail therefore stays on top until the player
     *    reports MEDIA_INFO_VIDEO_RENDERING_START.
     *  - Pause/resume: tapping the playing video pauses it (play button
     *    reappears); the play button resumes a paused clip instead of
     *    restarting it.
     */
    private fun setupVideoReviewPlayback(binding: FragmentCameraBinding) {
        val video = binding.captureProgressVideo ?: return
        val playButton = binding.captureProgressPlayButton ?: return

        playButton.visibility = View.VISIBLE
        // Composite the playback surface above the viewfinder surface.
        video.setZOrderMediaOverlay(true)

        video.setOnClickListener {
            if (video.isPlaying) {
                video.pause()
                playButton.visibility = View.VISIBLE
            }
        }

        playButton.setOnClickListener {
            playButton.visibility = View.GONE
            // Paused mid-clip → just resume.
            if (video.visibility == View.VISIBLE && video.currentPosition > 0 && !video.isPlaying) {
                video.start()
                return@setOnClickListener
            }
            // Fresh start. Keep the thumbnail covering the surface until the
            // first video frame is actually rendered.
            video.visibility = View.VISIBLE
            video.setOnPreparedListener { mp ->
                adjustVideoViewSizeForCenterCrop(video, mp)
                mp.setOnInfoListener { _, what, _ ->
                    if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        _fragmentCameraBinding?.captureProgressThumbnail?.visibility = View.GONE
                        true
                    } else {
                        false
                    }
                }
                mp.start()
                // Safety net for players that never emit RENDERING_START.
                video.postDelayed({
                    if (video.isPlaying) {
                        _fragmentCameraBinding?.captureProgressThumbnail?.visibility = View.GONE
                    }
                }, 600L)
            }
            val uri = videoUri
            if (uri != null) {
                video.setVideoURI(uri)
            } else {
                currentVideoFile?.let { file -> video.setVideoPath(file.absolutePath) }
            }
        }

        video.setOnCompletionListener {
            video.visibility = View.GONE
            _fragmentCameraBinding?.captureProgressThumbnail?.visibility = View.VISIBLE
            playButton.visibility = View.VISIBLE
        }
    }

    private fun adjustVideoViewSizeForCenterCrop(videoView: android.widget.VideoView, mediaPlayer: android.media.MediaPlayer) {
        val container = _fragmentCameraBinding?.viewFinderContainer ?: return
        val containerWidth = container.width
        val containerHeight = container.height
        if (containerWidth <= 0 || containerHeight <= 0) return

        var videoWidth = mediaPlayer.videoWidth
        var videoHeight = mediaPlayer.videoHeight
        if (videoWidth <= 0 || videoHeight <= 0) return

        // Retrieve rotation to swap width/height if necessary
        try {
            val retriever = android.media.MediaMetadataRetriever()
            val uri = videoUri
            if (uri != null) {
                retriever.setDataSource(requireContext(), uri)
            } else {
                currentVideoFile?.let { retriever.setDataSource(it.absolutePath) }
            }
            val rotationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotation = rotationStr?.toIntOrNull() ?: 0
            retriever.release()

            if (rotation == 90 || rotation == 270) {
                val temp = videoWidth
                videoWidth = videoHeight
                videoHeight = temp
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve video rotation for preview scaling", e)
        }

        val containerRatio = containerWidth.toFloat() / containerHeight.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()

        val layoutParams = videoView.layoutParams as android.widget.FrameLayout.LayoutParams
        if (videoRatio > containerRatio) {
            // Video is wider than container -> match container height, scale width (overflow/crop on sides)
            layoutParams.height = containerHeight
            layoutParams.width = (containerHeight * videoRatio).toInt()
        } else {
            // Video is taller than container -> match container width, scale height (overflow/crop on top/bottom)
            layoutParams.width = containerWidth
            layoutParams.height = (containerWidth / videoRatio).toInt()
        }
        layoutParams.gravity = android.view.Gravity.CENTER
        videoView.layoutParams = layoutParams
    }

    /** Toggles the capture button label between "Capture" and "Take new"
     *  depending on whether the Done overlay is up. The landscape layout
     *  uses an ImageButton without text — view binding types the field as
     *  the common ancestor View, so we cast and skip the text update if we
     *  happen to be in landscape. */
    private fun updateCaptureButtonForState() {
        val button = _fragmentCameraBinding?.captureButton
                as? com.google.android.material.button.MaterialButton ?: return
        button.text = getString(
            when {
                isShowingDone -> R.string.progress_take_new
                isProcessing -> R.string.progress_developing
                isRecordingVideo -> R.string.video_stop
                isVideoMode -> R.string.video_record
                else -> R.string.capture
            }
        )
        
        if (isProcessing) {
            button.backgroundTintList = ColorStateList.valueOf(getSecondaryContainerColor())
            button.setTextColor(getOnSecondaryContainerColor())
            button.iconTint = ColorStateList.valueOf(getOnSecondaryContainerColor())
        } else if (isRecordingVideo) {
            button.backgroundTintList = ColorStateList.valueOf(getErrorColor())
            button.setTextColor(getOnErrorColor())
            button.iconTint = ColorStateList.valueOf(getOnErrorColor())
        } else {
            button.backgroundTintList = ColorStateList.valueOf(getPrimaryColor())
            button.setTextColor(getOnPrimaryColor())
            button.iconTint = ColorStateList.valueOf(getOnPrimaryColor())
        }
    }

    private fun releaseResources() {
        // Stop feeding the encoder (blocks until the GL thread is done) before
        // tearing down the recorder, in every teardown path.
        analogRenderer?.stopEncoder()
        if (isRecordingVideo) {
            try {
                mediaRecorder?.stop()
            } catch (exc: Throwable) {
                Log.w(TAG, "mediaRecorder stop failed: ${exc.message}")
            }
            isRecordingVideo = false
        }
        try {
            mediaRecorder?.reset()
        } catch (exc: Throwable) {
            Log.w(TAG, "mediaRecorder reset failed: ${exc.message}")
        }
        try {
            mediaRecorder?.release()
        } catch (exc: Throwable) {
            Log.w(TAG, "mediaRecorder release failed: ${exc.message}")
        }
        mediaRecorder = null
        try {
            context?.cacheDir?.let { cacheDir ->
                val tempFile = File(cacheDir, "temp_video.mp4")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        } catch (exc: Throwable) {
            Log.w(TAG, "Failed to delete temp video file: ${exc.message}")
        }
        try {
            videoFileDescriptor?.close()
        } catch (exc: Throwable) {
            Log.w(TAG, "videoFileDescriptor close failed: ${exc.message}")
        }
        videoFileDescriptor = null
        persistentSurface?.release()
        persistentSurface = null

        // Close each resource independently so a failure in one doesn't skip the others.
        if (::session.isInitialized) {
            try { session.stopRepeating() } catch (exc: Throwable) {
                Log.w(TAG, "stopRepeating failed: ${exc.message}")
            }
            try { session.close() } catch (exc: Throwable) {
                Log.w(TAG, "session.close failed: ${exc.message}")
            }
        }
        if (::camera.isInitialized) {
            try { camera.close() } catch (exc: Throwable) {
                Log.w(TAG, "camera.close failed: ${exc.message}")
            }
            isCameraClosed = true
        }
        if (::imageReader.isInitialized) {
            try { imageReader.close() } catch (exc: Throwable) {
                Log.w(TAG, "imageReader.close failed: ${exc.message}")
            }
        }
        // Release the GL renderer LAST — its SurfaceTexture is a camera output,
        // so the session/camera must be torn down first to avoid feeding an
        // abandoned buffer queue (a native crash hazard).
        analogRenderer?.release()
        analogRenderer = null
    }

    private fun closeCameraAndSession() {
        if (::session.isInitialized) {
            try { session.stopRepeating() } catch (exc: Throwable) {}
            try { session.close() } catch (exc: Throwable) {}
        }
        if (::camera.isInitialized) {
            try { camera.close() } catch (exc: Throwable) {}
            isCameraClosed = true
        }
        if (::imageReader.isInitialized) {
            try { imageReader.close() } catch (exc: Throwable) {}
        }
    }

    private fun reEnableUI() {
        Log.d(TAG, "reEnableUI() called, setting isCameraInitializing to false")
        isCameraInitializing = false
        fragmentCameraBinding.captureButton.isEnabled = true
        val count = fragmentCameraBinding.lensSelectorContainer?.childCount ?: 0
        for (i in 0 until count) {
            fragmentCameraBinding.lensSelectorContainer?.getChildAt(i)?.isEnabled = true
        }
    }

    private fun updateLensHighlight() {
        val container = fragmentCameraBinding.lensSelectorContainer ?: return
        for (i in 0 until container.childCount) {
            val button = container.getChildAt(i) as com.google.android.material.button.MaterialButton
            val cameraId = allCameraIds.getOrNull(i)
            if (cameraId == currentCameraId) {
                button.setBackgroundColor(getPrimaryColor()) 
                button.setTextColor(Color.BLACK)
                button.alpha = 1.0f
            } else {
                button.setBackgroundColor("#33FFFFFF".toColorInt()) // 20% white
                button.setTextColor(Color.WHITE)
                button.alpha = 0.8f
            }
            button.strokeWidth = 0
        }
    }

    private fun switchCamera(newId: String) {
        if (currentCameraId == newId) return
        
        Log.d(TAG, "Switching camera to $newId")
        currentCameraId = newId
        
        // Update highlight immediately for responsive feel
        updateLensHighlight()

        // Pre-apply correct viewfinder constraints to avoid stretch layout jumps
        updateViewfinderRatio()
        
        // Cancel any pending camera operations and start fresh only if not showing done preview
        if (!isShowingDone) {
            initializeCamera()
        }
    }

    private fun openRecentPhoto() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%DCIM/Camera%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val query = requireContext().contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        query?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                val contentUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "image/*")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(intent)
            } else {
                // Fallback if no images found in DCIM/Camera
                val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "No gallery app found", Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            // Fallback if query fails
            val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera() {
        cameraJob?.cancel()
        
        _fragmentCameraBinding?.overlay?.animate()?.cancel()
        _fragmentCameraBinding?.overlay?.setBackgroundColor(Color.TRANSPARENT)
        _fragmentCameraBinding?.overlay?.alpha = 0f
        
        // Coerce video settings based on current camera characteristics (updates dynamically on startup & lens switches)
        val supportedResolutions = getSupportedVideoResolutions()
        var settingsChanged = false
        if (videoResolution !in supportedResolutions) {
            videoResolution = supportedResolutions.firstOrNull() ?: VideoResolution.MAX
            settingsChanged = true
        }
        val supportedFramerates = getSupportedVideoFramerates()
        if (videoFrameRate !in supportedFramerates) {
            videoFrameRate = if (30 in supportedFramerates) 30 else supportedFramerates.firstOrNull() ?: 30
            settingsChanged = true
        }
        // Authoritative preset lock: native fps + encoder-safe resolution. Runs
        // on every (re)init so a stale/unsupported persisted value can't reach
        // the encoder and fail it.
        if (usesGlPipeline()) {
            val before = videoResolution to videoFrameRate
            if (isSuper8()) coerceSuper8VideoSettings() else coerceVhsVideoSettings()
            if (videoResolution to videoFrameRate != before) settingsChanged = true
        }
        if (settingsChanged) {
            saveSettings()
            updateSettingsUI()
        }
        
        // Disable UI during initialization (lens buttons too, so the user can't
        // bounce between lenses faster than the camera service can re-open).
        isCameraInitializing = true
        val lensContainer = fragmentCameraBinding.lensSelectorContainer
        for (i in 0 until (lensContainer?.childCount ?: 0)) {
            lensContainer?.getChildAt(i)?.isEnabled = false
        }

        cameraJob = lifecycleScope.launch(Dispatchers.Main) {
            var initialized = false
            try {
                val cameraToOpen = physicalToLogicalMap[currentCameraId] ?: currentCameraId
                val needsReopen = isCameraClosed || !::camera.isInitialized || camera.id != cameraToOpen
                
                if (needsReopen) {
                    // Release existing resources before opening a new camera.
                    // If we are recording video, we must NOT release the mediaRecorder or persistentSurface.
                    if (isRecordingVideo) {
                        closeCameraAndSession()
                    } else {
                        releaseResources()
                    }

                    if (isVideoMode && persistentSurface == null) {
                        persistentSurface = android.media.MediaCodec.createPersistentInputSurface()
                    }

                    // Open right away (no settle delay — that cost 250 ms on
                    // every cold start and lens switch). If the previous
                    // device is still releasing, retry once briefly before
                    // falling back to the logical parent camera.
                    camera = try {
                        openCamera(cameraManager, cameraToOpen, cameraHandler)
                    } catch (exc: Exception) {
                        Log.w(TAG, "Failed to open camera $cameraToOpen, retrying once", exc)
                        kotlinx.coroutines.delay(200)
                        try {
                            openCamera(cameraManager, cameraToOpen, cameraHandler)
                        } catch (exc2: Exception) {
                            Log.e(TAG, "Failed to open camera $cameraToOpen, trying fallback", exc2)
                            val fallbackId = physicalToLogicalMap[currentCameraId]
                            if (fallbackId != null && fallbackId != currentCameraId) {
                                currentCameraId = fallbackId
                                updateLensHighlight()
                                openCamera(cameraManager, currentCameraId, cameraHandler)
                            } else {
                                throw exc2
                            }
                        }
                    }
                } else {
                    // Close the session and imageReader, but keep the camera device open!
                    if (::session.isInitialized) {
                        try { session.stopRepeating() } catch (exc: Throwable) {}
                        try { session.close() } catch (exc: Throwable) {}
                    }
                    if (::imageReader.isInitialized) {
                        try { imageReader.close() } catch (exc: Throwable) {}
                    }
                    
                    if (!isRecordingVideo) {
                        persistentSurface?.release()
                        persistentSurface = null
                        if (isVideoMode) {
                            persistentSurface = android.media.MediaCodec.createPersistentInputSurface()
                        }
                    }
                }

                // Initialize an image reader which will be used to capture still photos.
                // Use the CURRENT sensor's characteristics — for a physical sub-camera
                // those are the physical's, not the logical parent's. With
                // setPhysicalCameraId() (below), the configured size must be valid
                // for the physical sensor, otherwise session configuration silently
                // fails on multi-sensor devices like Pixel 4a 5G where the ultrawide
                // and main sensor have different native resolutions.
                Log.d(TAG, "Initializing image reader")
                val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw RuntimeException("Camera $currentCameraId does not support stream configuration map")

                val previewSize = if (isVideoMode) {
                    val videoSizes = streamMap.getOutputSizes(android.media.MediaRecorder::class.java)
                    val videoSize = chooseVideoSize(videoSizes)
                    
                    val deviceCw = relativeOrientation.value ?: when (
                        fragmentCameraBinding.viewFinder.display?.rotation ?: Surface.ROTATION_0
                    ) {
                        Surface.ROTATION_0 -> 0
                        Surface.ROTATION_90 -> 90
                        Surface.ROTATION_180 -> 180
                        Surface.ROTATION_270 -> 270
                        else -> 0
                    }
                    val videoRotation = computeJpegOrientation(characteristics, deviceCw)
                    if (!isRecordingVideo) {
                        setupMediaRecorder(videoSize, videoRotation, isTemp = true)
                    }

                    val captureRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    val computedPreviewSize = getPreviewOutputSize(
                        fragmentCameraBinding.viewFinder.display,
                        characteristics,
                        SurfaceHolder::class.java,
                        aspectRatio = captureRatio
                    )

                    // SUPER8/VHS insert the GL renderer between the camera and the
                    // encoder (the camera writes to the renderer's SurfaceTexture,
                    // the renderer bakes the look into the recording). The
                    // on-screen preview stays a plain camera preview.
                    //
                    // IMPORTANT: capture into the SurfaceTexture at the FOV-correct
                    // preview size (e.g. 1440x1080), NOT the small encoder size
                    // (e.g. 640x480). Some HALs crop the ultrawide to a narrower
                    // (zoomed) field of view at small output sizes, which made the
                    // recording look zoomed even though the preview was wide. The
                    // GL stage then downscales to the chosen encoder size (480p).
                    //
                    // Recreate the renderer if the capture size or look changed;
                    // if GL init fails on this device, fall back to NORMAL so
                    // video still works.
                    if (usesGlPipeline()) {
                        val capW = computedPreviewSize.width
                        val capH = computedPreviewSize.height
                        val look = if (isSuper8()) AnalogLook.SUPER8 else AnalogLook.VHS
                        val existing = analogRenderer
                        if (existing == null ||
                            existing.look != look ||
                            existing.videoWidth != capW ||
                            existing.videoHeight != capH
                        ) {
                            existing?.release()
                            analogRenderer = AnalogLookRenderer.createOrNull(look, capW, capH)
                        }
                        if (analogRenderer == null) {
                            Log.e(TAG, "$look GL pipeline unavailable; falling back to NORMAL preset")
                            videoPreset = VideoPreset.NORMAL
                            updateSettingsUI()
                            saveSettings()
                        }
                    } else {
                        analogRenderer?.release()
                        analogRenderer = null
                    }

                    computedPreviewSize
                } else {
                    // Photo mode never uses the GL renderer — tear it down.
                    analogRenderer?.release()
                    analogRenderer = null

                    val supportedSizes = streamMap.getOutputSizes(args.pixelFormat)
                    val format = if (supportedSizes.isNullOrEmpty()) {
                        Log.w(TAG, "Requested format ${args.pixelFormat} not supported by camera $currentCameraId, falling back to JPEG")
                        ImageFormat.JPEG
                    } else {
                        args.pixelFormat
                    }

                    val finalSizes = if (format == args.pixelFormat) supportedSizes else streamMap.getOutputSizes(format)
                    val size = finalSizes?.maxByOrNull { it.height * it.width }
                        ?: throw RuntimeException("No supported sizes found for format $format on camera $currentCameraId")
                    
                    imageReader = ImageReader.newInstance(
                        size.width, size.height, format, IMAGE_BUFFER_SIZE
                    )

                    val captureRatio = size.width.toFloat() / size.height.toFloat()
                    getPreviewOutputSize(
                        fragmentCameraBinding.viewFinder.display,
                        characteristics,
                        SurfaceHolder::class.java,
                        aspectRatio = captureRatio
                    )
                }

                // Determine if sensor+device orientation requires swapping
                // width/height for the on-screen aspect ratio.
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val deviceRotation = when (fragmentCameraBinding.viewFinder.display.rotation) {
                    Surface.ROTATION_0 -> 0
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val totalRotation = (sensorOrientation + deviceRotation) % 360
                val needsSwap = (totalRotation == 90 || totalRotation == 270)

                val displayWidth = if (needsSwap) previewSize.height else previewSize.width
                val displayHeight = if (needsSwap) previewSize.width else previewSize.height

                // Layout aspect (display coordinates) and buffer size (sensor coordinates).
                fragmentCameraBinding.viewFinder.setAspectRatio(displayWidth, displayHeight)
                fragmentCameraBinding.viewFinder.setBufferSize(previewSize.width, previewSize.height)

                // Update viewfinder aspect ratio constraints.
                updateViewfinderRatio()

                val facingFrontPreview = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                Log.d(TAG, "PREVIEW DIAG | camera=$currentCameraId facing=${if (facingFrontPreview) "FRONT" else "BACK"} sensor=${sensorOrientation}° deviceDisplay=${deviceRotation}° swap=$needsSwap | buffer=${previewSize.width}x${previewSize.height} viewAspect=$displayWidth:$displayHeight")

                // setFixedSize() above only takes effect on the next layout
                // traversal, but OutputConfiguration reads the surface size the
                // moment it is constructed. If session creation wins that race
                // (common on cold start with a warm camera service), the stream
                // gets configured for the stale layout-sized surface and the
                // preview is displayed stretched until the next re-init. Wait —
                // event-driven, no fixed settle delay — for the surface to
                // report the requested buffer size before building the outputs.
                awaitViewfinderBufferSize(previewSize.width, previewSize.height)

                // The "record" target for video: in SUPER8/VHS it's the renderer's
                // SurfaceTexture (camera → GL → encoder); in NORMAL it's the
                // encoder's persistent input surface directly. The preview
                // surface is the viewfinder either way.
                val videoRecordTarget: Surface? =
                    if (usesGlPipeline()) analogRenderer?.inputSurfaceOrNull() else persistentSurface

                // Creates list of Surfaces where the camera will output frames.
                val targets = when {
                    isVideoMode -> listOf(fragmentCameraBinding.viewFinder.holder.surface, videoRecordTarget!!)
                    else -> listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)
                }

                // Creates list of OutputConfigurations where the camera will output frames
                val outputs = targets.map { surface ->
                    val config = android.hardware.camera2.params.OutputConfiguration(surface)
                    val logicalParent = physicalToLogicalMap[currentCameraId]
                    if (logicalParent != null && logicalParent != currentCameraId) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            config.setPhysicalCameraId(currentCameraId)
                        }
                    }
                    config
                }

                // Start a capture session using our open camera and list of OutputConfigurations
                Log.d(TAG, "Creating capture session...")
                session = createCaptureSession(camera, outputs, cameraHandler)
                Log.d(TAG, "Capture session created successfully.")

                val template = if (isVideoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                val captureRequest = camera.createCaptureRequest(template).apply {
                    addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                    // Feed the record target only while actually recording; in
                    // preview the camera just drives the viewfinder.
                    if (isRecordingVideo && videoRecordTarget != null) {
                        addTarget(videoRecordTarget)
                    }

                    applyCaptureRequestSettings(this)
                }

                // This will keep sending the capture request as frequently as possible until the
                // session is torn down or session.stopRepeating() is called
                session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

                initialized = true
                Log.d(TAG, "Camera initialized successfully.")
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to initialize camera components", exc)
            } finally {
                if (cameraJob == coroutineContext[kotlinx.coroutines.Job]) {
                    if (!initialized) {
                        Log.d(TAG, "Camera initialization failed or cancelled, releasing resources")
                        releaseResources()
                    }
                    reEnableUI()
                } else {
                    Log.d(TAG, "Camera initialization cancelled by a newer job, skipping cleanup/reEnableUI")
                }
            }
        }
    }

    /**
     * Capture button handler. Runs on the view-lifecycle scope so it's
     * automatically cancelled if the user leaves the fragment mid-save.
     * Shows real progress states matching the actual app flow:
     *
     *   capture press      → spinner + "Aufnahme…"
     *   HAL finished       → (JPEG mode only) spinner + "Konvertiere RAW → JPEG…"
     *   demosaic finished  → spinner + "Speichere JPEG…"  /  "Speichere RAW (DNG)…"
     *   write done         → ✓ "Gespeichert" (briefly)
     */
    private suspend fun captureSurfaceBitmap(surfaceView: SurfaceView): Bitmap? = suspendCancellableCoroutine { cont ->
        val width = surfaceView.width
        val height = surfaceView.height
        if (width <= 0 || height <= 0) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val handlerThread = HandlerThread("PixelCopyThread").apply { start() }
        try {
            PixelCopy.request(surfaceView, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    if (cont.isActive) cont.resume(bitmap)
                } else {
                    Log.w(TAG, "PixelCopy failed with code $copyResult")
                    if (cont.isActive) cont.resume(null)
                }
                handlerThread.quitSafely()
            }, Handler(handlerThread.looper))
        } catch (e: Exception) {
            Log.e(TAG, "PixelCopy failed with exception", e)
            if (cont.isActive) cont.resume(null)
            handlerThread.quitSafely()
        }
    }

    private fun handleCaptureClick(button: View) {
        Log.d(TAG, "handleCaptureClick called. session.isInitialized = ${::session.isInitialized}")
        if (!::session.isInitialized) return
        
        // Provide haptic feedback for shutter
        button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        // Disable while the save is in flight. Once the Done state lands,
        // the button is re-enabled and rebadged as "Take new" by
        // showProgress(Done) → updateCaptureButtonForState.
        button.isEnabled = false
        setProcessing(true)

        viewLifecycleOwner.lifecycleScope.launch {
            // Capture a snapshot of the viewfinder to freeze it on screen
            val rawFrozen = captureSurfaceBitmap(fragmentCameraBinding.viewFinder)
            val frozen = if (rawFrozen != null) {
                cropToAspectRatio(rawFrozen).also {
                    if (it !== rawFrozen) rawFrozen.recycle()
                }
            } else rawFrozen
            showProgress(CaptureProgress.Saving(frozen))
            try {
                val captured = withContext(Dispatchers.IO) { takePhoto() }
                val saved: SaveOutput = captured.use { result ->
                    when {
                        // RAW capture + user wants JPEG or WebP → demosaic into a Bitmap
                        // first, then encode as JPEG/WebP. The same demosaiced bitmap
                        // doubles as the source for the on-screen preview.
                        result.format == ImageFormat.RAW_SENSOR && (outputFormat == OutputFormat.JPEG || outputFormat == OutputFormat.WEBP) -> {
                            val bitmap = withContext(Dispatchers.IO) { rawToBitmap(result) }
                            try {
                                val croppedBitmap = cropToAspectRatio(bitmap)
                                // Apply the selected film simulation. NORMAL is a
                                // no-op and returns the same bitmap; otherwise the
                                // result may be a new mutable bitmap.
                                val finalBitmap = withContext(Dispatchers.Default) {
                                    FilmFilter.apply(croppedBitmap, filmSimulation)
                                }
                                try {
                                    val file = withContext(Dispatchers.IO) {
                                        if (outputFormat == OutputFormat.WEBP) {
                                            writeBitmapAsWebp(finalBitmap)
                                        } else {
                                            writeBitmapAsJpeg(finalBitmap)
                                        }
                                    }
                                    val thumb = withContext(Dispatchers.Default) { makeThumbnail(finalBitmap) }
                                    SaveOutput(file, thumb)
                                } finally {
                                    if (finalBitmap !== croppedBitmap && !finalBitmap.isRecycled) {
                                        finalBitmap.recycle()
                                    }
                                    if (croppedBitmap !== bitmap && !croppedBitmap.isRecycled) {
                                        croppedBitmap.recycle()
                                    }
                                }
                            } finally {
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                        }
                        // RAW capture + user wants RAW → write DNG straight through.
                        result.format == ImageFormat.RAW_SENSOR -> {
                            withContext(Dispatchers.IO) { writeRawAsDng(result) }
                        }
                        // Direct-JPEG fallback (camera doesn't support RAW).
                        result.format == ImageFormat.JPEG -> {
                            withContext(Dispatchers.IO) { writeJpegBytes(result) }
                        }
                        else -> throw RuntimeException("Unsupported capture format: ${result.format}")
                    }
                }
                Log.d(TAG, "Image saved: ${saved.file.absolutePath}")
                // Done state stays up until the user taps "Take new" on the
                // capture button — re-enable the button so it can act as
                // that dismiss control.
                setProcessing(false)
                showProgress(CaptureProgress.Done(saved.thumbnail))
                button.isEnabled = true
            } catch (exc: Exception) {
                Log.e(TAG, "Photo capture failed", exc)
                showProgress(CaptureProgress.Failed(exc.message ?: "Unknown"))
                kotlinx.coroutines.delay(ERROR_INDICATOR_MILLIS)
                setProcessing(false)
                hideProgress()
                button.isEnabled = true
            }
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    isCameraClosed = false
                    if (cont.isActive) {
                        cont.resume(device)
                    } else {
                        Log.d(TAG, "openCamera.onOpened: Coroutine was cancelled, closing device")
                        device.close()
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "Camera $cameraId has been disconnected")
                    isCameraClosed = true
                    device.close()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    isCameraClosed = true
                    device.close()
                    val msg = when (error) {
                        ERROR_CAMERA_DEVICE -> "Fatal (device)"
                        ERROR_CAMERA_DISABLED -> "Device policy"
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_CAMERA_SERVICE -> "Fatal (service)"
                        ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        else -> "Unknown"
                    }
                    val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                    Log.e(TAG, exc.message, exc)
                    if (cont.isActive) cont.resumeWithException(exc)
                }
            }, handler)
        } catch (e: Exception) {
             if (cont.isActive) cont.resumeWithException(e)
        }
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session.
     *
     * Uses the modern [SessionConfiguration] API (added in API 28). Min SDK is
     * 29 so we don't need the deprecated overload at all.
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        outputs: List<android.hardware.camera2.params.OutputConfiguration>,
        handler: Handler,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) {
                    cont.resume(session)
                } else {
                    Log.d(TAG, "createCaptureSession.onConfigured: Coroutine was cancelled, closing session")
                    session.close()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }
        val executor = java.util.concurrent.Executor { command -> handler.post(command) }
        val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
            android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            callback,
        )
        device.createCaptureSession(sessionConfig)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto():
            CombinedCaptureResult = suspendCancellableCoroutine { cont ->

        // Flush any images left in the image reader (must be closed to free buffers)
        while (true) {
            val pending = imageReader.acquireNextImage() ?: break
            pending.close()
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        // IMPORTANT: compute the JPEG orientation using the CURRENT lens's
        // characteristics. The user can switch front ↔ back ↔ wide ↔ tele in
        // the lens selector after this fragment was created, and each lens
        // can have a different SENSOR_ORIENTATION. Caching the value (or
        // caching the characteristics in OrientationLiveData) was the source
        // of the "selfie saved upside-down" bug.
        val deviceCw = relativeOrientation.value ?: when (
            fragmentCameraBinding.viewFinder.display?.rotation ?: Surface.ROTATION_0
        ) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val currentCharacteristics = characteristics
        val captureRotation = computeJpegOrientation(currentCharacteristics, deviceCw)
        val sensorOrientation = currentCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facingFront = currentCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        Log.d(TAG, "Capture orientation: cameraId=$currentCameraId facing=${if (facingFront) "FRONT" else "BACK"} sensor=$sensorOrientation° deviceCw=$deviceCw° → JPEG_ORIENTATION=$captureRotation°")

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE
        ).apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.JPEG_ORIENTATION, captureRotation)

            // Set flash mode for capture
            if (flashMode == CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH) {
                // Use FLASH_MODE_SINGLE for reliable flash triggering
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
        }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                _fragmentCameraBinding?.viewFinder?.post(shutterFlashTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable {
                    if (cont.isActive) cont.resumeWithException(exc)
                }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images until the timestamp matches the capture we're
                        // waiting for. (We only ever request RAW_SENSOR or JPEG, so the
                        // DEPTH_JPEG special case from the upstream sample isn't needed.)
                        val image = imageQueue.take()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.timestamp != resultTimestamp
                        ) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.isNotEmpty()) {
                            imageQueue.take().close()
                        }

                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(captureRotation, mirrored)

                        Log.d(TAG, "Capture orientation: rotation=$captureRotation°, mirrored=$mirrored, exif=$exifOrientation, cameraId=$currentCameraId")

                        // Build the result and resume progress (only if the awaiting
                        // coroutine is still active — it could have been cancelled
                        // while we were waiting for the HAL).
                        if (cont.isActive) {
                            cont.resume(
                                CombinedCaptureResult(
                                    image, result, exifOrientation, captureRotation, imageReader.imageFormat
                                )
                            )
                        } else {
                            image.close()
                        }
                        // Break out of the loop so this coroutine can finish — otherwise
                        // imageQueue.take() below would block this scope forever.
                        return@launch
                    }
                }
            }
        }, cameraHandler)
    }

    // -------- Persisting / converting captures --------

    /** A successfully saved file plus an in-memory thumbnail (may be null
     *  if the device couldn't decode the captured format — see DNG case). */
    private data class SaveOutput(val file: File, val thumbnail: Bitmap?)

    /** Downscales [source] to at most [maxDim] on the longer side. Returns a
     *  new Bitmap; never the same instance. 1280 keeps the saved-image
     *  preview crisp at the preview-card size on a modern phone without
     *  bloating the in-memory copy too much (~6 MB ARGB at most). */
    private fun makeThumbnail(source: Bitmap, maxDim: Int = 1280): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDim) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }
        val scale = maxDim.toFloat() / longest
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun cropToAspectRatio(bitmap: Bitmap): Bitmap {
        if (aspectRatio == AspectRatio.RATIO_4_3) return bitmap
        val isPortrait = bitmap.height > bitmap.width
        val (targetW, targetH) = when (aspectRatio) {
            AspectRatio.RATIO_1_1 -> Pair(1, 1)
            AspectRatio.RATIO_16_9 -> if (isPortrait) Pair(9, 16) else Pair(16, 9)
            else -> Pair(bitmap.width, bitmap.height)
        }
        val width = bitmap.width
        val height = bitmap.height
        
        val targetHeight = (width * targetH) / targetW
        return if (targetHeight <= height) {
            val y = (height - targetHeight) / 2
            Bitmap.createBitmap(bitmap, 0, y, width, targetHeight)
        } else {
            val targetWidth = (height * targetW) / targetH
            val x = (width - targetWidth) / 2
            Bitmap.createBitmap(bitmap, x, 0, targetWidth, height)
        }
    }

    /**
     * Demosaics the RAW sensor data into a Bitmap by writing a temporary DNG
     * and letting the platform's RAW decoder process it. The output is rotated
     * to upright orientation (so we don't depend on viewers honouring EXIF).
     *
     * No device ISP pipeline runs here — that's the point of the app: no
     * denoising, no sharpening, no tone mapping.
     */
    private fun rawToBitmap(result: CombinedCaptureResult): Bitmap {
        // In-memory DNG roundtrip: write to a ByteArrayOutputStream and
        // decode straight from the byte[]. Avoids two disk I/Os (write to
        // cache, read back) per capture — typically ~100-200 ms on a
        // 12-MP DNG, plus the delete() syscall.
        val dngCreator = DngCreator(characteristics, result.metadata)
        val baos = java.io.ByteArrayOutputStream(8 * 1024 * 1024)
        dngCreator.writeImage(baos, result.image)
        val dngBytes = baos.toByteArray()
        // inMutable=true means the decoded bitmap is mutable from the start,
        // so FilmFilter can write to it in place without first having to
        // allocate and copy a ~48 MB mutable replica.
        val opts = BitmapFactory.Options().apply { inMutable = true }
        val decoded = BitmapFactory.decodeByteArray(dngBytes, 0, dngBytes.size, opts)
            ?: throw IOException(
                "BitmapFactory could not decode DNG on this device — pick \"Save as RAW\" instead.",
            )
        val rotated = if (result.rotationDegrees != 0) {
            val matrix = android.graphics.Matrix().apply {
                postRotate(result.rotationDegrees.toFloat())
            }
            Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        Log.d(TAG, "RAW→Bitmap: ${decoded.width}x${decoded.height} → ${rotated.width}x${rotated.height} (${result.rotationDegrees}°)")
        return rotated
    }

    /** Encodes [bitmap] as a quality-100 JPEG into DCIM/Camera. */
    private fun writeBitmapAsJpeg(bitmap: Bitmap): File {
        val filename = "IMG_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            }
            val resolver = requireContext().contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { stream ->
                // Wrap with a BufferedOutputStream — MediaStore streams write
                // through to the storage layer per call; buffering lets the
                // JPEG encoder dump larger chunks at once.
                java.io.BufferedOutputStream(stream, 64 * 1024).use { buf ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, buf)
                }
            }
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            File(File(dcim, "Camera"), filename)
        } else {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val file = File(File(dcim, "Camera").apply { if (!exists()) mkdirs() }, filename)
            FileOutputStream(file).use { stream ->
                java.io.BufferedOutputStream(stream, 64 * 1024).use { buf ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, buf)
                }
            }
            file
        }
    }

    private fun writeBitmapAsWebp(bitmap: Bitmap): File {
        val filename = "IMG_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.webp"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/webp")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            }
            val resolver = requireContext().contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { stream ->
                java.io.BufferedOutputStream(stream, 64 * 1024).use { buf ->
                    val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    bitmap.compress(compressFormat, 90, buf)
                }
            }
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            File(File(dcim, "Camera"), filename)
        } else {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val file = File(File(dcim, "Camera").apply { if (!exists()) mkdirs() }, filename)
            FileOutputStream(file).use { stream ->
                java.io.BufferedOutputStream(stream, 64 * 1024).use { buf ->
                    val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    bitmap.compress(compressFormat, 90, buf)
                }
            }
            file
        }
    }

    /**
     * Writes [result] to a DNG file with the correct orientation tag, then
     * tries to decode the result for an on-screen thumbnail. The decode can
     * return null on devices whose [BitmapFactory] can't read DNG — in that
     * case the Done overlay falls back to a check icon.
     *
     * Implementation note: we go through a temp file so we can both write to
     * the final destination *and* decode a thumbnail without consuming the
     * captured [android.media.Image]'s buffers twice (DngCreator.writeImage
     * reads the planes through ByteBuffers and a second pass is not safe).
     */
    private fun writeRawAsDng(result: CombinedCaptureResult): SaveOutput {
        val dngCreator = DngCreator(characteristics, result.metadata)
        dngCreator.setOrientation(result.orientation)
        Log.d(TAG, "DNG orientation tag = ${result.orientation} (EXIF), rotation=${result.rotationDegrees}°")
        val filename = "RAW_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.dng"
        val tempDng = File(requireContext().cacheDir, "save_temp.dng")
        try {
            FileOutputStream(tempDng).use { stream ->
                dngCreator.writeImage(stream, result.image)
            }

            val finalFile: File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                }
                val resolver = requireContext().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("Failed to create MediaStore entry")
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(tempDng).use { it.copyTo(out) }
                } ?: throw IOException("Failed to open output stream")
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                File(File(dcim, "Camera"), filename)
            } else {
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val file = File(File(dcim, "Camera").apply { if (!exists()) mkdirs() }, filename)
                tempDng.copyTo(file, overwrite = true)
                file
            }

            val thumbnail: Bitmap? = try {
                BitmapFactory.decodeFile(tempDng.absolutePath)?.let { decoded ->
                    val rotated = if (result.rotationDegrees != 0) {
                        val matrix = android.graphics.Matrix().apply {
                            postRotate(result.rotationDegrees.toFloat())
                        }
                        Bitmap.createBitmap(
                            decoded, 0, 0, decoded.width, decoded.height, matrix, true,
                        ).also { r -> if (r !== decoded) decoded.recycle() }
                    } else decoded
                    val finalBitmap = cropToAspectRatio(rotated)
                    val small = makeThumbnail(finalBitmap)
                    if (small !== finalBitmap) finalBitmap.recycle()
                    if (finalBitmap !== rotated) rotated.recycle()
                    small
                }
            } catch (exc: Exception) {
                Log.w(TAG, "DNG thumbnail decode failed: ${exc.message}")
                null
            }
            return SaveOutput(finalFile, thumbnail)
        } finally {
            tempDng.delete()
        }
    }

    /**
     * Direct-JPEG fallback path: the camera couldn't deliver RAW so the HAL
     * already produced a finished JPEG (with [CaptureRequest.JPEG_ORIENTATION]
     * applied). We just need to flush the bytes to disk and decode a tiny
     * preview for the Done overlay.
     */
    private fun writeJpegBytes(result: CombinedCaptureResult): SaveOutput {
        val image = result.image
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Decode-and-reencode path: needed whenever we have to crop to square
        // OR apply a film simulation OR user requested WEBP.
        val needsDecode = aspectRatio != AspectRatio.RATIO_4_3 || filmSimulation != FilmSimulation.NORMAL || outputFormat == OutputFormat.WEBP
        if (needsDecode) {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("Failed to decode JPEG bytes for processing")
            val cropped = cropToAspectRatio(decoded)
            val processed = FilmFilter.apply(cropped, filmSimulation)
            try {
                val file = if (outputFormat == OutputFormat.WEBP) {
                    writeBitmapAsWebp(processed)
                } else {
                    writeBitmapAsJpeg(processed)
                }
                val thumb = makeThumbnail(processed)
                return SaveOutput(file, thumb)
            } finally {
                if (processed !== cropped && !processed.isRecycled) processed.recycle()
                if (cropped !== decoded && !cropped.isRecycled) cropped.recycle()
                if (!decoded.isRecycled) decoded.recycle()
            }
        }

        val filename = "IMG_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.jpg"
        val file: File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            }
            val resolver = requireContext().contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            File(File(dcim, "Camera"), filename)
        } else {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val out = File(File(dcim, "Camera").apply { if (!exists()) mkdirs() }, filename)
            FileOutputStream(out).use { it.write(bytes) }
            out
        }
        val thumbnail: Bitmap? = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { decoded ->
                val small = makeThumbnail(decoded)
                if (small !== decoded) decoded.recycle()
                small
            }
        } catch (exc: Exception) {
            Log.w(TAG, "JPEG thumbnail decode failed: ${exc.message}")
            null
        }
        return SaveOutput(file, thumbnail)
    }

    private fun saveSettings() {
        val sharedPrefs = requireContext().getSharedPreferences("unprocess_settings", Context.MODE_PRIVATE)
        sharedPrefs.edit().apply {
            putString("pref_output_format", outputFormat.name)
            putString("pref_aspect_ratio", aspectRatio.name)
            putString("pref_photo_aspect_ratio", photoAspectRatio.name)
            putString("pref_video_aspect_ratio", videoAspectRatio.name)
            putBoolean("pref_is_square", aspectRatio == AspectRatio.RATIO_1_1)
            putInt("pref_flash_mode", flashMode)
            putString("pref_film_simulation", filmSimulation.name)
            putBoolean("pref_is_video_mode", isVideoMode)
            putInt("pref_video_fps", videoFrameRate)
            putString("pref_video_resolution", videoResolution.name)
            putString("pref_video_preset", videoPreset.name)
            apply()
        }
    }

    override fun onStop() {
        super.onStop()
        cameraJob?.cancel()
        releaseResources()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    override fun onDestroyView() {
        doneThumbnail?.takeIf { !it.isRecycled }?.recycle()
        doneThumbnail = null
        frozenThumbnail?.takeIf { !it.isRecycled }?.recycle()
        frozenThumbnail = null
        persistentSurface?.release()
        persistentSurface = null
        analogRenderer?.release()
        analogRenderer = null
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** How long the error message stays visible before dismissing the overlay. */
        private const val ERROR_INDICATOR_MILLIS: Long = 1800



        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val rotationDegrees: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }
    }
}
