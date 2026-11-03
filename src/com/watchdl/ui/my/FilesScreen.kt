package com.watchdl.ui.my

import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.Screen
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件管理（#27）：应用私有目录的占用，逐项可删。
 *
 * 只列代码里写死路径的四个目录，加 filesDir 下直接放的普通文件。
 * 别处再冒出新目录也不进这一页 —— 这一页的每一行都配删，多列一行就多一个
 * 用户看不懂也不敢碰的东西。
 *
 * 形态照 legado `FileManageActivity`：目录在前、文件在后，行 = 图标 + 名称 + 右侧动作。
 * legado 那页是任意层级浏览（面包屑 + 进目录 + 点开文件 + 顶栏搜索），
 * 这里只做一层：路径全是代码写死的，浏览层级对用户没有意义。
 */

/** 取用处写死路径的四个目录：下载作业、导出、书源、番茄设备注册。 */
private val MANAGED_DIRS = listOf("books", "export", "sources", "fangqie")

/** 一行。[count] 目录是递归文件数，单文件固定 1。 */
private class FileRow(
    val name: String,
    val file: File,
    val isDir: Boolean,
    val count: Int,
    val bytes: Long,
    val modified: Long,
)

internal fun MainActivity.showFiles() {
    setScreen(Screen.Files)
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "文件管理", onBack = { showMy() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    // 先给个占位：统计要递归整棵 books/ 树，慢的时候屏幕上不能是空白。
    col.addView(Widgets.emptyState(this, "grid", "正在统计…"))
    root.addView(body, Md3.lpWeight(1f))

    // 递归求和在主线程会把界面冻住一两秒（books/ 下每本书几十上百个文件）。
    bg(Screen.Files, body = { scanFileRows() }) { r ->
        renderFileRows(col, r.getOrDefault(emptyList()))
    }
}

/** 统计一屏要显示的行。只在后台线程调用。 */
private fun MainActivity.scanFileRows(): List<FileRow> {
    val out = mutableListOf<FileRow>()
    for (name in MANAGED_DIRS) {
        val dir = File(filesDir, name)
        // 目录不存在也照样列一行 0：少一行会让人以为路径已经改到别处。
        out += FileRow("$name/", dir, true, fileCount(dir), fileBytes(dir), dir.lastModified())
    }
    // filesDir 直接放的普通文件（dev.log、bookmarks.json 等）。子目录已在上面的行里。
    filesDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach {
        out += FileRow(it.name, it, false, 1, it.length(), it.lastModified())
    }
    return out
}

private fun fileCount(dir: File): Int = runCatching {
    dir.walkTopDown().count { it.isFile }
}.getOrDefault(0)

private fun fileBytes(dir: File): Long = runCatching {
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}.getOrDefault(0L)

/** 时间戳 → 「08-14 21:30」。行副标题第二段用，年月对「这文件是不是刚写的」没帮助。 */
private fun stamp(ms: Long): String =
    if (ms <= 0L) "" else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

/**
 * 画两段：目录 / 根目录文件。段标题自带数量与总量。
 *
 * 不再在顶上另起一行「共 N 项 · X」，也不在末尾补「合计」：屏只有 248 dp 高，
 * 顶栏之外能看的也就五行，三行统计会把最后一行挤出屏（缓存管理页同一取舍）。
 * 总量 = 两段相加，要算的时候眼前这两个数就够。
 */
private fun MainActivity.renderFileRows(col: LinearLayout, rows: List<FileRow>) {
    col.removeAllViews()
    val dirs = rows.filter { it.isDir }
    val loose = rows.filter { !it.isDir }

    // 目录段照 legado 的排序（目录先于文件），只是我们多给每段一个标题。
    col.addView(Widgets.groupCard(this,
        "目录 ${dirs.size} 个 · ${humanBytes(dirs.sumOf { it.bytes })}") { g ->
        for (row in dirs) {
            g.addView(Widgets.groupItem(
                ctx = this,
                title = row.name,
                subtitle = "${row.count} 个文件 · ${humanBytes(row.bytes)} · ${stamp(row.modified)}",
                glyph = "folder",
                trailing = "✕",
                onTrailingClick = { askDeleteFile(row) },
            ))
        }
    })

    col.addView(Widgets.groupCard(this,
        "根目录文件 ${loose.size} 个 · ${humanBytes(loose.sumOf { it.bytes })}") { g ->
        if (loose.isEmpty()) {
            g.addView(Widgets.emptyState(this, "file", "根目录下没有散文件。"))
        }
        for (row in loose) {
            g.addView(Widgets.groupItem(
                ctx = this,
                title = row.name,
                subtitle = "${humanBytes(row.bytes)} · ${stamp(row.modified)}",
                glyph = "file",
                trailing = "✕",
                onTrailingClick = { askDeleteFile(row) },
            ))
        }
    })
}

/**
 * 删除前必须问一次，文案要点清「删什么、多少字节」。
 *
 * 这一页是全应用唯一能删私有目录的入口，误点 books/ 就是整个书架没了，
 * 而正文只能联网重下 —— 比清理缓存那次更贵，所以确认框不能省。
 * legado 那边长按弹出菜单、菜单里只有「删除」且不确认；我们保留行右侧可见的
 * ✕ 加一道确认：手表上长按没有可见提示，藏起来的删除等于找不到，
 * 而误删 books/ 的代价是整架书重下。
 */
private fun MainActivity.askDeleteFile(row: FileRow) {
    val what = if (row.isDir) "${row.name}下的 ${row.count} 个文件" else "文件 ${row.name}"
    // books/ 要额外提醒：作业和进度都在里面，删了不是"腾地方"而是"丢书"。
    val extra = if (row.name == "books/") "\n书架与下载进度都在这，删了要重新搜书下载。" else ""
    Widgets.md3Dialog(this,
        title = "删除 ${row.name}",
        body = "会删掉$what，共 ${row.bytes} 字节（${humanBytes(row.bytes)}）。${extra}\n删掉就找不回来。",
        confirm = "删除",
        onConfirm = { deleteFileRow(row) },
    ).show()
}

/** 落盘删除放后台：books/ 下几百个文件，主线程删会卡住界面。 */
private fun MainActivity.deleteFileRow(row: FileRow) {
    bg(Screen.Files, body = {
        runCatching {
            if (row.isDir) {
                // **不删目录本身**：books/ 与 export/ 的路径在下载、导出、书架三处写死，
                // 目录没了运行中的作业会认不出自己。删内容再补 mkdirs 即可。
                row.file.listFiles()?.forEach { it.deleteRecursively() }
                row.file.mkdirs()
            } else {
                row.file.delete()
            }
        }.getOrDefault(false)
    }) { r ->
        toast(if (r.getOrDefault(false)) "已删除 ${row.name}" else "删除失败：${row.name}")
        showFiles()
    }
}
