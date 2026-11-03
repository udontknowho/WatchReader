package com.watchdl.ui.my

import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.DevLog
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

internal fun MainActivity.showLogs() {
    setScreen(Screen.Logs)
    root.removeAllViews()
    // 导出与清空收进 ⋮：两者都是次要动作，平铺成两个整宽按钮会吃掉 ~80dp，
    // 而这一页的全部价值是下面的日志尾巴。legado 里「清空」也在
    // `CrashLogSheet` 的 endAction 位置上，不是列表里的一行按钮。
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
        renderLogs(col, r.getOrNull())
    }
}

/**
 * 顶栏 ⋮：导出日志 / 清空日志。
 *
 * legado 的两条对应物都在别处：导出在关于页的 `SaveLog`（点了直接导），
 * 清空在 `CrashLogSheet` 标题右侧的图标按钮。手表上顶栏放不下第二个动作，
 * 所以两条合一进长菜单，与全站其它页的 ⋮ 一致。
 */
private fun MainActivity.logMenu() {
    Widgets.longMenuDialog(this, "日志", listOf(
        "导出日志" to { exportDevLog() },
        "清空日志" to { askClearLog() },
    )).show()
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
    val ring = ArrayDeque<String>()
    runCatching {
        f.bufferedReader().useLines { seq ->
            for (line in seq) {
                if (line.isBlank()) continue
                count++
                ring.addLast(line)
                if (ring.size > TAIL_LINES) ring.removeFirst()
            }
        }
    }.onFailure { return null }
    return LogTail(f.length(), count, ring.toList())
}

private fun MainActivity.renderLogs(col: LinearLayout, tail: LogTail?) {
    col.removeAllViews()
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

    if (tail == null) {
        // 空态照 legado `EmptyMessage`：图标 + 一句话，且点明先看开关。
        col.addView(Widgets.emptyState(this, "history",
            "还没有日志文件。\n上方开关打开后产生的日志会显示在这里。"))
        return
    }

    col.addView(Widgets.sectionLabel(this, "$LOG_NAME · ${humanBytes(tail.bytes)} · ${tail.lines} 行"))
    if (tail.tail.isEmpty()) {
        col.addView(Widgets.emptyState(this, "history",
            "日志文件是空的。\n开关打开后产生的日志会显示在这里。"))
    } else {
        // legado 的日志先列文件名、点开才看正文（CrashLogSheet → LogDetailSheet）。
        // 我们只有一个 dev.log，正文直接摊在页里：手表上多一次点击换不来什么，
        // 而「点了没反应」正是要当场看见的那件事。
        col.addView(Widgets.sectionLabel(this, "最后 ${tail.tail.size} 行"))
        for (line in tail.tail) {
            // 一行一条 TextView：日志是逐行读的，拼成一大段会丢掉行首的换行。
            // 单条最长可到几百字，189dp 宽放不下，截尾比横向滚动好用。
            col.addView(Md3.ellipsize(Md3.text(this, line, Md3.FS_SM, Md3.onSurfaceVariant)))
        }
    }
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
