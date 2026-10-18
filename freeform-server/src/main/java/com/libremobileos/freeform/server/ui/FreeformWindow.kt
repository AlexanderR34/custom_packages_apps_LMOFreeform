package com.libremobileos.freeform.server.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.util.Slog
import android.view.Display
import android.view.DisplayInfo
import android.graphics.Matrix
import android.view.InputDevice
import android.view.GestureDetector
import android.view.IRotationWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.android.server.LocalServices
import com.android.server.wm.WindowManagerInternal
import com.libremobileos.freeform.ILMOFreeformDisplayCallback
import com.libremobileos.freeform.server.Debug.dlog
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import com.libremobileos.freeform.server.SystemServiceHolder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FreeformWindow(
    val handler: Handler,
    val context: Context,
    private val appConfig: AppConfig,
    val freeformConfig: FreeformConfig
): TextureView.SurfaceTextureListener, ILMOFreeformDisplayCallback.Stub(), View.OnTouchListener,
    WindowManagerInternal.DisplaySecureContentListener {

    var freeformTaskStackListener: FreeformTaskStackListener? = null
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val windowManagerInt = LocalServices.getService(WindowManagerInternal::class.java)
    val windowParams = WindowManager.LayoutParams()
    private val resourceHolder = RemoteResourceHolder(context, FREEFORM_PACKAGE)
    lateinit var freeformLayout: ViewGroup
    public lateinit var freeformRootView: ViewGroup
    lateinit var freeformView: TextureView
    private lateinit var topBarView: View
    private var bottomBarView: View? = null
    public lateinit var veilView: ViewGroup
    var displayId = Display.INVALID_DISPLAY
    var defaultDisplayWidth = context.resources.displayMetrics.widthPixels
    var defaultDisplayHeight = context.resources.displayMetrics.heightPixels
    var defaultDisplayRotation = context.display.rotation
    private val hangUpGestureListener = HangUpGestureListener(this)
    private val defaultDisplayInfo = DisplayInfo()
    private val destroyRunnable = Runnable { destroy("destroyRunnable", true) }
    
    private lateinit var appPackageName: String
    private var appIcon: Drawable? = null

    var isHyperOSStyle = false
    var isFloatingIcon = false
    private var actionSubmenuView: View? = null
    private var threeDotsHandle: View? = null
    private var headerContainer: View? = null
    private var floatingIconCard: View? = null
    private var floatingAppIcon: ImageView? = null
    var leftScaleView: View? = null
    var rightScaleView: View? = null

    fun resetCornerAutoHideTimer() {
        if (!isHyperOSStyle || isFloatingIcon) return
        if (!freeformConfig.isHangUp) {
            leftScaleView?.visibility = View.VISIBLE
            leftScaleView?.alpha = 1f
            rightScaleView?.visibility = View.VISIBLE
            rightScaleView?.alpha = 1f
        }
    }

    private val hideSubmenuRunnable = Runnable { hideSubmenuIfOpen() }

    fun toggleSubmenu() {
        actionSubmenuView?.let { submenu ->
            resetCornerAutoHideTimer()
            if (submenu.visibility == View.VISIBLE) {
                hideSubmenuIfOpen()
            } else {
                handler.removeCallbacks(hideSubmenuRunnable)
                threeDotsHandle?.animate()?.alpha(0f)?.setDuration(120)?.start()
                submenu.visibility = View.VISIBLE
                submenu.alpha = 0f
                submenu.scaleX = 0.85f
                submenu.scaleY = 0.85f
                submenu.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start()
                handler.postDelayed(hideSubmenuRunnable, 3500L)
            }
        }
    }

    fun hideSubmenuIfOpen() {
        handler.removeCallbacks(hideSubmenuRunnable)
        actionSubmenuView?.let { submenu ->
            if (submenu.visibility == View.VISIBLE) {
                submenu.animate()?.alpha(0f)?.scaleX(0.85f)?.scaleY(0.85f)?.setDuration(120)?.withEndAction {
                    submenu.visibility = View.GONE
                    threeDotsHandle?.animate()?.alpha(1f)?.setDuration(120)?.start()
                }?.start()
            }
        }
    }



    fun enterSplitScreen(position: Int) {
        val taskId = freeformTaskStackListener?.taskId ?: -1
        if (taskId == -1) {
            Slog.e(TAG, "taskId is -1, cannot split")
            return
        }
        dlog(TAG, "enterSplitScreen: taskId=$taskId position=$position")
        Thread {
            runCatching {
                SystemServiceHolder.activityTaskManager.moveRootTaskToDisplay(taskId, Display.DEFAULT_DISPLAY)
                val windowBinder = android.os.ServiceManager.getService("window")
                if (windowBinder != null) {
                    val pfdIn = android.os.ParcelFileDescriptor.createPipe()
                    val pfdOut = android.os.ParcelFileDescriptor.createPipe()
                    val pfdErr = android.os.ParcelFileDescriptor.createPipe()
                    windowBinder.shellCommand(
                        pfdIn[0].fileDescriptor,
                        pfdOut[1].fileDescriptor,
                        pfdErr[1].fileDescriptor,
                        arrayOf("shell", "splitscreen", "moveToSideStage", taskId.toString(), position.toString()),
                        null,
                        object : android.os.ResultReceiver(null) {}
                    )
                    runCatching {
                        pfdIn[0].close(); pfdIn[1].close()
                        pfdOut[0].close(); pfdOut[1].close()
                        pfdErr[0].close(); pfdErr[1].close()
                    }
                } else {
                    Runtime.getRuntime().exec(arrayOf("/system/bin/cmd", "window", "shell", "splitscreen", "moveToSideStage", taskId.toString(), position.toString()))
                }
            }.onFailure { e ->
                Slog.e(TAG, "failed to enter split screen: ", e)
            }
        }.start()
        destroy("enterSplitScreen", shouldRemoveTask = false)
    }

    private val rotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            dlog(TAG, "onRotationChanged($rotation)")
            defaultDisplayWidth = context.resources.displayMetrics.widthPixels
            defaultDisplayHeight = context.resources.displayMetrics.heightPixels
            defaultDisplayRotation = context.display.rotation
            measureSize()
            handler.post {
                changeOrientation()
                if (freeformConfig.isHangUp) toHangUp()
                else makeSureFreeformInScreen()
            }
            measureScale()
            LMOFreeformServiceHolder.resizeFreeform(
                this@FreeformWindow,
                freeformConfig.freeformWidth,
                freeformConfig.freeformHeight,
                freeformConfig.densityDpi
            )
            freeformView?.surfaceTexture?.setDefaultBufferSize(
                freeformConfig.freeformWidth,
                freeformConfig.freeformHeight
            )
        }
    }

    companion object {
        private const val TAG = "LMOFreeform/FreeformWindow"
        private const val FREEFORM_PACKAGE = "com.libremobileos.freeform"
        private const val FREEFORM_LAYOUT = "view_freeform"
        const val KEY_FREEFORM_WINDOW_STYLE = "freeform_window_style"
        const val STYLE_STOCK = "view_freeform"
        const val STYLE_NEW = "view_freeform_new"
        private const val WINDOW_DESTROY_WAIT_MS = 10000L
    }

    init {
        if (LMOFreeformServiceHolder.ping()) {
            Slog.i(TAG, "FreeformWindow init")
            extractPackageInfo()
            populateFreeformConfig()
            handler.post { if (!addFreeformView()) destroy("init:addFreeform failed") }
        } else {
            destroy("init:service not running")
            // NOT RUNNING !!!
        }
    }

    override fun onDisplayPaused() {
        //NOT USED
    }

    override fun onDisplayResumed() {
        //NOT USED
    }

    override fun onDisplayStopped() {
        //NOT USED
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        dlog(TAG, "onSurfaceTextureAvailable width:$width height:$height")
        if (displayId < 0) {
            LMOFreeformServiceHolder.createDisplay(freeformConfig, appConfig, Surface(surfaceTexture), this)
        }
        surfaceTexture.setDefaultBufferSize(freeformConfig.freeformWidth, freeformConfig.freeformHeight)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(freeformConfig.freeformWidth, freeformConfig.freeformHeight)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        //NOT USED
    }

    override fun onDisplayAdd(displayId: Int) {
        Slog.i(TAG, "onDisplayAdd displayId=$displayId, $appConfig")
        handler.post {
            this.displayId = displayId
            freeformTaskStackListener = FreeformTaskStackListener(displayId, this)
            SystemServiceHolder.activityTaskManager.registerTaskStackListener(freeformTaskStackListener)
            if (displayId > 0) {
                runCatching {
                    SystemServiceHolder.windowManager.setDisplayImePolicy(
                        displayId,
                        1 /* WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY */
                    )
                }.onFailure { Slog.e(TAG, "failed to setDisplayImePolicy for displayId=$displayId: $it") }
                runCatching {
                    windowManagerInt.moveDisplayToTopIfAllowed(displayId)
                }.onFailure { Slog.e(TAG, "failed to moveDisplayToTopIfAllowed for displayId=$displayId: $it") }
            }
            if (appConfig.taskId != -1) {
                dlog(TAG, "moving taskId=${appConfig.taskId} to freeform display")
                freeformTaskStackListener!!.taskId = appConfig.taskId
                runCatching {
                    // TODO: find a new way for this since getTaskDescription was removed in fwb commit a7cae90a991e
                    // if (SystemServiceHolder.activityTaskManager.getTaskDescription(appConfig.taskId) == null) {
                    //     throw Exception("stale task")
                    // }
                    SystemServiceHolder.activityTaskManager.moveRootTaskToDisplay(appConfig.taskId, displayId)
                }
                .onFailure { e ->
                    Slog.e(TAG, "failed to move task ${appConfig.taskId}: $e, fallback to startApp")
                    startApp()
                }
            } else if (appConfig.userId == -100) {
                if (appConfig.pendingIntent == null) destroy("onDisplayAdd:userId=-100, but pendingIntent is null")
                else {
                    LMOFreeformServiceHolder.startPendingIntent(appConfig.pendingIntent, displayId)
                }
            } else {
                startApp()
            }

            val arrowBack = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "arrowBack")
            if (null == arrowBack && !isHyperOSStyle) {
                Slog.e(TAG, "right&rightScale view is null")
                destroy("onDisplayAdd:backView is null")
                return@post
            }
            arrowBack?.setOnClickListener(RightViewClickListener(displayId))
        }
    }

    private fun startApp() {
        if (displayId == Display.INVALID_DISPLAY) {
            Slog.e(TAG, "cannot startApp: displayId not yet set!")
            return
        }
        if (LMOFreeformServiceHolder.startApp(context, appConfig, displayId).not())
            destroy("startApp failed")
    }

    override fun onDisplayHasSecureWindowOnScreenChanged(displayId: Int, hasSecureWindowOnScreen: Boolean) {
        if (displayId != this.displayId) return;
        dlog(TAG, "onDisplayHasSecureWindowOnScreenChanged: $hasSecureWindowOnScreen")
        windowParams.apply {
            flags = if (hasSecureWindowOnScreen) {
                flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                flags xor WindowManager.LayoutParams.FLAG_SECURE
            }
        }
        handler.post {
            runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }
                .onFailure { Slog.e(TAG, "updateViewLayout failed: $it") }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (isHyperOSStyle) {
            resetCornerAutoHideTimer()
            hideSubmenuIfOpen()
        }
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (displayId > 0) {
                runCatching { windowManagerInt.moveDisplayToTopIfAllowed(displayId) }
            }
        }
        val newEvent = MotionEvent.obtain(event)
        val scaleMatrix = Matrix().apply {
            setScale(freeformConfig.scale, freeformConfig.scale)
        }
        newEvent.transform(scaleMatrix)
        newEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        LMOFreeformServiceHolder.touch(newEvent, displayId)
        newEvent.recycle()
        return true
    }

    /**
     * get freeform screen dimen / freeform view dimen
     */
    private fun populateFreeformConfig() {
        measureSize()
        measureScale()
        context.display.getDisplayInfo(defaultDisplayInfo)
        freeformConfig.apply {
            refreshRate = defaultDisplayInfo.refreshRate
            presentationDeadlineNanos = defaultDisplayInfo.presentationDeadlineNanos
            dlog(TAG, "populateFreeformConfig: $this")
        }
    }

    fun measureSize() {
        val isPortrait = defaultDisplayRotation == Surface.ROTATION_0 ||
                defaultDisplayRotation == Surface.ROTATION_180
        freeformConfig.apply {
            height = (defaultDisplayHeight * (if (isPortrait) 0.5 else 0.6)).roundToInt()
            width = if (isPortrait) {
                (defaultDisplayWidth * 0.75).roundToInt()
            } else {
                // preserving the aspect ratio
                defaultDisplayHeight * defaultDisplayHeight / defaultDisplayWidth
            }
            val density = context.resources.displayMetrics.density
            val miniWidthDp = if (isPortrait) 105f else 150f
            hangUpWidth = (miniWidthDp * density).roundToInt()
            val windowRatio = height.toFloat() / max(1, width).toFloat()
            hangUpHeight = (hangUpWidth * windowRatio).roundToInt()
            dlog(TAG, "measureSize: isPortrait=$isPortrait width=$width height=$height hangUpWidth=$hangUpWidth hangUpHeight=$hangUpHeight")
        }
    }

    fun measureScale() {
        freeformConfig.apply {
            val widthScale = min(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / min(width, height)
            val heightScale = max(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / max(width, height)
            scale = min(widthScale, heightScale)
            freeformWidth = (width * scale).roundToInt()
            freeformHeight = (height * scale).roundToInt()
            dlog(TAG, "measureScale: $scale freeformWidth=$freeformWidth freeformHeight=$freeformHeight")
        }
    }

    /**
     * Called in system handler
     */
    @SuppressLint("WrongConstant")
    private fun addFreeformView(): Boolean {
        dlog(TAG, "addFreeformView")
        val layoutStyle = try {
            android.provider.Settings.System.getString(context.contentResolver, KEY_FREEFORM_WINDOW_STYLE) ?: STYLE_STOCK
        } catch (e: Exception) {
            STYLE_STOCK
        }
        val targetLayout = if (layoutStyle == STYLE_NEW) STYLE_NEW else STYLE_STOCK
        isHyperOSStyle = (targetLayout == STYLE_NEW)
        dlog(TAG, "addFreeformView using layout: $targetLayout (isHyperOSStyle=$isHyperOSStyle)")
        val freeformLayoutResolved = resourceHolder.getLayout(targetLayout) ?: resourceHolder.getLayout(STYLE_STOCK) ?: return false
        freeformLayout = freeformLayoutResolved
        freeformRootView = resourceHolder.getLayoutChildViewByTag<FrameLayout>(freeformLayout, "freeform_root") ?: return false
        veilView = resourceHolder.getLayoutChildViewByTag<FrameLayout>(freeformLayout, "veilView") ?: return false
        topBarView = resourceHolder.getLayoutChildViewByTag(freeformLayout, "topBarView") ?: return false
        val veilAppIconView = resourceHolder.getLayoutChildViewByTag<ImageView>(freeformLayout, "veilAppIcon")
        veilAppIconView?.setImageDrawable(appIcon)

        leftScaleView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "leftScaleView")
        rightScaleView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "rightScaleView")

        if (isHyperOSStyle) {
            headerContainer = resourceHolder.getLayoutChildViewByTag(freeformLayout, "headerContainer")
            threeDotsHandle = resourceHolder.getLayoutChildViewByTag(freeformLayout, "threeDotsHandle")
            actionSubmenuView = resourceHolder.getLayoutChildViewByTag(freeformLayout, "actionSubmenuView")
            val btnFullscreen = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "btnFullscreen")
            val btnSplitTop = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "btnSplitTop")
            val btnSplitBottom = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "btnSplitBottom")
            val btnMiniWindow = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "btnMiniWindow")
            val btnClose = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "btnClose")

            btnFullscreen?.setOnClickListener {
                hideSubmenuIfOpen()
                MaximizeClickListener(this).onClick(it)
            }
            btnSplitTop?.setOnClickListener {
                hideSubmenuIfOpen()
                enterSplitScreen(0)
            }
            btnSplitBottom?.setOnClickListener {
                hideSubmenuIfOpen()
                enterSplitScreen(1)
            }
            btnMiniWindow?.setOnClickListener {
                hideSubmenuIfOpen()
                handleHangUp()
            }
            btnClose?.setOnClickListener {
                hideSubmenuIfOpen()
                close()
            }

            floatingIconCard = resourceHolder.getLayoutChildViewByTag(freeformLayout, "floatingIconCard")
            floatingAppIcon = resourceHolder.getLayoutChildViewByTag(freeformLayout, "floatingAppIcon")
            floatingAppIcon?.setImageDrawable(appIcon)
            floatingIconCard?.setOnTouchListener(FloatingIconTouchListener(this))

            val moveTouchListener = MoveTouchListener(this)
            topBarView.setOnTouchListener(moveTouchListener)

            leftScaleView?.visibility = View.VISIBLE
            leftScaleView?.alpha = 1f
            rightScaleView?.visibility = View.VISIBLE
            rightScaleView?.alpha = 1f
            leftScaleView?.setOnTouchListener(ScaleTouchListener(this, false))
            rightScaleView?.setOnTouchListener(ScaleTouchListener(this, true))
        } else {
            bottomBarView = resourceHolder.getLayoutChildViewByTag(freeformLayout, "bottomBarView") ?: return false
            val appIconView = resourceHolder.getLayoutChildViewByTag<ImageView>(freeformLayout, "appIcon")
            val packageNameView = resourceHolder.getLayoutChildViewByTag<TextView>(freeformLayout, "packageName")
            val maximizeView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "maximizeView")
            val minimizeView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "minimizeView")
            val pinView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "pinView")
            if (null == minimizeView || null == leftScaleView || null == rightScaleView 
                    || null == maximizeView || null == pinView || null == appIconView || null == packageNameView
                    || null == veilAppIconView) {
                Slog.e(TAG, "left&leftScale&rightScale view is null")
                destroy("addFreeformView:left&leftScale&rightScale view is null")
                return false
            }

            val moveTouchListener = MoveTouchListener(this)
            topBarView.setOnTouchListener(moveTouchListener)
            bottomBarView?.setOnTouchListener(moveTouchListener)

            appIconView.setImageDrawable(appIcon)
            packageNameView.text = appPackageName
            minimizeView.setOnClickListener(LeftViewClickListener(this))
            maximizeView.setOnClickListener(MaximizeClickListener(this))
            pinView.setOnClickListener(PinClickListener(this))
            leftScaleView!!.setOnTouchListener(ScaleTouchListener(this, false))
            rightScaleView!!.setOnTouchListener(ScaleTouchListener(this))
        }

        freeformView = FreeformTextureView(context).apply {
            setOnTouchListener(this@FreeformWindow)
            surfaceTextureListener = this@FreeformWindow
        }
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = freeformConfig.width
            height = freeformConfig.height
        }
        freeformRootView.addView(freeformView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val isPortrait = defaultDisplayRotation == Surface.ROTATION_0 ||
                defaultDisplayRotation == Surface.ROTATION_180
        val sidebarPositionX = try {
            android.provider.Settings.System.getInt(context.contentResolver, "sidebar_position_x", 1)
        } catch (e: Exception) {
            1
        }
        val sidebarPositionY = try {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                if (isPortrait) "sidebar_position_y_portrait" else "sidebar_position_y_landscape",
                0
            )
        } catch (e: Exception) {
            0
        }

        val density = context.resources.displayMetrics.density
        val marginX = (48 * density).roundToInt()
        val maxAvailableX = max(0, (defaultDisplayWidth - freeformConfig.width) / 2 - marginX)
        val initialOffsetMultiplier = if (isPortrait) 0.50f else 0.65f
        val calculatedX = (maxAvailableX * initialOffsetMultiplier).roundToInt()
        val marginY = (36 * density).roundToInt()
        val maxY = max(0, (defaultDisplayHeight - freeformConfig.height) / 2 - marginY)

        windowParams.apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            format = PixelFormat.RGBA_8888
            windowAnimations = 0
            x = if (sidebarPositionX >= 0) calculatedX else -calculatedX
            y = sidebarPositionY.coerceIn(-maxY, maxY)
        }
        freeformConfig.notInHangUpX = windowParams.x
        freeformConfig.notInHangUpY = windowParams.y
        runCatching {
            windowManager.addView(freeformLayout, windowParams)
            FreeformAnimation.playEnterAnimation(this, sidebarPositionX)
            SystemServiceHolder.windowManager.watchRotation(rotationWatcher, Display.DEFAULT_DISPLAY)
            windowManagerInt.registerDisplaySecureContentListener(this)
        }.onFailure {
            Slog.e(TAG, "addView failed: $it")
            return false
        }
        return true
    }

    /**
     * Called in system handler
     */
    @SuppressLint("ClickableViewAccessibility")
    fun handleHangUp() {
        if (freeformConfig.isHangUp) {
            windowParams.apply {
                x = freeformConfig.notInHangUpX
                y = freeformConfig.notInHangUpY
                flags = flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
            freeformRootView.layoutParams.apply {
                width = freeformConfig.width
                height = freeformConfig.height
            }
            windowManager.updateViewLayout(freeformLayout, windowParams)
            headerContainer?.visibility = View.VISIBLE
            topBarView.visibility = View.VISIBLE
            bottomBarView?.visibility = View.VISIBLE
            freeformConfig.isHangUp = false
            freeformView.setOnTouchListener(this)
            if (isHyperOSStyle) {
                setCardRadius(24f)
                leftScaleView?.visibility = View.VISIBLE
                rightScaleView?.visibility = View.VISIBLE
                leftScaleView?.alpha = 1f
                rightScaleView?.alpha = 1f
            }
        } else {
            hideSubmenuIfOpen()
            freeformConfig.notInHangUpX = windowParams.x
            freeformConfig.notInHangUpY = windowParams.y
            toHangUp()
            headerContainer?.visibility = View.GONE
            topBarView.visibility = View.GONE
            bottomBarView?.visibility = View.GONE
            freeformConfig.isHangUp = true
            val gestureDetector = GestureDetector(context, hangUpGestureListener)
            freeformView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP) makeSureFreeformInScreen()
                true
            }
        }
    }

    /**
     * Called in system handler
     */
    fun toHangUp() {
        if (isHyperOSStyle) {
            val density = context.resources.displayMetrics.density
            val cardPadding = (6 * density).roundToInt()
            val totalWindowHalfWidth = (freeformConfig.hangUpWidth / 2) + cardPadding
            val snapRight = windowParams.x >= 0
            val targetX = if (snapRight) {
                (defaultDisplayWidth / 2 - totalWindowHalfWidth)
            } else {
                -(defaultDisplayWidth / 2 - totalWindowHalfWidth)
            }
            val topMargin = (48 * density).roundToInt()
            val targetY = -(defaultDisplayHeight / 2 - freeformConfig.hangUpHeight / 2 - topMargin)
            windowParams.apply {
                x = targetX
                y = targetY
                flags = flags xor WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
            setCardRadius(18f)
            updateMiniWindowCorners(snapRight)
        } else {
            windowParams.apply {
                x = (defaultDisplayWidth / 2 - freeformConfig.hangUpWidth / 2)
                y = -(defaultDisplayHeight / 2 - freeformConfig.hangUpHeight / 2)
                flags = flags xor WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
        }
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = freeformConfig.hangUpWidth
            height = freeformConfig.hangUpHeight
        }
        runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }.onFailure { Slog.e(TAG, "$it") }
    }

    fun updateMiniWindowCorners(snapRight: Boolean) {
        if (!isHyperOSStyle || isFloatingIcon) return
        if (snapRight) {
            leftScaleView?.visibility = View.VISIBLE
            leftScaleView?.alpha = 1f
            rightScaleView?.visibility = View.GONE
        } else {
            rightScaleView?.visibility = View.VISIBLE
            rightScaleView?.alpha = 1f
            leftScaleView?.visibility = View.GONE
        }
    }

    fun snapMiniWindowToLateralEdge() {
        if (isFloatingIcon) return
        val density = context.resources.displayMetrics.density
        val cardPadding = (6 * density).roundToInt()
        val totalWindowHalfWidth = (freeformConfig.hangUpWidth / 2) + cardPadding
        val snapRight = windowParams.x >= 0
        val targetX = if (snapRight) {
            (defaultDisplayWidth / 2 - totalWindowHalfWidth)
        } else {
            -(defaultDisplayWidth / 2 - totalWindowHalfWidth)
        }
        val topMargin = (48 * density).roundToInt()
        val maxY = defaultDisplayHeight / 2 - freeformConfig.hangUpHeight / 2 - (16 * density).roundToInt()
        val minY = -(defaultDisplayHeight / 2 - freeformConfig.hangUpHeight / 2 - topMargin)
        val targetY = windowParams.y.coerceIn(minY, maxY)

        FreeformAnimation.moveInScreenAnimator(windowParams.x, targetX, 250, true, this)
        if (windowParams.y != targetY) {
            FreeformAnimation.moveInScreenAnimator(windowParams.y, targetY, 250, false, this)
        }
        updateMiniWindowCorners(snapRight)
    }

    fun toFloatingIcon() {
        if (!isHyperOSStyle) return
        isFloatingIcon = true
        freeformConfig.isHangUp = true
        hideSubmenuIfOpen()

        val cardView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "windowCard")
        cardView?.visibility = View.GONE
        leftScaleView?.visibility = View.GONE
        rightScaleView?.visibility = View.GONE
        leftScaleView?.isEnabled = false
        rightScaleView?.isEnabled = false

        floatingIconCard?.visibility = View.VISIBLE
        floatingAppIcon?.setImageDrawable(appIcon)

        val density = context.resources.displayMetrics.density
        val iconSize = (56 * density).roundToInt()
        val iconHalfSize = iconSize / 2

        val maxX = (defaultDisplayWidth / 2 - iconHalfSize)
        val minX = -maxX
        val topMargin = (48 * density).roundToInt()
        val bottomMargin = (36 * density).roundToInt()
        val minY = -(defaultDisplayHeight / 2 - iconHalfSize - topMargin)
        val maxY = (defaultDisplayHeight / 2 - iconHalfSize - bottomMargin)

        windowParams.x = windowParams.x.coerceIn(minX, maxX)
        windowParams.y = windowParams.y.coerceIn(minY, maxY)

        runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }
    }

    fun restoreFromFloatingIcon() {
        if (!isFloatingIcon) return
        isFloatingIcon = false
        floatingIconCard?.visibility = View.GONE
        leftScaleView?.isEnabled = true
        rightScaleView?.isEnabled = true

        val cardView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "windowCard")
        cardView?.visibility = View.VISIBLE
        setCardRadius(18f)

        freeformConfig.isHangUp = true
        headerContainer?.visibility = View.GONE
        topBarView.visibility = View.GONE

        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = freeformConfig.hangUpWidth
            height = freeformConfig.hangUpHeight
        }

        val gestureDetector = GestureDetector(context, hangUpGestureListener)
        freeformView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) makeSureFreeformInScreen()
            true
        }

        snapMiniWindowToLateralEdge()
        runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }
    }

    fun expandFromMiniMode() {
        freeformConfig.isHangUp = false
        headerContainer?.visibility = View.VISIBLE
        topBarView.visibility = View.VISIBLE
        freeformView.setOnTouchListener(this)
        setCardRadius(24f)
        leftScaleView?.visibility = View.VISIBLE
        rightScaleView?.visibility = View.VISIBLE
        leftScaleView?.alpha = 1f
        rightScaleView?.alpha = 1f
    }

    fun setCardRadius(radiusDp: Float) {
        val cardView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "windowCard") ?: return
        val density = context.resources.displayMetrics.density
        runCatching {
            val method = cardView.javaClass.getMethod("setRadius", Float::class.javaPrimitiveType)
            method.invoke(cardView, radiusDp * density)
        }
    }

    /**
     * Called in uiHandler
     */
    fun makeSureFreeformInScreen() {
        if (isFloatingIcon) return
        if (freeformConfig.isHangUp) {
            if (isHyperOSStyle) {
                snapMiniWindowToLateralEdge()
            }
            return
        }
        val maxWidth = defaultDisplayWidth
        val maxHeight = (defaultDisplayHeight * 0.9).roundToInt()
        if (freeformRootView.layoutParams.width > maxWidth || freeformRootView.layoutParams.height > maxHeight) {
            freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
                width = min(freeformRootView.width, maxWidth)
                height = min(freeformRootView.height, maxHeight)
            }
        }
        if (windowParams.x < -(defaultDisplayWidth / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.x, -(defaultDisplayWidth / 2), 300, true, this)
        else if (windowParams.x > (defaultDisplayWidth / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.x, (defaultDisplayWidth / 2), 300, true, this)
        if (windowParams.y < -(defaultDisplayHeight / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.y, -(defaultDisplayHeight / 2), 300, false, this)
        else if (windowParams.y > (defaultDisplayHeight / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.y, (defaultDisplayHeight / 2), 300, false, this)
    }

    /**
     * Change freeform orientation
     * Called in system handler
     */
    fun changeOrientation() {
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = if (freeformConfig.isHangUp) freeformConfig.hangUpWidth else freeformConfig.width
            height = if (freeformConfig.isHangUp) freeformConfig.hangUpHeight else freeformConfig.height
        }
    }

    fun getFreeformId(): String {
        return "${appConfig.packageName},${appConfig.activityName},${appConfig.userId}"
    }

    private fun shouldKeepTaskAlive(): Boolean {
        // 0. Check custom whitelist or global keep alive setting
        try {
            val keepAliveSetting = android.provider.Settings.System.getString(
                context.contentResolver,
                "lmo_freeform_keep_alive_apps"
            )
            if (!keepAliveSetting.isNullOrEmpty()) {
                val list = keepAliveSetting.split(",")
                if (list.contains(appConfig.packageName) || list.contains("*")) {
                    return true
                }
            }
        } catch (e: Exception) {
            Slog.w(TAG, "failed to check keep alive settings: $e")
        }

        // 1. Check if audio / music is playing or active communication
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (audioManager != null && (audioManager.isMusicActive || audioManager.mode != android.media.AudioManager.MODE_NORMAL)) {
                return true
            }
        } catch (e: Exception) {
            Slog.w(TAG, "failed to check audio manager: $e")
        }

        // 2. Check if the app has an active MediaSession (PlaybackState PLAYING / BUFFERING / CONNECTING)
        try {
            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager
            val sessions = mediaSessionManager?.getActiveSessions(null)
            if (sessions != null) {
                for (controller in sessions) {
                    if (controller.packageName == appConfig.packageName) {
                        val state = controller.playbackState?.state
                        if (state == android.media.session.PlaybackState.STATE_PLAYING ||
                            state == android.media.session.PlaybackState.STATE_BUFFERING ||
                            state == android.media.session.PlaybackState.STATE_CONNECTING) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Slog.w(TAG, "failed to check media session: $e")
        }

        // 3. Check if the package is running a Foreground Service (Audio, Download, etc.)
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val processes = am?.runningAppProcesses
            if (processes != null) {
                for (proc in processes) {
                    if (proc.pkgList != null && proc.pkgList.contains(appConfig.packageName)) {
                        if (proc.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Slog.w(TAG, "failed to check running app processes: $e")
        }

        return false
    }

    fun close() {
        dlog(TAG, "close()")
        val currentTaskId = freeformTaskStackListener?.taskId ?: -1
        if (currentTaskId != -1 && shouldKeepTaskAlive()) {
            Slog.i(TAG, "App ${appConfig.packageName} has active playback/foreground service; preserving in background")
            runCatching {
                SystemServiceHolder.activityTaskManager.moveRootTaskToDisplayOnTopOrBottom(currentTaskId, Display.DEFAULT_DISPLAY, false)
            }.onFailure { exception ->
                Slog.e(TAG, "moveRootTaskToDisplayOnTopOrBottom failed: ", exception)
            }
            destroy("window.close(preserveBackgroundPlayback)", shouldRemoveTask = false)
            return
        }

        runCatching {
            SystemServiceHolder.activityTaskManager.removeTask(freeformTaskStackListener!!.taskId)
            removeView()
        }.onFailure { exception ->
            Slog.e(TAG, "removeTask failed: ", exception)
            destroy("window.close() fallback")
        }
    }

    fun removeView(runDestroy: Boolean = true) {
        dlog(TAG, "removeView($runDestroy)")
        handler.removeCallbacks(destroyRunnable)
        handler.post {
            runCatching {
                windowManager.removeViewImmediate(freeformLayout)
                dlog(TAG, "removeView success")
            }.onFailure { exception ->
                Slog.e(TAG, "removeView failed $exception")
            }
        }
        // wait for onTaskRemoved(), but take it into our own hands in case its never triggered.
        if (runDestroy)
            handler.postDelayed(destroyRunnable, WINDOW_DESTROY_WAIT_MS)
    }

    fun destroy(callReason: String, shouldRemoveTask: Boolean = false) {
        Slog.i(TAG, "destroy ${getFreeformId()}, displayId=$displayId callReason: $callReason")
        removeView(false)
        handler.removeCallbacks(destroyRunnable)
        SystemServiceHolder.activityTaskManager.unregisterTaskStackListener(freeformTaskStackListener)
        SystemServiceHolder.windowManager.removeRotationWatcher(rotationWatcher)
        LMOFreeformServiceHolder.releaseFreeform(this)
        FreeformWindowManager.removeWindow(getFreeformId())
        windowManagerInt.unregisterDisplaySecureContentListener(this)
        freeformTaskStackListener?.taskId?.let {
            if (it != -1 && shouldRemoveTask && !shouldKeepTaskAlive()) {
                Slog.i(TAG, "destroy: remove taskId $it again")
                runCatching { SystemServiceHolder.activityTaskManager.removeTask(it) }
            }
        }
    }
    
    private fun extractPackageInfo() {
        try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(appConfig.packageName, 0)
            appPackageName = pm.getApplicationLabel(ai).toString()
            appIcon = pm.getApplicationIcon(ai)
        } catch (e: Exception) {
            Slog.e(TAG, "Failed to retrieve app info: ${e.message}")
            appPackageName = ""
            appIcon = null
        }
    }
}
