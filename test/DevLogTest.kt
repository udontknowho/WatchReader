import com.watchdl.core.DevLog
import java.io.File

/**
 * 纯 JVM 自检。DevLog 只依赖 java.io/java.util，android.util.Log 走反射，
 * 所以测试里能直接跑，验证环形缓冲、落盘、关闭时不写盘三条关键行为。
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("  ✓ $name") else { failures++; println("  ✗ $name  $detail") }
    }

    val dir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "devlog-${System.nanoTime()}")
    dir.mkdirs()
    val logFile = File(dir, "dev.log")
    val ring = File(dir, "ring")

    DevLog.file = logFile
    DevLog.clear()

    // 关着：不写盘、不留缓冲。这是默认状态，用户不点开关就不该占存储。
    DevLog.setEnabled(false)
    DevLog.log("T", "关闭时的日志")
    check("关闭时不进缓冲", DevLog.dump().isEmpty(), "得到 ${DevLog.dump()}")
    check("关闭时不建文件", !logFile.exists() || logFile.length() == 0L)

    // 打开：立即落一条，让用户点完开关就能导出到东西。
    DevLog.setEnabled(true)
    check("开启后有一条起始记录", DevLog.dump().size == 1, "得到 ${DevLog.dump()}")
    check("开启即可见文件内容", logFile.exists() && logFile.length() > 0,
          "长度 ${if (logFile.exists()) logFile.length() else -1}")
    // 行格式：MM-dd HH:mm:ss.SSS [线程] TAG msg（毫秒与线程名是这轮新加的）
    check("行头带毫秒和线程名",
          Regex("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3} \\[.+\\] \\S+ ").containsMatchIn(DevLog.dump().first()),
          DevLog.dump().first())

    // 缓冲满：只留最近 CAP 条，不能无限涨（手表内存只有几百兆）。
    DevLog.clear()
    repeat(3200) { DevLog.log("T", "第 $it 条") }   // 3200 > CAP 3000
    val d = DevLog.dump()
    check("缓冲封顶 3000", d.size == 3000, "实际 ${d.size}")
    check("留下的是最新的", d.last().contains("第 3199 条"), "末条 ${d.lastOrNull()}")
    check("丢的是最旧的", d.none { it.contains("第 100 条") })

    // 落盘：每 50 条或 2 秒一次，3200 条必然写过多轮。先 flush 到齐再数行。
    check("落盘有内容", logFile.length() > 0, "长度 ${logFile.length()}")
    DevLog.forceFlush()
    val text = logFile.readText()
    // 不重复膨胀：文件行数必须等于写入条数。以前 appendLocked 用 size<flushed 判有没有新行，
    // ring 一封顶就恒为 false —— 文件冻在最早 3000 行、且每轮重抄整个 ring。
    val lines = text.lines().filter { it.isNotBlank() }.size
    check("写入不重复膨胀", lines == 3200, "行数 $lines")
    check("导出读文件且行数齐", DevLog.exportText().lines().count { it.isNotBlank() } == 3200,
          DevLog.exportText().length.toString())

    // 超限轮转：不让日志把用户存储吃光。
    val big = File(dir, "big.log")
    DevLog.file = big
    DevLog.clear()
    // clear 会清空文件，所以清完再灌满，否则轮转分支根本没被走过。
    big.writeText("x".repeat(3 * 1024 * 1024))   // > MAX_BYTES 2MB
    repeat(60) { DevLog.log("T", "轮转 $it") }
    DevLog.forceFlush()
    check("超 2MB 后轮转", big.length() < 64 * 1024, "长度 ${big.length()}")

    // 清空
    DevLog.clear()
    check("清空后缓冲为空", DevLog.dump().isEmpty())

    // 没设 file 时不能崩（纯 JVM 场景 / 初始化顺序出错时的兜底）
    DevLog.file = null
    DevLog.log("T", "无文件也不该抛异常")
    check("无文件对象时缓冲照收", DevLog.dump().isNotEmpty())

    File(dir, "dev.log").delete()
    big.delete()
    dir.delete()
    if (ring.exists()) ring.delete()

    println(if (failures == 0) "DevLogTest passed" else "DevLogTest 失败 $failures")
    if (failures > 0) throw AssertionError("DevLogTest: $failures 项失败")
}
