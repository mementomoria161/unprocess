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

package com.mementomoria.unprocess.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.lifecycle.LiveData


/**
 * Publishes the device's clockwise rotation (0, 90, 180, 270) relative to its
 * natural orientation, derived from the accelerometer via
 * [OrientationEventListener]. Camera-independent on purpose: any code that
 * needs a JPEG orientation must combine this with the *current* camera's
 * [CameraCharacteristics] at the moment of capture (see [computeJpegOrientation]).
 *
 * Caching characteristics inside this class is wrong because the user can
 * switch between physical cameras (back ↔ front, wide ↔ tele) while the
 * fragment lives, and each lens can have a different sensor orientation.
 */
class OrientationLiveData(context: Context) : LiveData<Int>() {

    private val appContext = context.applicationContext

    private val listener = object : OrientationEventListener(appContext) {
        override fun onOrientationChanged(orientation: Int) {
            // Device too close to flat — keep previous value, don't snap to 0.
            if (orientation == ORIENTATION_UNKNOWN) return
            val deviceCw = when {
                orientation <= 45 -> 0
                orientation <= 135 -> 90
                orientation <= 225 -> 180
                orientation <= 315 -> 270
                else -> 0
            }
            if (deviceCw != value) postValue(deviceCw)
        }
    }

    override fun onActive() {
        super.onActive()
        listener.enable()
        // Seed an initial value from the display rotation so a capture taken
        // immediately after launch (before the accelerometer fires) still has
        // a sensible value.
        if (value == null) {
            val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val rotation = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
            value = when (rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        }
    }

    override fun onInactive() {
        super.onInactive()
        listener.disable()
    }
}

/**
 * Computes the clockwise rotation (in degrees) needed to make a capture from
 * [characteristics] display upright on a device currently rotated [deviceCw]
 * degrees clockwise from its natural orientation. This matches the
 * `CaptureRequest.JPEG_ORIENTATION` contract.
 *
 * Always pass the *current* camera's characteristics — switching lenses
 * (front ↔ back, wide ↔ tele) can change the sensor orientation.
 */
fun computeJpegOrientation(
    characteristics: CameraCharacteristics,
    deviceCw: Int
): Int {
    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    val facingFront = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
    // Canonical formula from the Android camera2 docs:
    //   if (facingFront) deviceOrientation = -deviceOrientation
    //   jpeg = (sensorOrientation + deviceOrientation + 360) % 360
    val signedDevice = if (facingFront) -deviceCw else deviceCw
    return (sensorOrientation + signedDevice + 360) % 360
}
