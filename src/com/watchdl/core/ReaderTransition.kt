package com.watchdl.core

/**
 * 跨章入场的决策。不依赖 Android，便于 JVM 自检。
 *
 * 只决定「哪一次重建该挂动画、朝哪个方向、走多远」，位移本身由调用方
 * 落到 View 的 translationY 上 —— 不抓位图，也不触发重排。
 *
 * 旧做法（1.6.1.6 及以前）是换章前 `frame.draw(Canvas)` 拓一张整屏位图，
 * 新页就位后整层向上滑出。拓帧与整章排版挤在同一帧，掉帧是「卡」，
 * 位图上滑是「跳」。删掉之后手感也没了，这里换成对正文本身做位移。
 */
object ReaderTransition {
    /** 不动画：首次进阅读、改字号行距、切主题等重建，都不该有入场感。 */
    const val NONE = 0

    /** 顺读：新章自下而上顶进来。 */
    const val FORWARD = 1

    /** 回读：新章自上而下落下来。 */
    const val BACKWARD = -1

    /** 动画时长。比 legado 手机端基准 360 ms 短：表屏一屏字数少，拖长了像卡。 */
    const val DURATION_MS = 190L

    /** 入场位移上限（dp）。视口比例给到 30 % 时，248 dp 屏幕约 74 dp。 */
    const val MAX_LIFT_DP = 72

    /**
     * 方向判定。
     *
     * [prevIndex] 为 null 表示上一屏不是同一本书的阅读页（首次进入、从目录跳章、
     * 从书架进来），这些都不动画：没有「从哪儿读过来」的空间关系，硬加位移只是闪。
     * 同一章重建（改设置、切主题）同样返回 [NONE]。
     */
    fun dirFor(prevIndex: Int?, index: Int): Int = when {
        prevIndex == null -> NONE
        prevIndex == index -> NONE
        index > prevIndex -> FORWARD
        else -> BACKWARD
    }

    /**
     * 入场位移像素：视口的 30 %，上限 [maxPx]。
     *
     * 视口为 0（尚未排版）时退回 [maxPx]，别让动画退化成「原地不动」。
     * 负数与超限一律夹住：传错值最多是幅度不对，不该出现反向位移。
     */
    fun liftPx(viewportH: Int, maxPx: Int): Int {
        val cap = maxPx.coerceAtLeast(0)
        if (viewportH <= 0) return cap
        return (viewportH.toLong() * 3 / 10).coerceIn(0L, cap.toLong()).toInt()
    }
}
