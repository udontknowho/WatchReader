package com.watchdl.core

/**
 * 「换一批源」的起点推进。
 *
 * 原先是 `MainActivity.nextSourceBatch` 里的一段算术，`RotationTest` 复刻一份再断言副本 ——
 * 真实现改了测试照样绿（N12）。挪到这里，测试直接调真实现。
 */
object SourceRotation {
    /**
     * 下一批的起点偏移；null 表示转满一圈。
     *
     * 用余数而不是 `offset + step`：源总数不是本轮用量的整数倍时
     *（如 42 条源 × 15 一轮），加法会循环取同一批（0→15→30→3→18→33→6…重复），
     * 有些源永远轮不到。
     */
    fun next(offset: Int, step: Int, total: Int): Int? {
        if (total <= 0) return null
        val s = step.coerceAtLeast(1)
        val n = (offset + s) % total
        return if (n == 0) null else n
    }
}
