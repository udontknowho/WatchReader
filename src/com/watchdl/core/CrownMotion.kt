package com.watchdl.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 表冠还没走完的位移。只由 UI 线程调用，不依赖 Android。
 *
 * 旧实现存的是「起点 → 目标」两个绝对坐标，每帧 `scrollTo` 一个绝对值。
 * 阅读器的内容总高随摘块、前插而变：绝对值一旦落到别的内容上就是一次跳，
 * 内容变矮时还会被 ScrollView 静默夹到新上限 —— 表冠因此会「跳回上章末尾」。
 *
 * 现在只存「还剩多少像素没走」，每帧按时间比例消费，由调用方 `scrollBy`。
 * 增量与内容总高无关，内容增删只影响这一帧能走多远，不会把读者扔到别处。
 */
class CrownMotion(private val durationMs: Int) {
    var running: Boolean = false
        private set

    /** 尚未消费的像素：正数向下，负数向上。 */
    var pending: Float = 0f
        private set

    fun cancel() {
        running = false
        pending = 0f
    }

    /**
     * 累加一个事件给的位移。
     *
     * 同向快转把位移攒起来，反转直接抵消 —— 与旧实现一致，手感不变。
     * 这里不截断单次余量：上限该由「一帧最多走多远」管（见 [step]），
     * 在累加处截断会让快转丢位移。
     */
    fun add(delta: Float) {
        if (!delta.isFinite() || delta == 0f) return
        pending += delta
        running = true
    }

    /**
     * 消费一帧，返回本帧应滚动的像素（可能为 0，也可能为负）。
     *
     * [dtMs] 是距上一帧的毫秒数；[durationMs] 决定「走完当前余量要多久」。
     * 每帧至少走 1 像素，否则小于 1 的余量按比例乘完永远是 0，`running` 会挂住不退。
     */
    fun step(dtMs: Long): Int {
        val left = pending
        if (left == 0f) {
            running = false
            return 0
        }
        if (durationMs <= 0) {
            pending = 0f
            running = false
            return left.roundToInt()
        }
        val sign = if (left > 0f) 1 else -1
        val mag = abs(left)
        // 不到一像素的余量：这一帧走完，别让 roundToInt 把它压成 0。
        if (mag <= 1f) {
            pending = 0f
            running = false
            return sign
        }
        val ratio = (dtMs.coerceIn(1L, 64L).toFloat() / durationMs).coerceIn(0.1f, 1f)
        val take = (mag * ratio).coerceAtLeast(1f).coerceAtMost(mag)
        val moved = sign * take.roundToInt().coerceAtLeast(1)
        pending -= moved
        // 浮点残量收尾：剩不到半像素就当走完，否则会一直空转。
        if (abs(pending) < 0.5f) {
            pending = 0f
            running = false
        }
        return moved
    }

    companion object {
        /** 旋转编码器使用 SCROLL；旧设备和鼠标仍使用 VSCROLL，避免双轴重复累计。 */
        fun delta(rotary: Boolean, vertical: Float, scroll: Float, step: Float): Float {
            if (!step.isFinite() || step <= 0f) return 0f
            val axis = if (rotary && scroll != 0f && scroll.isFinite()) scroll else vertical
            val value = -axis * step.coerceAtMost(10f)
            return if (value.isFinite()) value else 0f
        }
    }
}
