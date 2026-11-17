import com.watchdl.core.ReaderPaging

fun main() {
    var checks = 0
    fun eq(actual: Any, expected: Any) {
        check(actual == expected) { "expected=$expected actual=$actual" }
        checks++
    }
    eq(ReaderPaging.step(432, 48), 384)
    eq(ReaderPaging.step(20, 48), 48)
    eq(ReaderPaging.step(0, 0), 1)
    // 剩余 200 像素时，末页不能被吸附成仅移动 3 像素。
    eq(ReaderPaging.target(803, true, 384, 1003) { 806 }, 1003)
    eq(ReaderPaging.target(203, false, 384, 1003) { 199 }, 0)
    eq(ReaderPaging.target(0, true, 384, 1003) { 360 }, 360)
    eq(ReaderPaging.target(400, true, 384, 1003) { 390 }, 784)
    eq(ReaderPaging.target(400, false, 384, 1003) { 410 }, 16)
    eq(ReaderPaging.target(0, true, 384, 0) { error("boundary must not snap") }, 0)
    eq(ReaderPaging.target(Int.MAX_VALUE - 10, true, 100, Int.MAX_VALUE) { 0 }, Int.MAX_VALUE)
    // 旧拼接块和新章都包含相同正文，标题高度变化不改变行内位置。
    val anchor = ReaderPaging.anchor(150, 2347, 2000, 320)
    eq(anchor.chars, 150)
    eq(anchor.delta, 27)
    eq(ReaderPaging.restore(anchor, 100, 320, 32), 415)
    // 视口尚在下一章标题处，保留正文前的负距离。
    val title = ReaderPaging.anchor(0, 1960, 2000, 0)
    eq(title.delta, -40)
    eq(ReaderPaging.restore(title, 100, 0, 32), 28)
    // 按一次下一屏后交接，不用整个 800 字拼接块长度充当锚点。
    val nextPage = ReaderPaging.anchor(300, 2384, 2000, 336)
    eq(ReaderPaging.restore(nextPage, 100, 336, 32), 452)
    // 相同排版下换窗前后的位移一致，包括三档边距和短章。
    for (padding in listOf(16, 32, 48)) {
        for (oldScroll in listOf(1850, 2000, 2300)) {
            for (advance in listOf(12, 120, 384)) {
                val desired = oldScroll + padding + advance
                val lineTop = ((desired - 2000).coerceAtLeast(0) / 48) * 48
                val a = ReaderPaging.anchor(0, desired, 2000, lineTop)
                val restored = ReaderPaging.restore(a, 100, lineTop, padding)
                eq(restored - oldScroll, advance - 1900)
            }
        }
    }
    // ── 连续滚动：章号与存档偏移同一口径 ────────────────────
    // 三章同屏（本章 10、追加 11、12），块顶按排版后的像素给。
    val bounds = listOf(10 to 0, 11 to 2000, 12 to 4200)
    eq(ReaderPaging.cursorAt(bounds, 1500, 10), 10)
    eq(ReaderPaging.cursorAt(bounds, 2000, 10), 11)
    eq(ReaderPaging.cursorAt(bounds, 2600, 10), 11)
    eq(ReaderPaging.cursorAt(bounds, 4300, 10), 12)
    eq(ReaderPaging.cursorAt(emptyList(), 999, 7), 7)
    // 视口还在首块之上：还算首章，不能倒回 fallback 之外。
    eq(ReaderPaging.cursorAt(bounds, -5, 10), 10)
    // 滚到第 12 章 100 px，存的必须是 100，不是整屏 4300：
    // 存整屏会被 mainLimit 限幅成「读完」，下次打开落到后一章。
    eq(ReaderPaging.savedOffset(bounds, 12, 4300, 0, 2000), 100)
    eq(ReaderPaging.savedOffset(bounds, 11, 2600, 0, 2000), 600)
    // 超出该章块高就限幅到块高（11 章的块高 = 4200 − 2000 = 2200）。
    eq(ReaderPaging.savedOffset(bounds, 11, 4400, 0, 2000), 2200)
    eq(ReaderPaging.savedOffset(bounds, 11, 4100, 0, 2000), 2100)
    eq(ReaderPaging.chapSpan(bounds, 12, 2000), 2000)
    eq(ReaderPaging.chapSpan(bounds, 99, 0), 1)
    // paddingTop 计入视口顶。
    eq(ReaderPaging.savedOffset(bounds, 11, 1992, 8, 2000), 0)
    // 还没追加：退回整屏值并按 limit 限幅，与旧行为一致。
    eq(ReaderPaging.savedOffset(emptyList(), 3, 700, 16, 400), 400)
    eq(ReaderPaging.savedOffset(emptyList(), 3, 120, 16, 400), 120)
    println("ReaderPagingTest: $checks checks passed")
}
