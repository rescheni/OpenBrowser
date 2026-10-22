package com.openbrowser.mobile.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * 轻量动效工具：按下缩放、列表/区块错峰入场。
 *
 * 不依赖动画库，全部使用 ViewPropertyAnimator，保证在低端机上也不会掉帧。
 */
object Motion {

    private const val DURATION_DOWN = 110L
    private const val DURATION_UP = 150L

    /** 手指按下时轻微缩小，松开回弹，点击事件不受影响。 */
    @SuppressLint("ClickableViewAccessibility")
    fun pressScale(view: View, pressedScale: Float = 0.97f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(pressedScale)
                    .scaleY(pressedScale)
                    .setDuration(DURATION_DOWN)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(DURATION_UP)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            false
        }
    }

    /** 子 View 依次淡入并上浮，用于页面头部、表单区块。 */
    fun staggerIn(
        container: ViewGroup,
        startDelay: Long = 40L,
        step: Long = 45L,
        translateDp: Float = 10f,
    ) {
        val offset = translateDp * container.resources.displayMetrics.density
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            child.alpha = 0f
            child.translationY = offset
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelay + index * step)
                .setDuration(280L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}
