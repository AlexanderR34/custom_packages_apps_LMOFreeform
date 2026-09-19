package com.libremobileos.freeform.server.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator

object FreeformAnimation {
    private val ELEGANT_EXPAND_INTERPOLATOR = PathInterpolator(0.18f, 1.04f, 0.22f, 1.0f)

    fun playEnterAnimation(window: FreeformWindow, sidebarPositionX: Int) {
        val density = window.context.resources.displayMetrics.density
        val targetView = window.freeformLayout

        targetView.alpha = 0f

        targetView.post {
            val width = if (targetView.width > 0) targetView.width.toFloat() else window.freeformConfig.width.toFloat()
            val height = if (targetView.height > 0) targetView.height.toFloat() else window.freeformConfig.height.toFloat()

            targetView.pivotX = if (sidebarPositionX >= 0) width else 0f
            targetView.pivotY = height / 2f

            val startTransX = if (sidebarPositionX >= 0) 40f * density else -40f * density

            targetView.alpha = 0f
            targetView.scaleX = 0.08f
            targetView.scaleY = 0.08f
            targetView.translationX = startTransX

            targetView.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .translationX(0f)
                .setDuration(440)
                .setInterpolator(ELEGANT_EXPAND_INTERPOLATOR)
                .start()
        }
    }

    fun moveInScreenAnimator(start: Int, end: Int, dur: Long, moveX: Boolean, window: FreeformWindow) {
        AnimatorSet().apply {
            play(
                ValueAnimator.ofInt(start, end).apply {
                    addUpdateListener {
                        window.windowManager.updateViewLayout(
                            window.freeformLayout,
                            window.windowParams.apply {
                                if (moveX) x = it.animatedValue as Int
                                else y = it.animatedValue as Int
                            }
                        )
                    }
                }
            )
            duration = dur
            start()
        }
    }

    fun toFullScreen(window: FreeformWindow, dur: Long, listener: Animator.AnimatorListener) {
        AnimatorSet().apply {
            play(
                ValueAnimator.ofInt(window.windowParams.x, 0).apply {
                    addUpdateListener {
                        window.windowManager.updateViewLayout(
                            window.freeformLayout,
                            window.windowParams.apply {
                                x = it.animatedValue as Int
                            }
                        )
                    }
                }
            )
            duration = dur
            start()
        }
        AnimatorSet().apply {
            play(
                ValueAnimator.ofInt(window.windowParams.y, 0).apply {
                    addUpdateListener {
                        window.windowManager.updateViewLayout(
                            window.freeformLayout,
                            window.windowParams.apply {
                                y = it.animatedValue as Int
                            }
                        )
                    }
                }
            )
            duration = dur
            start()
        }
        AnimatorSet().apply {
            play(
                ValueAnimator.ofInt(window.freeformConfig.width, window.defaultDisplayWidth).apply {
                    addUpdateListener {
                        window.freeformRootView.layoutParams = window.freeformRootView.layoutParams.apply {
                            width = it.animatedValue as Int
                        }
                    }
                }
            )
            duration = dur
            start()
        }
        AnimatorSet().apply {
            play(
                ValueAnimator.ofInt(window.freeformConfig.height, window.defaultDisplayHeight).apply {
                    addUpdateListener {
                        window.freeformRootView.layoutParams = window.freeformRootView.layoutParams.apply {
                            height = it.animatedValue as Int
                        }
                    }
                }
            )
            duration = dur
            start()
        }.addListener(listener)
    }
}
