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

package com.reilandeubank.unprocess.utils

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * A [SurfaceView] that can be adjusted to a specified aspect ratio and
 * performs center-crop transformation of input frames.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var aspectRatio = 0f

    /**
     * Sets the aspect ratio used to size this view in its parent. The caller
     * must pass the dimensions as seen on the display (i.e. already swapped if
     * the camera frames will be rotated 90/270 degrees to match the display).
     * Buffer size for the surface is configured separately via [setBufferSize].
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative" }
        aspectRatio = width.toFloat() / height.toFloat()
        requestLayout()
    }

    /** Sets the fixed buffer size used by the underlying surface (sensor coordinates). */
    fun setBufferSize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative" }
        holder.setFixedSize(width, height)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (aspectRatio == 0f) {
            setMeasuredDimension(width, height)
        } else {
            // Center-fit using the provided (display-oriented) aspect ratio.
            val newWidth: Int
            val newHeight: Int
            if (width > height * aspectRatio) {
                newHeight = height
                newWidth = (height * aspectRatio).roundToInt()
            } else {
                newWidth = width
                newHeight = (width / aspectRatio).roundToInt()
            }

            Log.d(TAG, "Measured dimensions set: $newWidth x $newHeight (aspectRatio=$aspectRatio)")
            setMeasuredDimension(newWidth, newHeight)
        }
    }

    companion object {
        private val TAG = AutoFitSurfaceView::class.java.simpleName
    }
}
