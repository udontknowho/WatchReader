package com.watchdl.core

import kotlin.math.roundToInt

/** 表冠的累计目标和插值状态。只由 UI 线程调用，不依赖 Android。 */
class CrownMotion(private val durationMs: Int) {
    var running: Boolean = false
        private set
    var target: Float = 0f
        private set
    private var start = 0f
    private var startedAt = 0L

    fun cancel() { running = false }

    /** 每个事件只更新状态。同向快转保留尚未追平的位移，反转抵消累计目标。 */
    fun add(current: Int, maximum: Int, delta: Float, now: Long) {
        if (!delta.isFinite() || delta == 0f) return
        val limit = maximum.coerceAtLeast(0).toFloat()
        val pendingTarget = target
        val position = if (running) frame(maximum, now).toFloat() else current.toFloat()
        start = position.coerceIn(0f, limit)
        target = ((if (running) pendingTarget else start) + delta).coerceIn(0f, limit)
        startedAt = now
        running = true
    }

    /** 最后一帧按整数像素结束，不能用 scrollY == 浮点目标判断结束。 */
    fun frame(maximum: Int, now: Long): Int {
        val limit = maximum.coerceAtLeast(0)
        target = target.coerceIn(0f, limit.toFloat())
        start = start.coerceIn(0f, limit.toFloat())
        val elapsed = (now - startedAt).coerceAtLeast(0L)
        val fraction = if (durationMs <= 0) 1f else
            (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
        val eased = 1f - (1f - fraction) * (1f - fraction)
        val position = (start + (target - start) * eased).roundToInt().coerceIn(0, limit)
        if (fraction >= 1f || start == target) running = false
        return position
    }

    companion object {
        /** 旋转编码器使用 SCROLL；旧设备和鼠标仍使用 VSCROLL，避免双轴重复累计。 */
        fun delta(rotary: Boolean, vertical: Float, scroll: Float, step: Float): Float {
            val axis = if (rotary && scroll != 0f && scroll.isFinite()) scroll else vertical
            val value = -axis * step
            return if (value.isFinite()) value else 0f
        }
    }
}
