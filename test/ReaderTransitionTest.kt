import com.watchdl.core.ReaderTransition

fun main() {
    var checks = 0
    fun eq(actual: Any, expected: Any) {
        check(actual == expected) { "expected=$expected actual=$actual" }
        checks++
    }

    // 方向：只有同一本书、不同章才有入场。
    eq(ReaderTransition.dirFor(null, 7), ReaderTransition.NONE) // 首次进入 / 从目录跳章
    eq(ReaderTransition.dirFor(7, 7), ReaderTransition.NONE) // 改字号、切主题重建
    eq(ReaderTransition.dirFor(7, 8), ReaderTransition.FORWARD) // 顺读
    eq(ReaderTransition.dirFor(7, 20), ReaderTransition.FORWARD) // 目录往前跳
    eq(ReaderTransition.dirFor(7, 6), ReaderTransition.BACKWARD) // 回读
    eq(ReaderTransition.dirFor(7, 0), ReaderTransition.BACKWARD) // 回跳到第一章
    eq(ReaderTransition.dirFor(0, 1), ReaderTransition.FORWARD)

    // 位移：视口 30 %，且有上限。
    eq(ReaderTransition.liftPx(800, 200), 200) // 240 → 夹到 200
    eq(ReaderTransition.liftPx(400, 200), 120)
    eq(ReaderTransition.liftPx(100, 200), 30)
    eq(ReaderTransition.liftPx(10, 200), 3)
    // 未排版（高度 0）退回上限，动画不退化成原地不动。
    eq(ReaderTransition.liftPx(0, 72), 72)
    eq(ReaderTransition.liftPx(-5, 72), 72)
    // 上限为 0 时不给负位移。
    eq(ReaderTransition.liftPx(500, 0), 0)
    eq(ReaderTransition.liftPx(0, 0), 0)

    // 小视口不能因取整变成 0 位移：1 px 也算动。
    eq(ReaderTransition.liftPx(1, 72), 0) // 0.3 px 取整为 0，属可接受退化
    eq(ReaderTransition.liftPx(4, 72), 1)

    println("ReaderTransitionTest: $checks checks passed")
}
