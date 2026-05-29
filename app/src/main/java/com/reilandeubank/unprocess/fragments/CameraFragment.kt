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

package com.reilandeubank.unprocess.fragments

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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.reilandeubank.unprocess.utils.computeExifOrientation
import com.reilandeubank.unprocess.utils.computeJpegOrientation
import com.reilandeubank.unprocess.utils.getPreviewOutputSize
import com.reilandeubank.unprocess.utils.OrientationLiveData
import com.reilandeubank.unprocess.R
import com.reilandeubank.unprocess.databinding.FragmentCameraBinding
import com.reilandeubank.unprocess.filter.FilmFilter
import com.reilandeubank.unprocess.filter.FilmSimulation
import kotlinx.coroutines.Dispatchers
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
    private var isSquare: Boolean = false

    private var isSettingsMode = false

    /** Currently selected film simulation. Cycled via the filter toggle. */
    private var filmSimulation: FilmSimulation = FilmSimulation.NORMAL

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
        currentCameraId = args.cameraId
        outputFormat = when (args.outputFormat.uppercase()) {
            "RAW" -> OutputFormat.RAW
            "WEBP" -> OutputFormat.WEBP
            else -> OutputFormat.JPEG
        }

        updateViewfinderRatio()
        updateModeToggleUI()
        updateFlashUI()
        updateAspectRatioUI()
        updateSettingsUI()
        setupLensSelector()

        fragmentCameraBinding.modeToggle?.setOnClickListener {
            outputFormat = when (outputFormat) {
                OutputFormat.RAW -> OutputFormat.JPEG
                OutputFormat.JPEG -> OutputFormat.WEBP
                OutputFormat.WEBP -> OutputFormat.RAW
            }
            updateModeToggleUI()
            updateSettingsUI()
            // The capture format is always RAW_SENSOR — only the save path
            // differs (DNG vs DNG→Bitmap→JPEG/WEBP). No need to restart the session.
        }

        fragmentCameraBinding.aspectRatioToggle?.setOnClickListener {
            isSquare = !isSquare
            updateAspectRatioUI()
            updateViewfinderRatio()
            updateSettingsUI()
            if (!isShowingDone) {
                initializeCamera()
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
            
            // Try to update the existing session if possible
            try {
                if (::session.isInitialized) {
                        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                            set(CaptureRequest.CONTROL_AE_MODE, flashMode)
                            // We don't use TORCH for standard flash toggle, common camera apps use strobe
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

        fragmentCameraBinding.settingsToggle?.setOnClickListener {
            toggleSettingsMode()
        }

        fragmentCameraBinding.filterToggle?.setOnClickListener {
            filmSimulation = filmSimulation.next()
            updateFilterUI()
            updateSettingsUI()
        }

        val navOffsetListener = androidx.core.view.OnApplyWindowInsetsListener { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.translationX = (-systemBars.right).toFloat()
            v.translationY = (-systemBars.bottom).toFloat()
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
            if (isShowingDone) {
                hideProgress()
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
                // Wait for layout to settle before initializing camera to prevent stretching
                view.postDelayed({
                    if (_fragmentCameraBinding != null && isAdded && !isShowingDone) {
                        initializeCamera()
                        // Run it a second time after a short delay to ensure surface sizes are fully applied
                        view.postDelayed({
                            if (_fragmentCameraBinding != null && isAdded && !isShowingDone) {
                                initializeCamera()
                            }
                        }, 300L)
                    }
                }, 250L)
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
        fragmentCameraBinding.modeToggle?.text = outputFormat.name
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
        fragmentCameraBinding.aspectRatioToggle?.text = getString(
            if (isSquare) R.string.aspect_ratio_square else R.string.aspect_ratio_full
        )
    }

    private var allCameraIds: List<String> = emptyList()

    private data class LensEntry(
        val id: String,
        val focalLength: Float,
        val label: String,
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
            LensEntry(id, focal, label)
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
        val button = _fragmentCameraBinding?.captureButton ?: return
        if (button.isEnabled) {
            button.performClick()
        }
    }

    private fun toggleSettingsMode() {
        isSettingsMode = !isSettingsMode
        updateSettingsUI()
    }

    private fun updateSettingsUI() {
        val binding = _fragmentCameraBinding ?: return
        
        // Always show the standard settings gear icon
        binding.settingsToggle?.setIconResource(R.drawable.ic_settings)
        
        // Settings gear button is highlighted (active) when settings mode is OFF (inactive),
        // and grayed out (inactive) when settings mode is ON (active)
        binding.settingsToggle?.let { setButtonActiveStyle(it, !isSettingsMode) }
        
        // Flash toggle is always visible and always active
        binding.flashToggle?.visibility = View.VISIBLE
        setButtonActiveStyle(binding.flashToggle, true)
        
        // Aspect ratio, format/mode and filter toggles are shown only in
        // settings mode, and are always active when visible.
        val settingsVisibility = if (isSettingsMode) View.VISIBLE else View.GONE
        binding.aspectRatioToggle?.visibility = settingsVisibility
        binding.modeToggle?.visibility = settingsVisibility
        binding.filterToggle?.visibility = settingsVisibility

        setButtonActiveStyle(binding.aspectRatioToggle, true)
        setButtonActiveStyle(binding.modeToggle, true)

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
        val filterAvailable = !isProcessing && (outputFormat == OutputFormat.JPEG || outputFormat == OutputFormat.WEBP)
        setButtonActiveStyle(binding.filterToggle, filterAvailable)
        binding.filterToggle?.isEnabled = filterAvailable

        updateAspectRatioUI()
        updateFlashUI()
        updateModeToggleUI()
        updateFilterUI()
    }

    private fun updateFilterUI() {
        fragmentCameraBinding.filterToggle?.text = filmSimulation.displayName
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

    private fun setButtonActiveStyle(button: com.google.android.material.button.MaterialButton?, active: Boolean) {
        if (button == null) return
        if (active) {
            button.backgroundTintList = ColorStateList.valueOf(getPrimaryColor())
            button.setTextColor(Color.BLACK)
            button.iconTint = ColorStateList.valueOf(Color.BLACK)
        } else {
            button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            button.setTextColor(Color.GRAY)
            button.iconTint = ColorStateList.valueOf(Color.GRAY)
        }
    }

    private fun updateViewfinderRatio() {
        try {
            val ch = characteristics
            val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val streamMap = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (streamMap != null) {
                val supportedSizes = streamMap.getOutputSizes(args.pixelFormat)
                val format = if (supportedSizes.isNullOrEmpty()) ImageFormat.JPEG else args.pixelFormat
                val finalSizes = if (format == args.pixelFormat) supportedSizes else streamMap.getOutputSizes(format)
                val size = finalSizes?.maxByOrNull { it.height * it.width }
                if (size != null) {
                    val needsSwap = (sensorOrientation == 90 || sensorOrientation == 270)
                    val displayWidth = if (needsSwap) size.height else size.width
                    val displayHeight = if (needsSwap) size.width else size.height
                    val ratio = if (isSquare) "1:1" else "$displayWidth:$displayHeight"

                    val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
                    constraintSet.clone(fragmentCameraBinding.root as androidx.constraintlayout.widget.ConstraintLayout)
                    constraintSet.setDimensionRatio(R.id.view_finder_container, ratio)
                    constraintSet.applyTo(fragmentCameraBinding.root as androidx.constraintlayout.widget.ConstraintLayout)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update viewfinder ratio constraints", e)
        }
    }

    // -------- Capture progress UI --------

    /** Bitmap currently shown in the Done overlay. Recycled when we leave
     *  the Done state (Take-new pressed, error, fragment torn down). */
    private var doneThumbnail: Bitmap? = null

    /** True while the Done overlay (saved image) is visible — in that state
     *  the capture button doubles as "Take new". */
    private var isShowingDone: Boolean = false

    /** True from the moment of the capture press until the save (or error
     *  fallback) has finished — drives the "Developing…" button label. */
    private var isProcessing: Boolean = false

    /** Lifecycle stage of a capture, drives the overlay state machine. */
    private sealed class CaptureProgress {
        /** HAL is exposing + the file is being saved. UI: just a dim. */
        object Saving : CaptureProgress()
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
                // Dim the preview, no thumbnail yet, no spinner, no text.
                thumbnail.visibility = View.GONE
                thumbnail.setImageDrawable(null)
                overlay.visibility = View.VISIBLE
                overlay.alpha = 0f
                overlay.animate().alpha(1f).setDuration(180L).start()
                isShowingDone = false
                updateCaptureButtonForState()
            }
            is CaptureProgress.Done -> {
                overlay.visibility = View.VISIBLE
                overlay.alpha = 1f
                val oldBitmap = doneThumbnail
                doneThumbnail = state.thumbnail
                if (state.thumbnail != null) {
                    thumbnail.setImageBitmap(state.thumbnail)
                    thumbnail.visibility = View.VISIBLE
                    
                    // Adjust viewfinder container ratio to match the captured thumbnail aspect ratio
                    val ratio = "${state.thumbnail.width}:${state.thumbnail.height}"
                    val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
                    constraintSet.clone(binding.root as androidx.constraintlayout.widget.ConstraintLayout)
                    constraintSet.setDimensionRatio(R.id.view_finder_container, ratio)
                    constraintSet.applyTo(binding.root as androidx.constraintlayout.widget.ConstraintLayout)
                } else {
                    // Decoder failed (typical for DNG-only on some devices).
                    // Keep the dim — there's just no preview image to show.
                    thumbnail.setImageDrawable(null)
                    thumbnail.visibility = View.GONE
                }
                oldBitmap?.takeIf { it !== state.thumbnail && !it.isRecycled }?.recycle()
                isShowingDone = true
                updateCaptureButtonForState()
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
        doneThumbnail?.takeIf { !it.isRecycled }?.recycle()
        doneThumbnail = null
        isShowingDone = false
        updateCaptureButtonForState()
        
        // Restore/start camera feed preview
        initializeCamera()
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
                else -> R.string.capture
            }
        )
    }

    private fun releaseResources() {
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
        }
        if (::imageReader.isInitialized) {
            try { imageReader.close() } catch (exc: Throwable) {
                Log.w(TAG, "imageReader.close failed: ${exc.message}")
            }
        }
    }

    private fun reEnableUI() {
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
        
        // Disable UI during initialization (lens buttons too, so the user can't
        // bounce between lenses faster than the camera service can re-open).
        fragmentCameraBinding.captureButton.isEnabled = false
        val lensContainer = fragmentCameraBinding.lensSelectorContainer
        for (i in 0 until (lensContainer?.childCount ?: 0)) {
            lensContainer?.getChildAt(i)?.isEnabled = false
        }

        cameraJob = lifecycleScope.launch(Dispatchers.Main) {
            var initialized = false
            try {
                // Release existing resources before opening a new camera
                releaseResources()
                
                // Wait a bit for the system to settle
                kotlinx.coroutines.delay(250)

                // Open the logical camera parent (or the camera itself if it is logical)
                val cameraToOpen = physicalToLogicalMap[currentCameraId] ?: currentCameraId
                try {
                    camera = openCamera(cameraManager, cameraToOpen, cameraHandler)
                } catch (exc: Exception) {
                    Log.e(TAG, "Failed to open camera $cameraToOpen, trying fallback", exc)
                    val fallbackId = physicalToLogicalMap[currentCameraId]
                    if (fallbackId != null && fallbackId != currentCameraId) {
                        currentCameraId = fallbackId
                        camera = openCamera(cameraManager, currentCameraId, cameraHandler)
                        updateLensHighlight()
                    } else {
                        throw exc
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
                
                // Both "Save as RAW" and "Save as JPEG" capture RAW sensor data —
                // that's the whole point of an "unprocess" app. JPEG mode just
                // demosaics the RAW into a Bitmap and re-encodes as JPEG, without
                // running the device's ISP (no denoising, sharpening, tone mapping,
                // colour-profile bake-in). The HAL's direct JPEG output would be
                // smaller/prettier but heavily processed — exactly what we want
                // to avoid.
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
                Log.d(TAG, "Capture format=$format (outputFormat=$outputFormat), size=${size.width}x${size.height}")
                
                imageReader = ImageReader.newInstance(
                    size.width, size.height, format, IMAGE_BUFFER_SIZE
                )

                // Pick a preview size capped at the display resolution to avoid
                // wasting GPU bandwidth on full-sensor previews (often 4K+).
                val captureRatio = size.width.toFloat() / size.height.toFloat()
                val previewSize = getPreviewOutputSize(
                    fragmentCameraBinding.viewFinder.display,
                    characteristics,
                    SurfaceHolder::class.java,
                    aspectRatio = captureRatio
                )
                Log.d(TAG, "Capture: ${size.width}x${size.height} ratio=$captureRatio | preview: ${previewSize.width}x${previewSize.height}")

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

                val ratio = if (isSquare) "1:1" else "$displayWidth:$displayHeight"
                val facingFrontPreview = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                Log.d(TAG, "PREVIEW DIAG | camera=$currentCameraId facing=${if (facingFrontPreview) "FRONT" else "BACK"} sensor=${sensorOrientation}° deviceDisplay=${deviceRotation}° swap=$needsSwap | buffer=${previewSize.width}x${previewSize.height} viewAspect=$displayWidth:$displayHeight container=$ratio")

                val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
                constraintSet.clone(fragmentCameraBinding.root as androidx.constraintlayout.widget.ConstraintLayout)
                constraintSet.setDimensionRatio(R.id.view_finder_container, ratio)
                constraintSet.applyTo(fragmentCameraBinding.root as androidx.constraintlayout.widget.ConstraintLayout)

                // Creates list of Surfaces where the camera will output frames
                val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)

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
                session = createCaptureSession(camera, outputs, cameraHandler)

                val captureRequest = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
                ).apply { 
                    addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, flashMode)
                }

                // This will keep sending the capture request as frequently as possible until the
                // session is torn down or session.stopRepeating() is called
                session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

                initialized = true
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to initialize camera components", exc)
            } finally {
                if (!initialized) {
                    Log.d(TAG, "Camera initialization failed or cancelled, releasing resources")
                    releaseResources()
                }
                reEnableUI()
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
    private fun handleCaptureClick(button: View) {
        if (!::session.isInitialized) return
        
        // Provide haptic feedback for shutter
        button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        // Disable while the save is in flight. Once the Done state lands,
        // the button is re-enabled and rebadged as "Take new" by
        // showProgress(Done) → updateCaptureButtonForState.
        button.isEnabled = false
        setProcessing(true)

        viewLifecycleOwner.lifecycleScope.launch {
            // Single dim from button-press through save-complete — no
            // intermediate spinner / text states.
            showProgress(CaptureProgress.Saving)
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
                                val croppedBitmap = if (isSquare) cropToSquare(bitmap) else bitmap
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
                override fun onOpened(device: CameraDevice) = cont.resume(device)

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "Camera $cameraId has been disconnected")
                    // Do not finish activity, just warn
                }

                override fun onError(device: CameraDevice, error: Int) {
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
                if (cont.isActive) cont.resume(session)
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

    private fun cropToSquare(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val minDim = minOf(width, height)
        val x = (width - minDim) / 2
        val y = (height - minDim) / 2
        return Bitmap.createBitmap(bitmap, x, y, minDim, minDim)
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
                    val finalBitmap = if (isSquare) cropToSquare(rotated) else rotated
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
        val needsDecode = isSquare || filmSimulation != FilmSimulation.NORMAL || outputFormat == OutputFormat.WEBP
        if (needsDecode) {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("Failed to decode JPEG bytes for processing")
            val cropped = if (isSquare) cropToSquare(decoded) else decoded
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
