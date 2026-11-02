package com.watchdl.ui.my

import android.app.AlertDialog
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.Screen
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File

/**
 * 文件管理（#27）：应用私有目录的占用，逐项可删。
 *
 * 只列代码里写死路径的四个目录，加 filesDir 下直接放的普通文件。
 * 别处再冒出新目录也不进这一页 —— 这一页的每一行都配删，多列一行就多一个
 * 用户看不懂也不敢碰的东西。
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
        out += FileRow("$name/", dir, true, fileCount(dir), fileBytes(dir))
    }
    // filesDir 直接放的普通文件（dev.log、bookmarks.json 等）。子目录已在上面的行里。
    filesDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach {
        out += FileRow(it.name, it, false, 1, it.length())
    }
    return out
}

private fun fileCount(dir: File): Int = runCatching {
    dir.walkTopDown().count { it.isFile }
}.getOrDefault(0)

private fun fileBytes(dir: File): Long = runCatching {
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}.getOrDefault(0L)

private fun MainActivity.renderFileRows(col: LinearLayout, rows: List<FileRow>) {
    col.removeAllViews()
    val total = rows.sumOf { it.bytes }
    col.addView(Widgets.sectionLabel(this, "共 ${rows.size} 项 · ${humanBytes(total)}"))

    for (row in rows) {
        col.addView(Widgets.listItem(
            ctx = this,
            title = row.name,
            subtitle = if (row.isDir) "${row.count} 个文件 · ${humanBytes(row.bytes)}"
                       else humanBytes(row.bytes),
            trailing = "✕",
            onTrailingClick = { askDeleteFile(row) },
        ))
    }
    col.addView(Md3.text(this, "合计 ${humanBytes(total)}", Md3.FS_SM, Md3.onSurfaceVariant))
}

/**
 * 删除前必须问一次，文案要点清「删什么、多少字节」。
 *
 * 这一页是全应用唯一能删私有目录的入口，误点 books/ 就是整个书架没了，
 * 而正文只能联网重下 —— 比清理缓存那次更贵，所以确认框不能省。
 */
private fun MainActivity.askDeleteFile(row: FileRow) {
    val what = if (row.isDir) "${row.name}下的 ${row.count} 个文件" else "文件 ${row.name}"
    // books/ 要额外提醒：作业和进度都在里面，删了不是"腾地方"而是"丢书"。
    val extra = if (row.name == "books/") "\n书架与下载进度都在这，删了要重新搜书下载。" else ""
    Widgets.styleDialog(AlertDialog.Builder(this)
        .setTitle("删除 ${row.name}")
        .setMessage("会删掉$what，共 ${row.bytes} 字节（${humanBytes(row.bytes)}）。${extra}\n删掉就找不回来。")
        .setNegativeButton("取消", null)
        .setPositiveButton("删除") { _, _ -> deleteFileRow(row) }
        .create()).show()
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
