/**
 * 「换一批源」的轮转推进自检。不碰网络 —— 只验纯算术，跟 UI 无关的部分。
 *
 * 复刻 `MainActivity.nextSourceBatch` 的公式。它以前是 `offset + step`，
 * 源总数不是 step 的整数倍时会周期重复（42 条源 × 15 一构：
 * 0→15→30→3→18→33→6→21→36→9…），有的源永远轮不到。
 * 改成余数后要注意「转满一圈」这个终止条件别丢。
 */
/** 最大公约数。轮转不同起点的个数是 total/gcd(step,total)，不是 total/step。 */
private fun gcd(a: Int, b: Int): Int = if (b == 0) kotlin.math.abs(a) else gcd(b, a % b)

fun main() {    var failures = 0
    fun check(n: String, ok: Boolean, d: String = "") {
        if (ok) println("  ✓ $n") else { failures++; println("  ✗ $n  $d") }
    }

    /** 与 MainActivity.nextSourceBatch 同一套算法：返回 null = 转满一圈。 */
    fun next(offset: Int, step: Int, total: Int): Int? {
        val s = step.coerceAtLeast(1)
        if (total <= 0) return null
        val n = (offset + s) % total
        return if (n == 0) null else n
    }

    println("[换一批] 源总数是步长的整数倍")
    check("0 → 15", next(0, 15, 45) == 15)
    check("15 → 30", next(15, 15, 45) == 30)
    check("30 → 转满", next(30, 15, 45) == null)

    println("[换一批] 源总数不是整数倍，必须每批都出新源")
    run {
        val total = 42
        val step = 15
        val seen = mutableListOf<Int>()
        var off = 0
        while (true) {
            val n = next(off, step, total) ?: break
            seen += n
            off = n
            if (seen.size > total) break        // 防死循环
        }
        check("每步都不同源起点", seen.distinct().size == seen.size, "序列 $seen")
        // 42/gcd(15,42)=42/3=14 个不同批次（不含初始 0）—— 不是简单的 42/15。
        check("轮到的起点数量正确", seen.size == total / gcd(step, total) - 1,
            "实际 ${seen.size}")
    }

    println("[换一批] 只有一个源时不乱转")
    check("total=1 → 转满", next(0, 1, 1) == null)

    println("[换一批] 步长为 0 时不除以零")
    check("step=0 当作 1", next(0, 0, 10) == 1)

    if (failures == 0) println("全部通过") else { println("失败 $failures 项"); kotlin.system.exitProcess(1) }
}
