package com.watchdl.core

/** 返回页面的会话状态。窗口和像素偏移必须一起恢复，不能借用另一屏的全局窗口。 */
data class ReturnPoint(
    val screen: Screen,
    val scrollY: Int = 0,
    val hitsWindow: Int = 0,
    val tocWindow: Int = 0,
)

/** 同屏重复记忆更新位置，不增加返回层级。最多保留八层。 */
fun ArrayDeque<ReturnPoint>.remember(point: ReturnPoint) {
    if (lastOrNull()?.screen == point.screen) removeLast()
    addLast(point)
    while (size > 8) removeFirst()
}

/** 内容尚未加载完时先夹到可滚范围，后续布局继续恢复剩余偏移。 */
fun restoredScrollY(saved: Int, contentHeight: Int, viewportHeight: Int): Int =
    saved.coerceAtLeast(0).coerceAtMost((contentHeight - viewportHeight).coerceAtLeast(0))
