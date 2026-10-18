package com.libremobileos.freeform.server.ui

import android.annotation.SuppressLint
import android.os.Build
import android.util.Slog
import android.view.Display
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import com.libremobileos.freeform.server.SystemServiceHolder
import kotlin.math.max
import kotlin.math.roundToInt

class MoveTouchListener(
    private val window: FreeformWindow
) : View.OnTouchListener {
    private var startX = 0.0f
    private var startY = 0.0f
    private var isDragging = false
    private val touchSlop = 16f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                isDragging = false
                window.resetCornerAutoHideTimer()
                if (window.displayId > 0) {
                    runCatching { window.windowManagerInt.moveDisplayToTopIfAllowed(window.displayId) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                if (!isDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                    isDragging = true
                    window.hideSubmenuIfOpen()
                }
                if (isDragging) {
                    window.windowManager.updateViewLayout(window.freeformLayout, window.windowParams.apply {
                        x = (x + dx).roundToInt()
                        y = (y + dy).roundToInt()
                    })
                    startX = event.rawX
                    startY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    v.performClick()
                    window.toggleSubmenu()
                } else {
                    window.makeSureFreeformInScreen()
                }
            }
        }
        return true
    }
}

class LeftViewClickListener(private val window: FreeformWindow) : View.OnClickListener {
    override fun onClick(v: View) {
        window.close()
    }

}

/**
 * maximize freeform screen
 */
class MaximizeClickListener(private val window: FreeformWindow): View.OnClickListener {
    companion object {
        private const val TAG = "LMOFreeform/TouchListener"
    }
    override fun onClick(v: View) {
        if (null != window.freeformTaskStackListener) {
            if (window.freeformTaskStackListener!!.taskId == -1) {
                Slog.e(TAG, "taskId is -1, can`t move")
                return
            }
            runCatching { SystemServiceHolder.activityTaskManager.moveRootTaskToDisplay(window.freeformTaskStackListener!!.taskId, Display.DEFAULT_DISPLAY) }
        }
    }
}

/**
 * Pin freeform
 */
class PinClickListener(private val window: FreeformWindow): View.OnClickListener {
    override fun onClick(v: View) {
        window.handler.post {
            // hangup
            window.handleHangUp()
        }
    }
}

class RightViewClickListener(private val displayId: Int) : View.OnClickListener {
    override fun onClick(v: View) {
        LMOFreeformServiceHolder.back(displayId)
    }
}

class ScaleTouchListener(private val window: FreeformWindow, private val isRight: Boolean = true): View.OnTouchListener {
    private var startX = 0.0f
    private var startY = 0.0f
    private var initialWidth = 0
    private var initialHeight = 0
    private var initialWindowX = 0
    private var initialWindowY = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (window.isFloatingIcon) {
            return false
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                initialWidth = if (window.freeformRootView.layoutParams.width > 0) window.freeformRootView.layoutParams.width else window.freeformConfig.width
                initialHeight = if (window.freeformRootView.layoutParams.height > 0) window.freeformRootView.layoutParams.height else window.freeformConfig.height
                initialWindowX = window.windowParams.x
                initialWindowY = window.windowParams.y

                window.hideSubmenuIfOpen()

                if (window.displayId > 0) {
                    runCatching { window.windowManagerInt.moveDisplayToTopIfAllowed(window.displayId) }
                }

                if (window.freeformConfig.isHangUp && !window.isHyperOSStyle) {
                    window.handleHangUp()
                    return true
                }
                // Keep the app view live and visible at all times during resize
                window.freeformRootView.visibility = View.VISIBLE
                window.veilView.visibility = View.GONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - startX).roundToInt()
                val dy = (event.rawY - startY).roundToInt()

                if (window.isHyperOSStyle && window.freeformConfig.isHangUp) {
                    val density = window.context.resources.displayMetrics.density
                    val contractThreshold = (22 * density).roundToInt()
                    val expandThreshold = (20 * density).roundToInt()

                    // isRight is true when corner is at bottom-right, false when bottom-left
                    val contractX = if (isRight) -dx else dx
                    if (contractX > contractThreshold || -dy > contractThreshold) {
                        // User contracted the mini window inwards -> Switch to Floating Icon mode!
                        window.toFloatingIcon()
                        return true
                    }

                    val expandX = if (isRight) dx else -dx
                    if (expandX > expandThreshold || dy > expandThreshold) {
                        // User expands -> Switch to normal freeform resizing
                        window.expandFromMiniMode()
                    } else {
                        return true
                    }
                }

                val density = window.context.resources.displayMetrics.density
                val minWidth = (210 * density).roundToInt()
                val minHeight = (260 * density).roundToInt()
                val maxWidth = window.defaultDisplayWidth
                val maxHeight = (window.defaultDisplayHeight * 0.9f).roundToInt()

                val newWidth = if (isRight) {
                    (initialWidth + dx).coerceIn(minWidth, maxWidth)
                } else {
                    (initialWidth - dx).coerceIn(minWidth, maxWidth)
                }
                val newHeight = (initialHeight + dy).coerceIn(minHeight, maxHeight)

                window.freeformRootView.layoutParams = window.freeformRootView.layoutParams.apply {
                    width = newWidth
                    height = newHeight
                }
                window.freeformRootView.requestLayout()
            }
            MotionEvent.ACTION_UP -> {
                if (window.isFloatingIcon) {
                    return true
                }
                if (window.freeformView.surfaceTexture != null) {
                    val finalWidth = window.freeformRootView.layoutParams.width
                    val finalHeight = window.freeformRootView.layoutParams.height
                    window.freeformConfig.width = finalWidth
                    window.freeformConfig.height = finalHeight

                    val deltaX = finalWidth - initialWidth
                    val deltaY = finalHeight - initialHeight
                    window.windowParams.x = if (isRight) initialWindowX + deltaX / 2 else initialWindowX - deltaX / 2
                    window.windowParams.y = initialWindowY + deltaY / 2
                    runCatching { window.windowManager.updateViewLayout(window.freeformLayout, window.windowParams) }

                    window.handler.post { window.makeSureFreeformInScreen() }
                    window.measureScale()
                    LMOFreeformServiceHolder.resizeFreeform(
                        window,
                        window.freeformConfig.freeformWidth,
                        window.freeformConfig.freeformHeight,
                        window.freeformConfig.densityDpi
                    )
                    window.freeformView.surfaceTexture!!.setDefaultBufferSize(window.freeformConfig.freeformWidth, window.freeformConfig.freeformHeight)
                    window.freeformRootView.visibility = View.VISIBLE
                    window.veilView.visibility = View.GONE
                }
            }
        }
        return true
    }
}

class HangUpGestureListener(private val window: FreeformWindow) : SimpleOnGestureListener() {
    private var startX = 0
    private var startY = 0
    override fun onDown(e: MotionEvent): Boolean {
        startX = window.windowParams.x
        startY = window.windowParams.y
        return super.onDown(e)
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (window.isHyperOSStyle) {
            if (window.displayId > 0) {
                runCatching { window.windowManagerInt.moveDisplayToTopIfAllowed(window.displayId) }
            }
            return true
        }
        window.handler.post { window.handleHangUp() }
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (!isValidMotionEvent(e1) || !isValidMotionEvent(e2)) {
            return true
        }

        val e1RawX = e1?.rawX ?: 0f
        val e1RawY = e1?.rawY ?: 0f

        if (!isValidCoordinate(e1RawX) || !isValidCoordinate(e1RawY) 
                || !isValidCoordinate(e2.rawX) || !isValidCoordinate(e2.rawY)) {
            return true
        }
        
        val newX = (startX + e2.rawX - e1RawX).roundToInt()
        val newY = (startY + e2.rawY - e1RawY).roundToInt()

        try {
            window.handler.post {
                window.windowManager.updateViewLayout(window.freeformLayout, window.windowParams.apply {
                    x = newX
                    y = newY
                })
            }
        } catch (e: Exception) {}
        return true
    }

    fun isValidMotionEvent(event: MotionEvent?): Boolean {
        return event != null &&
                !event.rawX.isNaN() &&
                !event.rawY.isNaN() &&
                event.rawX.isFinite() &&
                event.rawY.isFinite()
    }
    
    fun isValidCoordinate(coordinate: Float): Boolean {
        return !coordinate.isNaN() && coordinate.isFinite()
    }
}

class FloatingIconTouchListener(private val window: FreeformWindow) : View.OnTouchListener {
    private var startRawX = 0f
    private var startRawY = 0f
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = event.rawX
                startRawY = event.rawY
                initialX = window.windowParams.x
                initialY = window.windowParams.y
                isDragging = false
                if (window.displayId > 0) {
                    runCatching { window.windowManagerInt.moveDisplayToTopIfAllowed(window.displayId) }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                val density = window.context.resources.displayMetrics.density
                val touchSlop = 16f * density
                if (!isDragging && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    isDragging = true
                }
                if (isDragging) {
                    val iconHalfSize = (28 * density).roundToInt()
                    val maxX = (window.defaultDisplayWidth / 2 - iconHalfSize)
                    val minX = -maxX
                    val topMargin = (48 * density).roundToInt()
                    val bottomMargin = (36 * density).roundToInt()
                    val minY = -(window.defaultDisplayHeight / 2 - iconHalfSize - topMargin)
                    val maxY = (window.defaultDisplayHeight / 2 - iconHalfSize - bottomMargin)

                    window.windowParams.x = (initialX + dx.roundToInt()).coerceIn(minX, maxX)
                    window.windowParams.y = (initialY + dy.roundToInt()).coerceIn(minY, maxY)
                    runCatching { window.windowManager.updateViewLayout(window.freeformLayout, window.windowParams) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    v.performClick()
                    window.restoreFromFloatingIcon()
                }
                return true
            }
        }
        return false
    }
}

