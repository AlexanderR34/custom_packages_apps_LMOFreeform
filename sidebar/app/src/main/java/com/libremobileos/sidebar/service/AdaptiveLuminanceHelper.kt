package com.libremobileos.sidebar.service

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.CompositionSamplingListener
import android.view.Display
import android.view.SurfaceControl
import android.view.View
import com.libremobileos.sidebar.utils.Logger
import java.util.concurrent.Executor

class AdaptiveLuminanceHelper(
    private val context: Context,
    private val onLuminanceChanged: (isDark: Boolean, luma: Float) -> Unit
) {
    private val logger = Logger(TAG)
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }

    private var samplingListener: CompositionSamplingListener? = null
    private var isSamplingRegistered = false
    private val currentSamplingRect = Rect()
    private var sampledStopLayer: SurfaceControl? = null

    private var isDarkRegion = true
    private var lastLuma = -1.0f
    private var attachedView: View? = null
    private val layoutChangeListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        updateSamplingRect(v)
    }

    companion object {
        private const val TAG = "AdaptiveLuminanceHelper"
        // Luminance thresholds for adaptive contrast:
        // A background is considered LIGHT (switch handle to TRANSLUCENT GRAY)
        // only when luminance is distinctly bright/white (>= 0.65).
        // Over dark, gray, or medium backgrounds (luminance <= 0.55),
        // the background is considered DARK/GRAY (switch handle to TRANSLUCENT WHITE)
        // so that the bar NEVER merges with gray or dark backgrounds.
        private const val THRESHOLD_SWITCH_TO_LIGHT = 0.65f
        private const val THRESHOLD_SWITCH_TO_DARK = 0.55f
    }

    init {
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        isDarkRegion = isNight
        onLuminanceChanged(isDarkRegion, if (isNight) 0.1f else 0.9f)
    }

    private val wallpaperColorsListener = WallpaperManager.OnColorsChangedListener { colors, _ ->
        if (!isSamplingRegistered && colors != null) {
            val isDark = (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0
            if (isDark != isDarkRegion) {
                isDarkRegion = isDark
                onLuminanceChanged(isDarkRegion, if (isDark) 0.2f else 0.8f)
            }
        }
    }

    fun startSampling(sampledView: View? = null, stopLayer: SurfaceControl? = null) {
        logger.d("startSampling")
        if (samplingListener == null) {
            runCatching {
                samplingListener = object : CompositionSamplingListener(mainExecutor) {
                    override fun onSampleCollected(medianLuma: Float) {
                        handleSampleCollected(medianLuma)
                    }
                }
            }.onFailure { e ->
                logger.e("Failed to create CompositionSamplingListener: ", e)
            }
        }

        if (sampledView != null && attachedView != sampledView) {
            attachedView?.removeOnLayoutChangeListener(layoutChangeListener)
            attachedView = sampledView
            sampledView.addOnLayoutChangeListener(layoutChangeListener)
        }

        runCatching {
            val wm = context.getSystemService(Context.WALLPAPER_SERVICE) as? WallpaperManager
            wm?.addOnColorsChangedListener(wallpaperColorsListener, handler)
            val initialColors = wm?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (initialColors != null) {
                val isDark = (initialColors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0
                if (isDark != isDarkRegion) {
                    isDarkRegion = isDark
                    onLuminanceChanged(isDarkRegion, if (isDarkRegion) 0.2f else 0.8f)
                }
            }
        }.onFailure { e ->
            logger.e("Failed to register wallpaper listener: ", e)
        }

        sampledView?.let { updateSamplingRect(it, stopLayer) }
    }

    fun updateSamplingRect(sampledView: View, stopLayer: SurfaceControl? = null) {
        if (!sampledView.isAttachedToWindow) return
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val location = IntArray(2)
        sampledView.getLocationOnScreen(location)
        val left = location[0].coerceIn(0, screenW - 2)
        val top = location[1].coerceIn(0, screenH - 2)
        val right = (location[0] + sampledView.width).coerceIn(left + 1, screenW)
        val bottom = (location[1] + sampledView.height).coerceIn(top + 1, screenH)

        val newRect = Rect(left, top, right, bottom)
        val effectiveStopLayer = stopLayer ?: runCatching {
            sampledView.viewRootImpl?.surfaceControl
        }.getOrNull()

        if (newRect == currentSamplingRect && effectiveStopLayer == sampledStopLayer && isSamplingRegistered) {
            return
        }

        currentSamplingRect.set(newRect)
        sampledStopLayer = effectiveStopLayer
        registerSampling()
    }

    private fun registerSampling() {
        val listener = samplingListener ?: return
        if (currentSamplingRect.isEmpty) return

        runCatching {
            if (isSamplingRegistered) {
                CompositionSamplingListener.unregister(listener)
                isSamplingRegistered = false
            }
            val sc = sampledStopLayer
            val wrappedSc = if (sc != null && sc.isValid) {
                runCatching { SurfaceControl(sc, "AdaptiveLuminanceHelper") }.getOrNull() ?: sc
            } else null
            try {
                CompositionSamplingListener.register(
                    listener,
                    Display.DEFAULT_DISPLAY,
                    wrappedSc,
                    currentSamplingRect
                )
            } catch (e: Exception) {
                logger.w("Failed with stopLayer, retrying without stopLayer: $e")
                CompositionSamplingListener.register(
                    listener,
                    Display.DEFAULT_DISPLAY,
                    null,
                    currentSamplingRect
                )
            }
            isSamplingRegistered = true
            logger.d("CompositionSamplingListener registered for rect=$currentSamplingRect")
        }.onFailure { e ->
            logger.e("Failed to register CompositionSamplingListener: ", e)
        }
    }

    private fun handleSampleCollected(medianLuma: Float) {
        if (medianLuma < 0f) return

        val newIsDark: Boolean = if (isDarkRegion) {
            medianLuma < THRESHOLD_SWITCH_TO_LIGHT
        } else {
            medianLuma <= THRESHOLD_SWITCH_TO_DARK
        }

        if (newIsDark != isDarkRegion || lastLuma < 0f) {
            logger.d("Luminance sample: luma=$medianLuma isDark=$newIsDark (was $isDarkRegion)")
            isDarkRegion = newIsDark
            lastLuma = medianLuma
            onLuminanceChanged(isDarkRegion, medianLuma)
        }
    }

    fun stopSampling() {
        logger.d("stopSampling")
        attachedView?.removeOnLayoutChangeListener(layoutChangeListener)
        attachedView = null
        if (isSamplingRegistered && samplingListener != null) {
            runCatching {
                CompositionSamplingListener.unregister(samplingListener)
            }.onFailure { e ->
                logger.e("Failed to unregister CompositionSamplingListener: ", e)
            }
            isSamplingRegistered = false
        }
        runCatching {
            val wm = context.getSystemService(Context.WALLPAPER_SERVICE) as? WallpaperManager
            wm?.removeOnColorsChangedListener(wallpaperColorsListener)
        }.onFailure { e ->
            logger.e("Failed to unregister wallpaper listener: ", e)
        }
    }

    fun destroy() {
        stopSampling()
        runCatching {
            samplingListener?.destroy()
        }
        samplingListener = null
    }
}
