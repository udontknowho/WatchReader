package com.watchdl.ui.my

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.DevLog
import com.watchdl.core.Screen
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File

/**
 * 日志（#30）：开发者日志的开关、体积、尾巴，以及导出与清空。
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
    root.addView(Widgets.topBar(this, "日志", onBack = { showMy() }))

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
    // 开关状态必须常驻屏上：这一页最容易出现「怎么什么都没有」，
    // 而原因通常就是开关没开，不是没产生日志。
    col.addView(logToggleRow(on))

    if (tail == null) {
        col.addView(Widgets.emptyState(this, "history", "还没有日志文件。\n开关打开后产生的日志会显示在这里。"))
    } else {
        col.addView(Widgets.sectionLabel(this, "$LOG_NAME · ${humanBytes(tail.bytes)} · ${tail.lines} 行"))
        if (tail.tail.isEmpty()) {
            col.addView(Widgets.emptyState(this, "history", "日志文件是空的。\n开关打开后产生的日志会显示在这里。"))
        } else {
            col.addView(Widgets.sectionLabel(this, "最后 ${tail.tail.size} 行"))
            for (line in tail.tail) {
                // 一行一条 TextView：日志是逐行读的，拼成一大段会丢掉行首的换行。
                // 单条最长可到几百字，189dp 宽放不下，截尾比横向滚动好用。
                col.addView(Md3.ellipsize(Md3.text(this, line, Md3.FS_SM, Md3.onSurfaceVariant)))
            }
        }
    }

    // 两个按钮竖着放：189dp 宽的行并排放两个会把标签挤成半个字。
    col.addView(Widgets.button(this, "导出日志", "outline") { exportDevLog() })
    col.addView(Widgets.button(this, "清空日志", "outline") { askClearLog() })
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

/**
 * 日志开关行：一行内的勾，点整行切开关。
 *
 * 原来是「一行 listItem 说状态」加「一个整宽按钮切开关」两件套，
 * 首屏先被这 96 dp 吃掉，而这一页的全部价值是下面的日志尾巴。
 * 写法照书源屏 `sourceRow` 的 30 dp 勾：开=主色，关=压到 0.3 透明。
 * 勾只做显示，点击交给整行 —— 18 dp 的图形当唯一可点区太难戳中。
 */
private fun MainActivity.logToggleRow(on: Boolean): LinearLayout {
    val row = Md3.row(this)
    row.minimumHeight = Md3.dp(this, Md3.ITEM)
    row.setPadding(Md3.dp(this, Md3.PAD * 3 / 4), 0, Md3.dp(this, Md3.PAD / 2), 0)
    row.background = Md3.ripple(this, Color.TRANSPARENT, Md3.R_ITEM.toFloat())

    val col = Md3.column(this)
    col.addView(Md3.ellipsize(Md3.text(this, if (on) "日志：开" else "日志：关",
        Md3.FS_MD, Md3.onSurface)).apply { alpha = if (on) 1f else 0.45f })
    col.addView(Md3.ellipsize(Md3.text(this,
        if (on) "只记本 App，不读系统日志" else "关闭时只打 logcat，不存盘",
        Md3.FS_SM, Md3.onSurfaceVariant)).apply { alpha = if (on) 0.7f else 0.3f })
    row.addView(col, LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    val mark = Icons.view(this, "check", if (on) Md3.primary else Md3.onSurfaceVariant, 16)
    mark.alpha = if (on) 1f else 0.3f
    mark.contentDescription = if (on) "已开启" else "已关闭"
    row.addView(mark, LinearLayout.LayoutParams(
        Md3.dp(this, 30), Md3.dp(this, Md3.ITEM)).apply { gravity = Gravity.CENTER_VERTICAL })

    row.isClickable = true
    // toggleDevLog 自己会重画当前屏（日志页 / 设置页各画各的），这里不必再调 showLogs。
    row.setOnClickListener { toggleDevLog() }
    return row
}
