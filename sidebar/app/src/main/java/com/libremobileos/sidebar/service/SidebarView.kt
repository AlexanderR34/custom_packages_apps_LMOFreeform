package com.libremobileos.sidebar.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.UserHandle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.ui.theme.SidebarTheme
import com.libremobileos.sidebar.utils.Logger
import kotlin.math.roundToInt

/**
 * @author KindBrave
 * @since 2023/9/26
 */
class SidebarView(
    private val context: Context,
    private val viewModel: ServiceViewModel,
    private val callback: Callback,
    private val adaptiveLuminanceHelper: (() -> AdaptiveLuminanceHelper?)? = null
) : SavedStateRegistryOwner {

    private var lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var composeView: View
    private var sidebarPositionX = 0
    private var sidebarPositionY = 0
    private var isShowing = false
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layoutParams = LayoutParams()
    private val logger = Logger(TAG)
    private val handler = Handler()

    private val sharedPrefs by lazy {
        context.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
    }

    companion object {
        private const val OFFSET_X = 90
        private const val PACKAGE = "com.libremobileos.freeform"
        private const val ACTION = "com.libremobileos.freeform.START_FREEFORM"
        private const val TAG = "SidebarView"
    }

    init {
        savedStateRegistryController.performRestore(null)
    }

    private fun launchAppInFreeform(appInfo: AppInfo) {
        val intent = Intent(ACTION).apply {
            setPackage(PACKAGE)
            putExtra("packageName", appInfo.packageName)
            putExtra("activityName", appInfo.activityName)
            putExtra("userId", appInfo.userId)
        }
        context.sendBroadcastAsUser(intent, UserHandle(UserHandle.USER_CURRENT))
        removeView()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showView() {
        if (isShowing) return

        if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            lifecycleRegistry = LifecycleRegistry(this)
        }

        initComposeView()

        layoutParams.apply {
            type = LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_HARDWARE_ACCELERATED
            privateFlags = LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                    LayoutParams.PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }

        updateSidebarPosition()
        composeView.translationX = sidebarPositionX * 1.0f * 200
        composeView.setOnTouchListener { view, event ->
            logger.d("composeView: $event")
            if (event.action == MotionEvent.ACTION_UP) {
                removeView()
                true
            }
            false
        }

        handler.post {
            runCatching {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                windowManager.addView(composeView, layoutParams)
                composeView.animate().translationX(0f).setDuration(300).start()
                isShowing = true
                val currentStyle = sharedPrefs.getString(
                    SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
                    SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
                )
                if (currentStyle == SidebarApplication.STYLE_NEW) {
                    composeView.post {
                        adaptiveLuminanceHelper?.invoke()?.startSampling(composeView)
                    }
                }
            }.onFailure {
                logger.e("failed to add sidebar view: ", it)
            }
        }
    }

    fun removeView(force: Boolean = false) {
        if (!isShowing && !force) return

        logger.d("removeView")
        handler.post {
            runCatching {
                val currentStyle = sharedPrefs.getString(
                    SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
                    SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
                )
                if (currentStyle == SidebarApplication.STYLE_NEW) {
                    adaptiveLuminanceHelper?.invoke()?.stopSampling()
                }
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                windowManager.removeViewImmediate(composeView)
                callback.onRemove()
                isShowing = false
            }.onFailure {
                logger.e("failed to remove sidebar view: $it")
            }
        }
    }

    fun updateSidebarPosition() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val baseSidebarHeight = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            screenHeight / 3
        } else {
            (screenHeight * 0.8f).roundToInt()
        }
        val currentStyle = sharedPrefs.getString(
            SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
            SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
        )
        val lengthPercent = sharedPrefs.getInt(
            SidebarApplication.KEY_SIDEBAR_LENGTH_PERCENT,
            SidebarApplication.DEFAULT_SIDEBAR_LENGTH_PERCENT
        )
        val sidebarHeight = if (currentStyle == SidebarApplication.STYLE_NEW) {
            val base = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                (screenHeight * 0.46f).roundToInt()
            } else {
                (screenHeight * 0.82f).roundToInt()
            }
            (base * (lengthPercent / 100f)).roundToInt()
        } else {
            val baseSidebarHeight = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                screenHeight / 3
            } else {
                (screenHeight * 0.8f).roundToInt()
            }
            (baseSidebarHeight * (lengthPercent / 100f)).roundToInt()
        }

        sidebarPositionX = sharedPrefs.getInt(SidebarService.SIDELINE_POSITION_X, 1)
        sidebarPositionY = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            sharedPrefs.getInt(SidebarService.SIDELINE_POSITION_Y_PORTRAIT, -screenHeight / 6)
        } else {
            0
        }

        val maxOffsetY = (screenHeight - sidebarHeight) / 2 - 32
        val clampedY = sidebarPositionY.coerceIn(-maxOffsetY, maxOffsetY)

        val offsetX = if (currentStyle == SidebarApplication.STYLE_NEW) {
            val density = context.resources.displayMetrics.density
            val dockWidthPx = (60 * density).roundToInt()
            val edgeMarginPx = (14 * density).roundToInt()
            dockWidthPx / 2 + edgeMarginPx
        } else {
            OFFSET_X
        }

        layoutParams.apply {
            width = LayoutParams.WRAP_CONTENT
            height = sidebarHeight
            x = sidebarPositionX * (screenWidth / 2 - offsetX)
            y = clampedY
        }

        logger.d("updateSidebarPosition: posX=$sidebarPositionX posY=$sidebarPositionY" +
                " lp.x=${layoutParams.x} lp.y=${layoutParams.y} height=${layoutParams.height}")

        if (isShowing) {
            handler.post {
                runCatching {
                    windowManager.updateViewLayout(composeView, layoutParams)
                    val currentStyle = sharedPrefs.getString(
                        SidebarApplication.KEY_FREEFORM_WINDOW_STYLE,
                        SidebarApplication.DEFAULT_FREEFORM_WINDOW_STYLE
                    )
                    if (currentStyle == SidebarApplication.STYLE_NEW) {
                        composeView.post {
                            adaptiveLuminanceHelper?.invoke()?.updateSamplingRect(composeView)
                        }
                    }
                }.onFailure { e ->
                    logger.e("failed to updateViewLayout: ", e)
                }
            }
        }
    }

    private fun initComposeView() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@SidebarView)
            setViewTreeSavedStateRegistryOwner(this@SidebarView)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SidebarTheme {
                    val windowStyle by viewModel.windowStyleFlow.collectAsState()
                    if (windowStyle == SidebarApplication.STYLE_NEW) {
                        CustomSidebarComposeView(
                            viewModel = viewModel,
                            launchApp = { launchAppInFreeform(it) },
                            closeSidebar = { removeView() },
                            modifier = Modifier
                                .fillMaxHeight()
                                .wrapContentWidth()
                        )
                    } else {
                        SidebarComposeView(
                            viewModel = viewModel,
                            launchApp = { launchAppInFreeform(it) },
                            closeSidebar = { removeView() },
                            modifier = Modifier
                                .fillMaxHeight()
                                .wrapContentWidth()
                        )
                    }
                }
            }
        }
    }

    interface Callback {
        fun onRemove()
    }
}
