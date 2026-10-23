import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 屏身份守卫：后台任务回来时，只在「当初起它的那屏仍是当前屏」时才允许动界面。
 *
 * 抽出来单独测是因为这条规则靠肉眼审代码守不住 —— 13 处 `thread {}` 里
 * 只有 1 处原来做了检查。这里把判据的三种组合钉死：
 * 同一屏放行、换了屏拦住、Activity 已销毁拦住。
 */

/** 主线程消息队列的替身：记录回调有没有被真正执行。 */
private class Guarded {
    val ran = AtomicBoolean(false)
    var current: String = "hits"
    var destroyed = false
    var finished = false

    /** 与 MainActivity.postAlive 同一套判据。 */
    fun post(owner: String, body: () -> Unit) {
        if (destroyed || finished) return
        if (current != owner) return
        ran.set(true)
        body()
    }
}

private var failures = 0

private fun check(desc: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ✓ $desc")
    else { println("  ✗ $desc  $detail"); failures++ }
}

fun main() {
    println("[异步回调] 屏身份守卫")

    run {
        println("[异步回调] 起任务时手里是空白屏，回调时必须仍能在结果屏上执行")
        // 这是 1.3.4 的真实回归：owner 是 Hits(kw, emptyList())，
        // 屏上是 Hits(kw, 实际结果)。比整个 data class 时永远不等，回调全丢。
        val g = Guarded()
        val hits = AtomicInteger()
        g.current = "hits:三体"          // 屏上带着搜索结果
        g.post("hits:三体") { hits.incrementAndGet() }
        check("空白 owner 与结果屏算同一屏", hits.get() == 1, "实际 ${hits.get()}")
    }

    run {
        println("[异步回调] 同屏：回调必须执行")
        val g = Guarded()
        val hits = AtomicInteger()
        g.current = "hits"
        g.post("hits") { hits.incrementAndGet() }
        check("结果写回界面", hits.get() == 1, "实际 ${hits.get()}")
    }

    run {
        println("[异步回调] 用户按返回后结果才回来：必须丢弃")
        val g = Guarded()
        val hits = AtomicInteger()
        g.current = "home"          // 用户已经退回首页
        g.post("hits") { hits.incrementAndGet() }
        check("不把用户拽回搜索结果页", hits.get() == 0, "实际 ${hits.get()}")
    }

    run {
        println("[异步回调] 换到另一个同类屏（换一批源）：必须丢弃旧的")
        val g = Guarded()
        val hits = AtomicInteger()
        g.current = "hits-offset-15"   // 新一轮搜索
        g.post("hits-offset-0") { hits.incrementAndGet() }
        check("旧一批的结果不覆盖新一批", hits.get() == 0, "实际 ${hits.get()}")
    }

    run {
        println("[异步回调] Activity 已销毁：必须丢弃")
        val g = Guarded()
        val hits = AtomicInteger()
        g.destroyed = true
        g.post("hits") { hits.incrementAndGet() }
        check("不碰已销毁的界面", hits.get() == 0, "实际 ${hits.get()}")
    }

    run {
        println("[异步回调] Activity 正在关闭：必须丢弃")
        val g = Guarded()
        val hits = AtomicInteger()
        g.finished = true
        g.post("hits") { hits.incrementAndGet() }
        check("不往正在关闭的 Activity 弹 toast", hits.get() == 0, "实际 ${hits.get()}")
    }

    run {
        println("[异步回调] 切屏要能取消在跑的任务")
        // 与 cancelTasks 同一行为：中断全部登记线程并清空。
        val tasks = mutableListOf<Thread>()
        val interrupted = AtomicInteger()
        repeat(3) {
            val t = Thread { try { Thread.sleep(60_000) } catch (e: InterruptedException) {
                interrupted.incrementAndGet()
            } }
            t.isDaemon = true
            t.start()
            tasks.add(t)
        }
        tasks.forEach { it.interrupt() }
        tasks.forEach { it.join(1_000) }
        check("三个任务全被中断", interrupted.get() == 3, "实际 ${interrupted.get()}")
    }

    println()
    if (failures == 0) println("全部通过") else {
        println("失败 $failures 项"); kotlin.system.exitProcess(1)
    }
}
