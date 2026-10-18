package com.libremobileos.sidebar.service

import android.annotation.SuppressLint
import android.app.IActivityManager
import android.app.Service
import android.app.UserSwitchObserver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.ServiceManager
import android.os.UserHandle
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import com.android.internal.policy.SystemBarUtils
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.utils.Logger
import kotlin.math.roundToInt

class SidebarService : Service(), SharedPreferences.OnSharedPreferenceChangeListener,
    GestureListener.Callback {
    private val logger = Logger(TAG)
    private lateinit var viewModel: ServiceViewModel
    private lateinit var windowManager: WindowManager
    private lateinit var iActivityManager: IActivityManager
    private lateinit var sidebarView: SidebarView
    private lateinit var sharedPrefs: SharedPreferences
    private var userId = 0
    private var serviceStarted = false
    private var showSideline = false
    private var isShowingSidebar = false
    private var isShowingSideline = false
    private var sidelinePositionX = 0
    private var sidelinePositionY = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private val layoutParams = LayoutParams()
    private val handler = Handler()
    private var adaptiveLuminanceHelper: AdaptiveLuminanceHelper? = null
    private var hyperOSHandleDrawable: HyperOSHandleDrawable? = null
    private var isGameSessionActive = false
    private val isGameActive: Boolean
        get() {
            val secureGame = runCatching {
                android.provider.Settings.Secure.getStringForUser(
                    contentResolver,
                    "gamespace_active_game",
                    UserHandle.USER_CURRENT
                )
            }.getOrNull()
            return isGameSessionActive || !secureGame.isNullOrEmpty()
        }
    private val isNewStyle: Boolean
        get() {
            val sp = if (::sharedPrefs.isInitialized) sharedPrefs
                     else applicationContext.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
            return sp.getString(
                SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
                SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
            ) == SidebarApplication.STYLE_NEW
        }

    private val gameSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_GAME_SESSION_STATE -> {
                    val active = intent.getBooleanExtra("active", false)
                    logger.d("gameSessionReceiver active=$active")
                    isGameSessionActive = active
                }
                ACTION_DISMISS_SIDEBAR -> {
                    sidebarView.removeView()
                }
            }
        }
    }

    private val sideLineView by lazy {
        val gestureManager = MGestureManager(this@SidebarService, GestureListener(this@SidebarService))
        View(this).apply {
            background = AppCompatResources.getDrawable(this@SidebarService, R.drawable.ic_line)
            setOnTouchListener { _, event ->
                gestureManager.onTouchEvent(event)
                true
            }
        }
    }

    private fun updateCurvedCornerState() {
        val handle = hyperOSHandleDrawable ?: return
        handle.isLandscape = !isPortrait
        handle.isDockedOnLeft = (sidelinePositionX <= 0)
        handle.isCurvedCornerMode = false
    }

    private fun updateHandleStyle(isNewStyle: Boolean) {
        logger.d("updateHandleStyle: isNewStyle=$isNewStyle")
        if (isNewStyle) {
            if (hyperOSHandleDrawable == null) {
                hyperOSHandleDrawable = HyperOSHandleDrawable(this@SidebarService)
            }
            val currentPercent = sharedPrefs.getInt(
                SidebarApplication.KEY_SIDELINE_LENGTH_PERCENT,
                SidebarApplication.DEFAULT_SIDELINE_LENGTH_PERCENT
            )
            val currentWidthPercent = sharedPrefs.getInt(
                SidebarApplication.KEY_SIDELINE_WIDTH_PERCENT,
                SidebarApplication.DEFAULT_SIDELINE_WIDTH_PERCENT
            )
            hyperOSHandleDrawable?.apply {
                lengthScale = currentPercent / 100f
                widthScale = currentWidthPercent / 100f
                isLandscape = !isPortrait
                isDockedOnLeft = (sidelinePositionX <= 0)
                setLuminance(viewModel.isBackgroundDarkFlow.value, animate = false)
            }
            sideLineView.scaleX = 1.0f
            updateCurvedCornerState()
            sideLineView.background = hyperOSHandleDrawable

            if (adaptiveLuminanceHelper == null) {
                adaptiveLuminanceHelper = AdaptiveLuminanceHelper(this@SidebarService) { isDark, _ ->
                    viewModel.updateBackgroundDark(isDark)
                    hyperOSHandleDrawable?.setLuminance(isDark, animate = true)
                }
            }
            if (isShowingSideline) {
                sideLineView.post {
                    adaptiveLuminanceHelper?.startSampling(sideLineView)
                }
            }
        } else {
            adaptiveLuminanceHelper?.stopSampling()
            sideLineView.background = AppCompatResources.getDrawable(this@SidebarService, R.drawable.ic_line)
            sideLineView.background?.clearColorFilter()
            val currentWidthPercent = sharedPrefs.getInt(
                SidebarApplication.KEY_SIDELINE_WIDTH_PERCENT,
                SidebarApplication.DEFAULT_SIDELINE_WIDTH_PERCENT
            )
            sideLineView.scaleX = currentWidthPercent / 100f
        }
    }

    private val userSwitchObserver = object : UserSwitchObserver() {
        override fun onUserSwitchComplete(newUserId: Int) {
            logger.d("onUserSwitchComplete($userId)")
            if (!showSideline) return
            if (newUserId != userId) {
                removeView(force = true)
            } else {
                showView()
            }
        }
    }

    private val isPortrait: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private val offset: Int
        get() = if (isPortrait) OFFSET_PORTRAIT else OFFSET_LANDSCAPE

    private fun getSidelineWidth(): Int {
        val currentStyle = sharedPrefs.getString(
            SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
            SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
        )
        if (currentStyle == SidebarApplication.STYLE_NEW) {
            val density = resources.displayMetrics.density
            return (64f * density).roundToInt()
        }
        return SIDELINE_WIDTH
    }

    private fun getSidelineMoveWidth(): Int {
        val currentStyle = sharedPrefs.getString(
            SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
            SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
        )
        if (currentStyle == SidebarApplication.STYLE_NEW) {
            val density = resources.displayMetrics.density
            return (80f * density).roundToInt()
        }
        return SIDELINE_MOVE_WIDTH
    }

    companion object {
        private const val TAG = "SidebarService"
        private const val SIDELINE_WIDTH = 100
        //侧边条移动时的宽度
        private const val SIDELINE_MOVE_WIDTH = 200
        private const val SIDELINE_HEIGHT = 200
        //侧边条屏幕边缘偏移量
        private const val OFFSET_PORTRAIT = 20
        private const val OFFSET_LANDSCAPE = 0

        const val SIDELINE = "sideline"
        const val SIDELINE_POSITION_X = "sideline_position_x"
        const val SIDELINE_POSITION_Y_PORTRAIT = "sideline_position_y_portrait"
        const val SIDELINE_POSITION_Y_LANDSCAPE = "sideline_position_y_landscape"
        const val ACTION_GAME_SESSION_STATE = "io.chaldeaprjkt.gamespace.action.GAME_SESSION_STATE"
        const val ACTION_SHOW_GAME_PANEL = "io.chaldeaprjkt.gamespace.action.SHOW_GAME_PANEL"
        const val ACTION_DISMISS_GAME_PANEL = "io.chaldeaprjkt.gamespace.action.DISMISS_GAME_PANEL"
        const val ACTION_DISMISS_SIDEBAR = "com.libremobileos.sidebar.action.DISMISS_SIDEBAR"
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userId = UserHandle.myUserId()
        if (userId != 0) {
            logger.d("not starting for non-system user $userId")
            stopSelf()
            return START_STICKY // this is just to skip the rest of the code
        }

        logger.d("starting service for user $userId")
        viewModel = ServiceViewModel(application)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        iActivityManager = IActivityManager.Stub.asInterface(ServiceManager.getService(Context.ACTIVITY_SERVICE))
        screenWidth = resources.displayMetrics.widthPixels
        screenHeight = resources.displayMetrics.heightPixels
        sharedPrefs = application.applicationContext.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(this)
        iActivityManager.registerUserSwitchObserver(userSwitchObserver, TAG)
        serviceStarted = true

        sidebarView = SidebarView(this@SidebarService, viewModel, object : SidebarView.Callback {
            override fun onRemove() {
                logger.d("sidebar view removed")
                if (isShowingSidebar && showSideline) {
                    animateShowSideline()
                    val currentStyle = sharedPrefs.getString(
                        SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
                        SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
                    )
                    if (currentStyle == SidebarApplication.STYLE_NEW) {
                        sideLineView.post {
                            adaptiveLuminanceHelper?.startSampling(sideLineView)
                        }
                    }
                }
                isShowingSidebar = false
                if (isGameActive) {
                    val intent = Intent(ACTION_DISMISS_GAME_PANEL).apply {
                        setPackage("io.chaldeaprjkt.gamespace")
                    }
                    sendBroadcastAsUser(intent, UserHandle.ALL)
                }
            }
        }, adaptiveLuminanceHelper = { adaptiveLuminanceHelper })
        val isNewStyle = sharedPrefs.getString(
            SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
            SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
        ) == SidebarApplication.STYLE_NEW
        updateHandleStyle(isNewStyle)
        val gameFilter = IntentFilter().apply {
            addAction(ACTION_GAME_SESSION_STATE)
            addAction(ACTION_DISMISS_SIDEBAR)
        }
        registerReceiver(gameSessionReceiver, gameFilter, Context.RECEIVER_EXPORTED)
        showSideline = sharedPrefs.getBoolean(SIDELINE, false)
        logger.d("screenWidth=$screenWidth screenHeight=$screenHeight showSideline=$showSideline")
        if (showSideline) showView()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val newWidth = resources.displayMetrics.widthPixels
        val newHeight = resources.displayMetrics.heightPixels
        if (newWidth == screenWidth && newHeight == screenHeight) {
            return
        }
        screenWidth = newWidth
        screenHeight = newHeight
        logger.d("onConfigChanged: screenWidth=$screenWidth height=$screenHeight" +
                " isShowingSideline=$isShowingSideline isShowingSidebar=$isShowingSidebar")

        if (showSideline) {
            updateSidelinePosition()
            updateCurvedCornerState()
        }
        if (isShowingSidebar) {
            sidebarView.updateSidebarPosition()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!serviceStarted) return
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(this)
        iActivityManager.unregisterUserSwitchObserver(userSwitchObserver)
        runCatching { unregisterReceiver(gameSessionReceiver) }
        removeView(force = true)
        adaptiveLuminanceHelper?.destroy()
        adaptiveLuminanceHelper = null
        hyperOSHandleDrawable = null
        viewModel.destroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            SIDELINE -> {
                showSideline = sharedPrefs.getBoolean(SIDELINE, false)
                if (showSideline) {
                    showView()
                } else {
                    removeView(force = true)
                }
            }
            SidebarApplication.KEY_SIDELINE_LENGTH_PERCENT -> {
                val currentPercent = sharedPrefs.getInt(
                    SidebarApplication.KEY_SIDELINE_LENGTH_PERCENT,
                    SidebarApplication.DEFAULT_SIDELINE_LENGTH_PERCENT
                )
                hyperOSHandleDrawable?.lengthScale = currentPercent / 100f
                if (isShowingSideline) {
                    updateSidelinePosition()
                }
            }
            SidebarApplication.KEY_SIDELINE_WIDTH_PERCENT -> {
                val currentWidthPercent = sharedPrefs.getInt(
                    SidebarApplication.KEY_SIDELINE_WIDTH_PERCENT,
                    SidebarApplication.DEFAULT_SIDELINE_WIDTH_PERCENT
                )
                hyperOSHandleDrawable?.widthScale = currentWidthPercent / 100f
                val currentStyle = sharedPrefs.getString(
                    SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
                    SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
                )
                if (currentStyle != SidebarApplication.STYLE_NEW) {
                    sideLineView.scaleX = currentWidthPercent / 100f
                }
                sideLineView.invalidate()
            }
            SidebarApplication.KEY_SIDEBAR_LENGTH_PERCENT,
            SidebarApplication.KEY_SIDEBAR_WIDTH_DP -> {
                if (isShowingSidebar) {
                    sidebarView.updateSidebarPosition()
                }
            }
            SidebarApplication.KEY_FREEFORM_WINDOW_STYLE -> {
                val isNewStyle = sharedPrefs.getString(
                    SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
                    SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
                ) == SidebarApplication.STYLE_NEW
                updateHandleStyle(isNewStyle)
                if (isShowingSideline) {
                    updateSidelinePosition()
                }
                if (isShowingSidebar) {
                    sidebarView.updateSidebarPosition()
                }
            }
        }
    }

    override fun showSidebar() {
        logger.d("showSidebar")
        adaptiveLuminanceHelper?.stopSampling()
        sidebarView.showView()
        isShowingSidebar = true
        animateHideSideline()

        if (isGameActive) {
            val dockWidthDp = 60
            val intent = Intent(ACTION_SHOW_GAME_PANEL).apply {
                setPackage("io.chaldeaprjkt.gamespace")
                putExtra("dockedOnLeft", sidelinePositionX <= 0)
                putExtra("dockWidthDp", dockWidthDp)
                putExtra("dockTopY", layoutParams.y)
            }
            sendBroadcastAsUser(intent, UserHandle.ALL)
        }
    }

    override fun beginMoveSideline() {
        logger.d("beginMoveSideline")
        layoutParams.apply {
            width = getSidelineMoveWidth()
        }
        updateViewLayout()
    }

    /**
     * @param xChanged x轴变化
     * @param yChanged y轴变化
     * @param positionX 触摸的x轴绝对位置。用来判断是否需要变化侧边条展示位置
     * @param positionY 触摸的y轴绝对位置
     */
    override fun moveSideline(xChanged: Int, yChanged: Int, positionX: Int, positionY: Int) {
        logger.d("moveSideline xChanged=$xChanged yChanged=$yChanged x=$positionX y=$positionY")
        sidelinePositionX = if (positionX > screenWidth / 2) 1 else -1
        layoutParams.apply {
            x = sidelinePositionX * (screenWidth / 2 - offset)
            y = constrainY(layoutParams.y + yChanged)
        }
        updateCurvedCornerState()
        updateViewLayout()
    }

    override fun endMoveSideline() {
        logger.d("endMoveSideline")
        val finalY = constrainY(layoutParams.y)

        layoutParams.apply {
            width = getSidelineWidth()
            y = finalY
        }
        updateCurvedCornerState()
        updateViewLayout()
        setIntSp(SIDELINE_POSITION_X, sidelinePositionX)
        if (isPortrait) {
            setIntSp(SIDELINE_POSITION_Y_PORTRAIT, layoutParams.y)
        } else {
            setIntSp(SIDELINE_POSITION_Y_LANDSCAPE, layoutParams.y)
        }
        sideLineView.post {
            adaptiveLuminanceHelper?.updateSamplingRect(sideLineView)
        }
    }

    private fun constrainY(y: Int): Int {
        val density = resources.displayMetrics.density
        val cornerRadius = (35f * density).roundToInt()
        val sbHeight = if (isPortrait) SystemBarUtils.getStatusBarHeight(this) else cornerRadius
        val navbarHeight = if (isPortrait) {
            resources.getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_height)
        } else {
            cornerRadius
        }
        val currentSidelineHeight = getSidelineHeight()
        val minY = -(screenHeight / 2 - sbHeight - currentSidelineHeight / 2)
        val maxY = screenHeight / 2 - navbarHeight - currentSidelineHeight / 2
        val newY = y.coerceIn(minY, maxY)
        logger.d("constrainY: $y -> $newY (min=$minY, max=$maxY)")
        return newY
    }

    private fun getSidelineHeight(): Int {
        val percent = sharedPrefs.getInt(
            SidebarApplication.KEY_SIDELINE_LENGTH_PERCENT,
            SidebarApplication.DEFAULT_SIDELINE_LENGTH_PERCENT
        )
        return (SIDELINE_HEIGHT * (percent / 100f)).roundToInt()
    }

    /**
     * 启动侧边条
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showView() {
        if (isShowingSideline) return

        logger.d("showView")

        val currentSidelineHeight = getSidelineHeight()
        val currentSidelineWidth = getSidelineWidth()
        layoutParams.apply {
            type = LayoutParams.TYPE_APPLICATION_OVERLAY
            width = currentSidelineWidth
            height = currentSidelineHeight
            flags = LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    LayoutParams.FLAG_LAYOUT_NO_LIMITS
            privateFlags = LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS or
                    LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                    LayoutParams.PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
        }

        sideLineView.setSystemGestureExclusionRects(
            listOf(Rect(0, 0, currentSidelineWidth, currentSidelineHeight))
        )

        updateSidelinePosition()

        handler.post {
            runCatching {
                windowManager.addView(sideLineView, layoutParams)
                viewModel.registerCallbacks()
                isShowingSideline = true
                val currentStyle = sharedPrefs.getString(
                    SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
                    SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
                )
                if (currentStyle == SidebarApplication.STYLE_NEW) {
                    sideLineView.post {
                        adaptiveLuminanceHelper?.startSampling(sideLineView)
                    }
                }
            }.onFailure { e ->
                logger.e("failed to add sideline view: ", e)
            }
        }
    }

    private fun updateSidelinePosition() {
        sidelinePositionX = sharedPrefs.getInt(SIDELINE_POSITION_X, 1)
        sidelinePositionY =
            if (isPortrait)
                sharedPrefs.getInt(SIDELINE_POSITION_Y_PORTRAIT, -screenHeight / 6)
            else
                sharedPrefs.getInt(SIDELINE_POSITION_Y_LANDSCAPE, -screenHeight / 6)

        val currentSidelineHeight = getSidelineHeight()
        val currentSidelineWidth = getSidelineWidth()
        layoutParams.apply {
            width = currentSidelineWidth
            height = currentSidelineHeight
            x = sidelinePositionX * (screenWidth / 2 - offset)
            y = constrainY(sidelinePositionY)
            logger.d("updateSidelinePosition: ($x,$y)")
            updateCurvedCornerState()

            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            flags = (flags and LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv()) or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }

        sideLineView.setSystemGestureExclusionRects(
            listOf(Rect(0, 0, currentSidelineWidth, currentSidelineHeight))
        )

        if (isShowingSideline) {
            updateViewLayout()
        }
    }

    private fun updateViewLayout() {
        handler.post {
            runCatching {
                sideLineView.setSystemGestureExclusionRects(
                    listOf(Rect(0, 0, layoutParams.width, layoutParams.height))
                )
                windowManager.updateViewLayout(sideLineView, layoutParams)
                sideLineView.post {
                    adaptiveLuminanceHelper?.updateSamplingRect(sideLineView)
                }
            }.onFailure { e ->
                logger.e("failed to updateViewLayout: ", e)
            }
        }
    }

    private fun removeView(force: Boolean = false) {
        if (!isShowingSideline && !force) return

        logger.d("removeView")
        adaptiveLuminanceHelper?.stopSampling()
        viewModel.unregisterCallbacks()

        handler.post {
            runCatching {
                windowManager.removeViewImmediate(sideLineView)
            }.onFailure { e ->
                logger.e("failed to remove sideline view: $e")
            }
        }

        sidebarView.removeView(force)
        isShowingSideline = false
    }

    private fun animateHideSideline() {
        logger.d("animateHideSideline")
        sideLineView.animate().translationX(sidelinePositionX * 1.0f * getSidelineWidth()).setDuration(300).start()
    }

    private fun animateShowSideline() {
        logger.d("animateShowSideline")
        sideLineView.animate().translationX(0f).setDuration(300).start()
    }

    private fun setIntSp(name: String, value: Int) {
        sharedPrefs.edit().apply {
            putInt(name, value)
            apply()
        }
    }
}
