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

    companion object {
        private const val TAG = "AdaptiveLuminanceHelper"
        private const val THRESHOLD_SWITCH_TO_LIGHT = 0.52f
        private const val THRESHOLD_SWITCH_TO_DARK = 0.48f
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
        val location = IntArray(2)
        sampledView.getLocationOnScreen(location)
        val left = location[0].coerceAtLeast(0)
        val top = location[1].coerceAtLeast(0)
        val right = (location[0] + sampledView.width).coerceAtLeast(left + 1)
        val bottom = (location[1] + sampledView.height).coerceAtLeast(top + 1)

        val newRect = Rect(left, top, right, bottom)
        val effectiveStopLayer = stopLayer ?: sampledView.viewRootImpl?.surfaceControl

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
            CompositionSamplingListener.register(
                listener,
                Display.DEFAULT_DISPLAY,
                if (sc != null && sc.isValid) sc else null,
                currentSamplingRect
            )
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
