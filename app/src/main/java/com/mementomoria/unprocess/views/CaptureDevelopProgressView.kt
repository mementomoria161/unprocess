package com.mementomoria.unprocess.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * One continuous capture-pill background for the Developing state.
 *
 * The track and the fill are drawn in the same pass. This avoids the spacing,
 * stop marker, and overlapping outlines that a Material progress indicator
 * introduces when it is used as a full-size button background.
 */
class CaptureDevelopProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private val bounds = RectF()

    private var progressPercent = 0

    fun setColors(trackColor: Int, fillColor: Int) {
        trackPaint.color = trackColor
        fillPaint.color = fillColor
        invalidate()
    }

    fun setProgress(value: Int) {
        val clamped = value.coerceIn(0, 100)
        if (progressPercent == clamped) return
        progressPercent = clamped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val cornerRadius = height / 2f
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, trackPaint)

        if (progressPercent == 0) return

        // Clip the same rounded pill instead of drawing a second shape over
        // it: the left edge stays rounded and the advancing right edge is a
        // clean, true fill boundary.
        val fillRight = width * (progressPercent / 100f)
        canvas.save()
        canvas.clipRect(0f, 0f, fillRight, height.toFloat())
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)
        canvas.restore()
    }
}
