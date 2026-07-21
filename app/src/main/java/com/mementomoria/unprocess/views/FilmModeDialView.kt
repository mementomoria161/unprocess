package com.mementomoria.unprocess.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.google.android.material.R as MaterialR
import com.mementomoria.unprocess.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A freely rotating, detented selector for film looks.
 *
 * The centre arrow always points to twelve o'clock. The label that turns under
 * it is selected, while the outer wheel can keep turning through any number of
 * full rotations.
 */
class FilmModeDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onModeSelected: ((Int) -> Unit)? = null
    var onCenterClick: (() -> Unit)? = null
    var onCollapsedClick: (() -> Unit)? = null

    /** 0 is the compact film button; 1 is the full rotary selector. */
    private var expansionProgress = 0f
    private var isActive = false

    private var modes: List<String> = emptyList()
    private var selectedIndex = 0
    private var visualPosition = 0f
    private var rawPosition = 0f
    private var lastTouchAngle = 0f
    private var lastSubtleHapticPosition = 0f
    private var snapAnimator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = 8f * density
    private var downX = 0f
    private var downY = 0f
    private var downWasOnCenter = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val panelColor = colorFromTheme(MaterialR.attr.colorSecondaryContainer, Color.rgb(42, 42, 46))
    private val primaryColor = colorFromTheme(MaterialR.attr.colorPrimary, Color.rgb(208, 188, 255))
    private val onPrimaryColor = colorFromTheme(MaterialR.attr.colorOnPrimary, Color.BLACK)
    private val onSurfaceColor = colorFromTheme(MaterialR.attr.colorOnSurface, Color.WHITE)
    private val mutedColor = colorFromTheme(MaterialR.attr.colorOnSurfaceVariant, Color.LTGRAY)
    private val collapsedIcon = context.getDrawable(R.drawable.ic_camera_roll)?.mutate()

    init {
        isHapticFeedbackEnabled = true
        isFocusable = true
        isClickable = true
        contentDescription = context.getString(R.string.film_mode_selector)
    }

    fun setExpansionProgress(progress: Float) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        if (expansionProgress == clampedProgress) return
        expansionProgress = clampedProgress
        invalidate()
    }

    /** Highlights the compact button whenever a non-normal look is active. */
    fun setActive(active: Boolean) {
        if (isActive == active) return
        isActive = active
        invalidate()
    }

    fun setModes(modeNames: List<String>, selected: Int) {
        modes = modeNames
        selectedIndex = selected.coerceIn(0, (modeNames.size - 1).coerceAtLeast(0))
        rawPosition = selectedIndex.toFloat()
        visualPosition = selectedIndex.toFloat()
        lastSubtleHapticPosition = rawPosition
        updateContentDescription()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = 336.dp.roundToInt()
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f
        drawCollapsedButton(canvas, cx, cy, radius)
        if (modes.isEmpty() || expansionProgress <= 0f) return

        val layer = if (expansionProgress < 1f) {
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (expansionProgress * 255).roundToInt())
        } else {
            -1
        }

        val plateRadius = radius
        val labelRadius = plateRadius * 0.70f
        val knobRadius = plateRadius * 0.20f
        val step = 360f / modes.size

        fillPaint.color = panelColor
        canvas.drawCircle(cx, cy, plateRadius, fillPaint)

        strokePaint.color = Color.argb(90, Color.red(onSurfaceColor), Color.green(onSurfaceColor), Color.blue(onSurfaceColor))
        strokePaint.strokeWidth = 1.dp
        canvas.drawCircle(cx, cy, plateRadius - 1.dp, strokePaint)

        modes.forEachIndexed { index, label ->
            val angle = -90f + (index - visualPosition) * step
            val radians = angle * PI.toFloat() / 180f
            val cosAngle = cos(radians)
            val sinAngle = sin(radians)
            val isSelected = index == selectedIndex
            textPaint.textSize = if (isSelected) 15.sp else 13.sp
            textPaint.typeface = Typeface.create("sans-serif-medium", if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            val labelCenterX = cx + cosAngle * labelRadius
            val labelCenterY = cy + sinAngle * labelRadius

            if (isSelected) {
                val horizontalPadding = 10.dp
                val verticalPadding = 6.dp
                val textHeight = textPaint.descent() - textPaint.ascent()
                fillPaint.color = primaryColor
                canvas.drawRoundRect(
                    labelCenterX - textPaint.measureText(label) / 2f - horizontalPadding,
                    labelCenterY - textHeight / 2f - verticalPadding,
                    labelCenterX + textPaint.measureText(label) / 2f + horizontalPadding,
                    labelCenterY + textHeight / 2f + verticalPadding,
                    18.dp,
                    18.dp,
                    fillPaint,
                )
            }

            textPaint.color = if (isSelected) onPrimaryColor else mutedColor
            canvas.drawText(
                label,
                labelCenterX,
                labelCenterY - (textPaint.ascent() + textPaint.descent()) / 2f,
                textPaint,
            )
        }

        // Match the capture button: a flat Material primary-colour knob.
        fillPaint.color = primaryColor
        canvas.drawCircle(cx, cy, knobRadius, fillPaint)
        strokePaint.color = Color.argb(45, Color.red(onPrimaryColor), Color.green(onPrimaryColor), Color.blue(onPrimaryColor))
        strokePaint.strokeWidth = 1.dp
        canvas.drawCircle(cx, cy, knobRadius, strokePaint)

        // The arrowhead joins the knob seamlessly and marks twelve o'clock.
        fillPaint.color = primaryColor
        val arrowPath = android.graphics.Path().apply {
            moveTo(cx, cy - knobRadius - 12.dp)
            lineTo(cx - 10.dp, cy - knobRadius + 2.dp)
            lineTo(cx + 10.dp, cy - knobRadius + 2.dp)
            close()
        }
        canvas.drawPath(arrowPath, fillPaint)

        if (layer != -1) {
            canvas.restoreToCount(layer)
        }
    }

    /** The compact state is drawn by this very same view, so expansion has no view swap. */
    private fun drawCollapsedButton(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        fillPaint.color = if (isActive) primaryColor else panelColor
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, radius, fillPaint)

        val iconHalfSize = 15.dp.roundToInt()
        collapsedIcon?.apply {
            setTint(if (isActive) onPrimaryColor else onSurfaceColor)
            alpha = 255
            setBounds(
                (cx - iconHalfSize).roundToInt(),
                (cy - iconHalfSize).roundToInt(),
                (cx + iconHalfSize).roundToInt(),
                (cy + iconHalfSize).roundToInt(),
            )
            draw(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (expansionProgress < 1f) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop) {
                        performClick()
                        onCollapsedClick?.invoke()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }
        if (modes.size < 2) return true
        val cx = width / 2f
        val cy = height / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                downX = event.x
                downY = event.y
                downWasOnCenter = isCenterHit(event.x, event.y)
                lastTouchAngle = angleAt(event.x, event.y, cx, cy)
                lastSubtleHapticPosition = rawPosition
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val currentAngle = angleAt(event.x, event.y, cx, cy)
                val delta = signedAngleDifference(currentAngle, lastTouchAngle)
                lastTouchAngle = currentAngle
                val step = 360f / modes.size
                // Do not clamp this value: the label wheel can rotate forever.
                rawPosition -= delta / step

                val nearestDetent = rawPosition.roundToInt()
                val nearestMode = indexForDetent(nearestDetent)
                if (nearestMode != selectedIndex) {
                    selectedIndex = nearestMode
                    updateContentDescription()
                    onModeSelected?.invoke(selectedIndex)
                    performDetentHaptic()
                    lastSubtleHapticPosition = rawPosition
                } else if (abs(rawPosition - lastSubtleHapticPosition) >= 0.25f) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    lastSubtleHapticPosition = rawPosition
                }

                // Let the ring follow the finger between detents; it only
                // settles exactly onto a label once the gesture ends.
                visualPosition = rawPosition
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val wasTap = event.actionMasked == MotionEvent.ACTION_UP &&
                    abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop
                if (wasTap) {
                    performClick()
                    if (downWasOnCenter && isCenterHit(event.x, event.y)) {
                        onCenterClick?.invoke()
                    }
                }
                snapToSelected()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isCenterHit(x: Float, y: Float): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val plateRadius = min(width, height) / 2f - 8.dp
        val centerRadius = plateRadius * 0.20f
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= centerRadius * centerRadius
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun snapToSelected() {
        snapAnimator?.cancel()
        val start = visualPosition
        val end = rawPosition.roundToInt().toFloat()
        snapAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                visualPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        rawPosition = end
    }

    private fun performDetentHaptic() {
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            HapticFeedbackConstants.CONTEXT_CLICK
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        performHapticFeedback(feedback)
    }

    private fun indexForDetent(detent: Int): Int =
        ((detent % modes.size) + modes.size) % modes.size

    private fun updateContentDescription() {
        val selectedLabel = modes.getOrNull(selectedIndex) ?: return
        contentDescription = context.getString(R.string.film_mode_selected, selectedLabel)
    }

    private fun angleAt(x: Float, y: Float, cx: Float, cy: Float): Float =
        (atan2(y - cy, x - cx) * 180f / PI.toFloat())

    private fun signedAngleDifference(current: Float, previous: Float): Float {
        var difference = current - previous
        if (difference > 180f) difference -= 360f
        if (difference < -180f) difference += 360f
        return difference
    }

    private fun colorFromTheme(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private val Int.dp: Float get() = this * density
    private val Int.sp: Float
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, toFloat(), resources.displayMetrics)
}
