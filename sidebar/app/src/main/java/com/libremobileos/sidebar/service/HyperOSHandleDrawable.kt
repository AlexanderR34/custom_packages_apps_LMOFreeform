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

    private val pillWidthPx = 4.5f * density
    private val pillHeightPx = 62f * density
    private val pillMarginEdgePx = 2.5f * density
    private val strokeWidthPx = 0.75f * density

    private val curvedStrokeWidthPx = 3.4f * density
    private val curvedOutlineWidthPx = 4.8f * density
    private val curveSweepXPx = 14f * density

    private val colorLightHandle = Color.argb(215, 250, 250, 252)
    private val strokeLightHandle = Color.argb(45, 0, 0, 0)

    private val colorDarkHandle = Color.argb(135, 38, 40, 46)
    private val strokeDarkHandle = Color.argb(55, 255, 255, 255)

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
        val halfHeight = pillHeightPx / 2f
        val top = centerY - halfHeight
        val bottom = centerY + halfHeight

        val left: Float
        val right: Float

        if (isLandscape) {
            if (isDockedOnLeft) {
                left = b.exactCenterX() + pillMarginEdgePx
                right = left + pillWidthPx
            } else {
                right = b.exactCenterX() - pillMarginEdgePx
                left = right - pillWidthPx
            }
        } else {
            if (isDockedOnLeft) {
                left = b.left + pillMarginEdgePx
                right = left + pillWidthPx
            } else {
                right = b.right - pillMarginEdgePx
                left = right - pillWidthPx
            }
        }

        rectF.set(left, top, right, bottom)
        val cornerRadius = pillWidthPx / 2f

        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, strokePaint)
    }

    private fun drawCurvedCornerHandle(canvas: Canvas, b: Rect) {
        val centerY = b.exactCenterY()
        val halfHeight = pillHeightPx / 2f
        val topY = centerY - halfHeight + 4f * density
        val bottomY = centerY + halfHeight
        val curveStartY = topY + pillHeightPx * 0.45f

        val startX: Float
        curvedPath.reset()

        if (isDockedOnLeft) {
            startX = b.exactCenterX() + 3.5f * density
            curvedPath.moveTo(startX, bottomY)
            curvedPath.lineTo(startX, curveStartY)
            curvedPath.quadTo(startX, topY, startX + curveSweepXPx, topY)
        } else {
            startX = b.exactCenterX() - 3.5f * density
            curvedPath.moveTo(startX, bottomY)
            curvedPath.lineTo(startX, curveStartY)
            curvedPath.quadTo(startX, topY, startX - curveSweepXPx, topY)
        }

        canvas.drawPath(curvedPath, curvedOutlinePaint)
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
