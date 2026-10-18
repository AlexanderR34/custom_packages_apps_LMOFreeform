package com.libremobileos.sidebar.service

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator

class HyperOSHandleDrawable(private val context: Context) : Drawable() {

    private val density = context.resources.displayMetrics.density

    private val pillWidthPx = 3.8f * density
    private val pillHeightPx = 58f * density
    private val pillMarginEdgePx = 1.2f * density
    private val strokeWidthPx = 0.8f * density

    private val curvedStrokeWidthPx = 3.8f * density
    private val curvedOutlineWidthPx = 5.4f * density

    // State 1: Dark or Gray background (isDarkBackground = true):
    // Pure frosted glass white handle
    private val colorLightHandle = Color.argb(235, 255, 255, 255)
    private val strokeLightHandle = Color.TRANSPARENT

    // State 2: White or Light background (isDarkBackground = false):
    // Pure frosted graphite gray handle
    private val colorDarkHandle = Color.argb(225, 48, 52, 60)
    private val strokeDarkHandle = Color.TRANSPARENT

    private var currentFillColor = colorLightHandle
    private var currentStrokeColor = strokeLightHandle

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = currentFillColor
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = currentStrokeColor
    }

    private val curvedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = curvedStrokeWidthPx
        color = currentFillColor
    }

    private val curvedOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = curvedOutlineWidthPx
        color = currentStrokeColor
    }

    private val rectF = RectF()
    private val curvedPath = Path()
    private val argbEvaluator = ArgbEvaluator()
    private var animator: ValueAnimator? = null

    var lengthScale: Float = 1.0f
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    var widthScale: Float = 1.0f
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    var isDockedOnLeft: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    var isLandscape: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    var isCurvedCornerMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    var isDarkBackground: Boolean = true
        private set

    fun setLuminance(isDark: Boolean, animate: Boolean = true) {
        if (isDark == isDarkBackground && animator?.isRunning == true) return
        isDarkBackground = isDark

        val targetFill = if (isDark) colorLightHandle else colorDarkHandle
        val targetStroke = if (isDark) strokeLightHandle else strokeDarkHandle

        if (!animate) {
            animator?.cancel()
            currentFillColor = targetFill
            currentStrokeColor = targetStroke
            applyColors(targetFill, targetStroke)
            invalidateSelf()
            return
        }

        val startFill = currentFillColor
        val startStroke = currentStrokeColor

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val fraction = va.animatedFraction
                val fill = argbEvaluator.evaluate(fraction, startFill, targetFill) as Int
                val stroke = argbEvaluator.evaluate(fraction, startStroke, targetStroke) as Int
                applyColors(fill, stroke)
                invalidateSelf()
            }
            start()
        }
    }

    private fun applyColors(fill: Int, stroke: Int) {
        currentFillColor = fill
        currentStrokeColor = stroke
        fillPaint.color = fill
        strokePaint.color = stroke
        curvedPaint.color = fill
        curvedOutlinePaint.color = stroke
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        if (isCurvedCornerMode) {
            drawCurvedCornerHandle(canvas, b)
        } else {
            drawStraightPillHandle(canvas, b)
        }
    }

    private fun drawStraightPillHandle(canvas: Canvas, b: Rect) {
        val centerY = b.exactCenterY()
        val halfHeight = (pillHeightPx * lengthScale) / 2f
        val top = centerY - halfHeight
        val bottom = centerY + halfHeight

        val actualPillWidth = pillWidthPx * widthScale
        val left: Float
        val right: Float

        if (isDockedOnLeft) {
            left = b.exactCenterX() + pillMarginEdgePx
            right = left + actualPillWidth
        } else {
            right = b.exactCenterX() - pillMarginEdgePx
            left = right - actualPillWidth
        }

        rectF.set(left, top, right, bottom)
        val cornerRadius = actualPillWidth / 2f

        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, fillPaint)
    }

    private fun drawCurvedCornerHandle(canvas: Canvas, b: Rect) {
        val scaledPillHeight = pillHeightPx * lengthScale
        val cornerArcSize = 22f * density
        val actualStrokeWidth = curvedStrokeWidthPx * widthScale

        curvedPaint.strokeWidth = actualStrokeWidth

        // Provide 4dp clearance from b.top so the Paint.Cap.ROUND stroke cap is never clipped
        val topY = b.top.toFloat() + 4f * density + actualStrokeWidth / 2f
        val bottomY = topY + scaledPillHeight
        val curveStartY = topY + cornerArcSize

        val startX: Float
        curvedPath.reset()

        if (isDockedOnLeft) {
            startX = b.exactCenterX() + pillMarginEdgePx + actualStrokeWidth / 2f
            curvedPath.moveTo(startX, bottomY)
            curvedPath.lineTo(startX, curveStartY)
            curvedPath.quadTo(startX, topY, startX + cornerArcSize, topY)
        } else {
            startX = b.exactCenterX() - pillMarginEdgePx - actualStrokeWidth / 2f
            curvedPath.moveTo(startX, bottomY)
            curvedPath.lineTo(startX, curveStartY)
            curvedPath.quadTo(startX, topY, startX - cornerArcSize, topY)
        }

        canvas.drawPath(curvedPath, curvedPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = (alpha * (Color.alpha(currentFillColor) / 255f)).toInt()
        strokePaint.alpha = (alpha * (Color.alpha(currentStrokeColor) / 255f)).toInt()
        curvedPaint.alpha = (alpha * (Color.alpha(currentFillColor) / 255f)).toInt()
        curvedOutlinePaint.alpha = (alpha * (Color.alpha(currentStrokeColor) / 255f)).toInt()
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        curvedPaint.colorFilter = colorFilter
        curvedOutlinePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
