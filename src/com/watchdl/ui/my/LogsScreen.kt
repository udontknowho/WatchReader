package com.watchdl.ui.my

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.DevLog
import com.watchdl.core.LogFilter
import com.watchdl.core.Screen
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File

/**
 * 日志：开发者日志的开关、体积、尾巴，导出与清空在顶栏 ⋮ 里。
 *
 * 原来这些只在设置页里有一个「开/关」和一个「导出」，看不到日志本身 ——
 * 用户报「刚才点了没反应」时，人得先插线导文件才能判断，而手表插线本身很麻烦。
 * 这一页把尾巴直接摊在屏上，看得到才有用。
 *
 * 开关语义仍归 `devLogOn()` / `toggleDevLog()`：两处入口（设置页、本页）
 * 共用同一份状态，否则一处开了另一处还显示关着。
 */

/** 正文区最多显示多少行。手表一屏约 5 行，40 行已经是「往回翻七八屏」。 */
private const val TAIL_LINES = 40

/** 落盘文件名，与 [com.watchdl.core.DevLog.file] 的赋值处一致。 */
private const val LOG_NAME = "dev.log"

/** 一次读取的结果。[bytes] 取文件实际长度（不是已显示部分），[lines] 是全文行数。 */
private class LogTail(val bytes: Long, val lines: Int, val tail: List<String>)

/**
 * 屏上那一次读到的尾巴。
 *
 * ⋮ 里的「复制」直接用它，不再读一次盘：日志文件上限 2 MB，
 * 为一次点击在主线程重读一遍就是掉帧。它只活在本次绘制之后，
 * 换屏也不清 —— 反正每次进日志页都会重写一次。
 */
private var lastTail: LogTail? = null
private var logTagFilter = ""
private var logErrorsOnly = false

internal fun MainActivity.showLogs() {
    setScreen(Screen.Logs)
    root.removeAllViews()
    // 导出与清空收进 ⋮：都是次要动作，平铺成整宽按钮会吃掉 ~80dp，
    // 而这一页的全部价值是下面的日志尾巴。legado 里「清除」在 `CrashLogSheet`
    // 的 endAction（DeleteSweep 图标）上，「拷贝」在 `CrashReportScreen` 的按钮行里，
    // 都不是列表项。手表上一行放不下两个图标按钮，三条合一进长菜单，与全站一致。
    root.addView(Widgets.topBar(
        ctx = this,
        title = "日志",
        onBack = { showMy() },
        trailing = listOf("more" to { logMenu() }),
    ))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    // 先给占位：读文件是磁盘 IO（上限 2MB），慢的时候不能是空白屏。
    col.addView(Widgets.emptyState(this, "history", "正在读取…"))
    root.addView(body, Md3.lpWeight(1f))

    // 整页一次画完（含开关行）：开关状态与文件内容属于同一次快照，
    // 分两段拼会出现「开关说开着、下面还是关着时的空态」。
    bg(Screen.Logs, body = { readLogTail() }) { r ->
        renderLogs(col, r.getOrNull(), r.isFailure)
        r.exceptionOrNull()?.let {
            col.addView(Widgets.emptyState(this, "warning", "日志读取失败\n${it.message?.take(48) ?: "文件不可读"}"))
            col.addView(Widgets.button(this, "重新读取", "tonal") { showLogs() })
        }
    }
}

/**
 * 顶栏 ⋮：导出日志 / 复制日志 / 清除日志。
 *
 * 三条对应物在 legado 分散在三处：导出是关于页的 `SaveLog`，复制是
 * `CrashReportScreen` 的 `copy_text`（拷贝内容），清除是 `CrashLogSheet`
 * 顶栏的 DeleteSweep。手表上顶栏只放得下一个 ⋮，三条都收进长菜单。
 */
private fun MainActivity.logMenu() {
    Widgets.longMenuDialog(this, "日志", listOf(
        "导出日志" to { exportDevLog() },
        "复制日志" to { copyLogTail() },
        "清除日志" to { askClearLog() },
    )).show()
}

/**
 * 把屏上这段尾巴拷进剪贴板。
 *
 * 手表没有能看大段文字的编辑器，复制只在「传给手机 / 发给别人看」时有意义；
 * 但报问题的时候，恰恰就需要把最后几十行原样贴出去 —— legado 的
 * 崩溃报告页也是同一理由才摆了个复制按钮。
 */
private fun MainActivity.copyLogTail() {
    val lines = lastTail?.tail.orEmpty()
    if (lines.isEmpty()) {
        toast("没有可复制的日志")
        return
    }
    val cm = runCatching {
        getSystemService(ClipboardManager::class.java)
    }.getOrNull()
    if (cm == null) {
        toast("这块表不支持剪贴板")
        return
    }
    cm.setPrimaryClip(ClipData.newPlainText(LOG_NAME, lines.joinToString("\n")))
    toast("已复制最后 ${lines.size} 行")
}

/**
 * 读体积、行数与最后 [TAIL_LINES] 行。只在后台线程调用。
 *
 * 逐行流式过一遍而不是整份读进内存：文件到 2MB 时有几万行，
 * 而真正要显示的只有最后 40 行。
 */
private fun MainActivity.readLogTail(): LogTail? {
    val f = File(filesDir, LOG_NAME)
    if (!f.isFile) return null
    // 环里的行不一定已落盘（攒 50 条或 2 秒才写一次）：不先 flush，
    // 刚点的那个操作就看不到，看着像功能没生效。
    DevLog.forceFlush()
    var count = 0
    val filter = LogFilter(logTagFilter, logErrorsOnly)
    val ring = ArrayDeque<String>()
    f.bufferedReader().useLines { seq ->
        for (line in seq) {
            if (line.isBlank()) continue
            count++
            if (!filter.matches(line)) continue
            ring.addLast(line)
            if (ring.size > TAIL_LINES) ring.removeFirst()
        }
    }
    return LogTail(f.length(), count, ring.toList())
}

private fun MainActivity.renderLogs(col: LinearLayout, tail: LogTail?, failed: Boolean = false) {
    col.removeAllViews()
    // ⋮ 里的「复制」读的是这一份，不再读盘。
    lastTail = tail
    val on = devLogOn()

    // 开关是一行带 description 的设置行（legado `SwitchSettingItem`：
    // 标题 + 描述 + 右侧开关），描述固定不随状态变，开关态只放 trailing。
    // 原来是自己拿 30dp 勾拼的行，标题写着「日志：开 / 日志：关」——
    // 标题跟着状态变，读起来像换了一个条目。
    // 开关必须常驻屏上：这一页最容易出现「怎么什么都没有」，
    // 而原因通常就是开关没开，不是没产生日志。
    col.addView(Widgets.groupCard(this, "记录") { g ->
        g.addView(Widgets.listItem(
            ctx = this,
            title = "记录日志",
            subtitle = devLogHint(),
            twoLine = true,
            trailing = if (on) "开" else "关",
            onClick = { toggleDevLog() },
        ))
        // legado 这里还有一条 record_heap_dump（生成堆转储）开关，不做：
        // Debug.dumpHwHeap 只对 debuggable 包有效，而 .hprof 动辄几十 MB，
        // 手表存储放不下，导到文档也没法看。
    })

    if (failed) return
    if (tail == null) {
        // 空态照 legado `EmptyMessage`：图标 + 一句「暂无日志」，
        // 再跟一句指向开关的话 —— 这一页的空屏十有八九是开关没开。
        col.addView(Widgets.emptyState(this, "history",
            "暂无日志\n上方开关打开后产生的日志会显示在这里"))
        return
    }

    col.addView(Widgets.sectionLabel(this, "$LOG_NAME · ${humanBytes(tail.bytes)} · ${tail.lines} 行"))
    run {
        col.addView(Widgets.sectionLabel(this, "筛选后最新 ${tail.tail.size} 行"))
        col.addView(Widgets.button(this, "标签：${logTagFilter.ifBlank { "全部" }}", "text") {
            Widgets.longMenuDialog(this, "日志标签", listOf("全部", "WatchDL", "UI", "Source", "History").map { tag ->
                tag to { logTagFilter = if (tag == "全部") "" else tag; showLogs() }
            }).show()
        })
        col.addView(Widgets.button(this, if (logErrorsOnly) "仅错误：开" else "仅错误：关", "text") {
            logErrorsOnly = !logErrorsOnly; showLogs()
        })
        val filtered = tail.tail
        col.addView(Widgets.sectionLabel(this, "匹配 ${filtered.size} / ${tail.tail.size} 行"))
        if (filtered.isEmpty()) col.addView(Widgets.emptyState(this, "search", "没有匹配日志"))
        else col.addView(logBlock(filtered))
    }
}

/**
 * 日志正文：等宽字体装在一枚圆角容器里。
 *
 * 形态照 legado `CrashReportScreen` 的正文区（`surfaceContainerLow` + 12dp 圆角 +
 * `FontFamily.Monospace` + bodySmall）。等宽不是怀旧：时间戳与屏名对齐之后，
 * 一眼能扫出「连续掉帧」还是「一条一条零散」，变宽字体下这个信息就没了。
 *
 * 整段一次画完，而不是一行一个 TextView：40 行就是 40 个 View，
 * 而 TextView 自己就按 `\n` 断行，行首的换行一个也不会丢。
 */
private fun MainActivity.logBlock(lines: List<String>): LinearLayout {
    val card = Md3.column(this)
    card.background = Md3.bg(Md3.surface2, Md3.R_CARD.toFloat())
    val pad = Md3.dp(this, Md3.PAD * 0.6)
    card.setPadding(pad, pad, pad, pad)
    card.addView(
        // 单行最长可到几百字，189dp 宽放不下：整段自动换行比横向滚动好读，
        // 真要看原文有 ⋮ 里的「复制日志」。
        Md3.text(this, lines.joinToString("\n"), Md3.FS_SM, Md3.onSurfaceVariant, mono = true)
            .apply { setLineSpacing(Md3.dp(this@logBlock, 2).toFloat(), 1f) },
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ),
    )
    card.layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = Md3.dp(this@logBlock, Md3.GAP) }
    return card
}

/** 清空不可撤销，且会连带清掉内存环（崩溃前那点上下文就没了），所以要问一次。 */
private fun MainActivity.askClearLog() {
    Widgets.md3Dialog(this,
        title = "清空日志",
        body = "会清掉 dev.log 与内存里的日志缓冲。\n要留证据先导出再清。",
        confirm = "清空",
        onConfirm = {
            DevLog.clear()
            toast("已清空日志")
            showLogs()
        },
    ).show()
}
