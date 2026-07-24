package com.mementomoria.unprocess.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.google.android.material.R as MaterialR
import com.mementomoria.unprocess.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A compact lens control that expands from the selected lens into a horizontal
 * pill. The selected lens is always held by the centre circle; dragging the
 * strip underneath it changes the active camera as each label reaches centre.
 */
class LensSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onLensSelected: ((Int) -> Unit)? = null
    var onExpansionChanged: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val compactDiameter = 56.dp
    private val itemSpacing = 64.dp
    private val touchSlop = 8.dp
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val panelRect = RectF()

    private val panelColor = colorFromTheme(MaterialR.attr.colorSecondaryContainer, Color.rgb(42, 42, 46))
    private val primaryColor = colorFromTheme(MaterialR.attr.colorPrimary, Color.rgb(208, 188, 255))
    private val onPrimaryColor = colorFromTheme(MaterialR.attr.colorOnPrimary, Color.BLACK)
    private val onSurfaceColor = colorFromTheme(MaterialR.attr.colorOnSurface, Color.WHITE)

    private var lensLabels: List<String> = emptyList()
    private var selectedIndex = 0
    private var selectionPosition = 0f
    private var expansionProgress = 0f
    private var isExpanded = false
    private var isTracking = false
    private var isPointerHovering = false
    private var downX = 0f
    private var startSelectionPosition = 0f
    private var startSelectedIndex = 0
    private var wasExpandedAtDown = false
    private var isDragging = false
    private var expandAnimator: ValueAnimator? = null
    private var settleAnimator: ValueAnimator? = null

    private val collapseRunnable = Runnable {
        if (!isTracking && !isPointerHovering) setExpanded(false)
    }
    private val expandOnHoldRunnable = Runnable {
        if (isTracking && !isDragging) setExpanded(true)
    }

    init {
        isClickable = true
        isFocusable = true
        isHapticFeedbackEnabled = true
        contentDescription = context.getString(R.string.lens_selector)
    }

    fun setLenses(labels: List<String>, selected: Int) {
        lensLabels = labels
        selectedIndex = selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        selectionPosition = selectedIndex.toFloat()
        updateContentDescription()
        requestLayout()
        invalidate()
    }

    fun setSelectedIndex(index: Int) {
        if (lensLabels.isEmpty()) return
        val resolvedIndex = index.coerceIn(0, lensLabels.lastIndex)
        selectedIndex = resolvedIndex
        if (!isTracking) selectionPosition = resolvedIndex.toFloat()
        updateContentDescription()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val measuredWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            compactDiameter.roundToInt()
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val measuredHeight = resolveSize(compactDiameter.roundToInt(), heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || lensLabels.isEmpty()) return

        val centreX = width / 2f
        val centreY = height / 2f
        val radius = min(width, height) / 2f
        if (expansionProgress > 0f) {
            // Labels and their background belong to one physical strip. The
            // strip travels behind the fixed middle selector as it is dragged.
            // Each outer label owns a 56dp circular cell. The end caps stop
            // exactly at that cell's outer edge, including at either limit.
            val stripLeft = centreX - selectionPosition * itemSpacing - radius
            val stripRight = centreX + (lensLabels.lastIndex - selectionPosition) * itemSpacing + radius
            canvas.save()
            canvas.scale(expansionProgress, 1f, centreX, centreY)
            panelPaint.color = panelColor
            if (stripRight > stripLeft) {
                panelRect.set(stripLeft, 0f, stripRight, height.toFloat())
                canvas.drawRoundRect(panelRect, radius, radius, panelPaint)
            }

            textPaint.textSize = 14.sp
            textPaint.color = onSurfaceColor
            textPaint.alpha = (expansionProgress * 215).roundToInt()
            lensLabels.forEachIndexed { index, label ->
                val x = centreX + (index - selectionPosition) * itemSpacing
                if (x > -itemSpacing && x < width + itemSpacing) {
                    canvas.drawText(label, x, centredTextBaseline(centreY, textPaint), textPaint)
                }
            }
            textPaint.alpha = 255
            canvas.restore()
        }

        selectionPaint.color = primaryColor
        canvas.drawCircle(centreX, centreY, radius, selectionPaint)
        textPaint.color = onPrimaryColor
        textPaint.textSize = 14.sp
        val selectedLabel = lensLabels.getOrNull(selectedIndex).orEmpty()
        canvas.drawText(selectedLabel, centreX, centredTextBaseline(centreY, textPaint), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || lensLabels.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (expansionProgress == 0f && abs(event.x - width / 2f) > compactDiameter / 2f) {
                    return false
                }
                removeCallbacks(collapseRunnable)
                isTracking = true
                isDragging = false
                wasExpandedAtDown = isExpanded
                downX = event.rawX
                startSelectionPosition = selectionPosition
                startSelectedIndex = selectedIndex
                if (!wasExpandedAtDown) {
                    postDelayed(expandOnHoldRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val delta = event.rawX - downX
                if (abs(delta) >= touchSlop) {
                    isDragging = true
                    removeCallbacks(expandOnHoldRunnable)
                    setExpanded(true)
                    updateSelectionPosition(startSelectionPosition - delta / itemSpacing)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(expandOnHoldRunnable)
                val wasTap = abs(event.rawX - downX) < touchSlop
                if (wasTap) performClick()
                isTracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (wasTap && !wasExpandedAtDown && !isExpanded) {
                    cycleToNextLens()
                } else {
                    settleOnSelectedLens()
                    // Dragging only previews a lens in the selector. Opening
                    // or reconfiguring the actual camera is intentionally
                    // deferred until the finger is released, avoiding a
                    // costly switch for every detent crossed during one
                    // gesture.
                    if (selectedIndex != startSelectedIndex) {
                        onLensSelected?.invoke(selectedIndex)
                    }
                }
                postDelayed(collapseRunnable, 160L)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(expandOnHoldRunnable)
                isTracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                selectedIndex = startSelectedIndex
                selectionPosition = startSelectedIndex.toFloat()
                updateContentDescription()
                invalidate()
                settleOnSelectedLens()
                postDelayed(collapseRunnable, 160L)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!isEnabled || lensLabels.isEmpty()) return super.onHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                if (expansionProgress == 0f && abs(event.x - width / 2f) > compactDiameter / 2f) {
                    return false
                }
                isPointerHovering = true
                removeCallbacks(collapseRunnable)
                setExpanded(true)
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                isPointerHovering = false
                postDelayed(collapseRunnable, 120L)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(collapseRunnable)
        removeCallbacks(expandOnHoldRunnable)
        expandAnimator?.cancel()
        settleAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        onExpansionChanged?.invoke(expanded)
        expandAnimator?.cancel()
        val start = expansionProgress
        val target = if (expanded) 1f else 0f
        expandAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                expansionProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateSelectionPosition(position: Float) {
        val clampedPosition = position.coerceIn(0f, lensLabels.lastIndex.toFloat())
        selectionPosition = clampedPosition
        val nextIndex = clampedPosition.roundToInt().coerceIn(0, lensLabels.lastIndex)
        if (nextIndex != selectedIndex) {
            selectedIndex = nextIndex
            updateContentDescription()
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        invalidate()
    }

    /** Advances the compact selector one lens and wraps after the final entry. */
    private fun cycleToNextLens() {
        if (lensLabels.isEmpty()) return
        settleAnimator?.cancel()
        selectedIndex = (selectedIndex + 1) % lensLabels.size
        selectionPosition = selectedIndex.toFloat()
        updateContentDescription()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
        onLensSelected?.invoke(selectedIndex)
    }

    private fun settleOnSelectedLens() {
        settleAnimator?.cancel()
        val start = selectionPosition
        val target = selectedIndex.toFloat()
        if (start == target) return
        settleAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                selectionPosition = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateContentDescription() {
        val label = lensLabels.getOrNull(selectedIndex) ?: return
        contentDescription = context.getString(R.string.lens_selector_selected, label)
    }

    private fun centredTextBaseline(centreY: Float, paint: Paint): Float =
        centreY - (paint.ascent() + paint.descent()) / 2f

    private fun colorFromTheme(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private val Int.dp: Float get() = this * density
    private val Int.sp: Float
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, toFloat(), resources.displayMetrics)
}
