package com.watchdl.ui.my

import android.app.AlertDialog
import com.watchdl.MainActivity
import com.watchdl.core.ChapterStatus
import com.watchdl.core.Downloader
import com.watchdl.core.Screen
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出书单（#29）：把书架上的书列成一份纯文本清单，落到私有 `export/` 目录。
 *
 * 走私有目录而不是公共「文档」：这本书单是给自己看的目录索引，
 * 而写公共目录要过存储权限，手表上那个授权弹窗经常把人挡在门外。
 *
 * 书清单只读 `job.json`（[Downloader.restoreLight]），不碰 `chapters.jsonl` ——
 * 几十本书的正文加起来几百 MB，为了一份清单去读正文是纯浪费。
 */

/** 生成的文件名前缀。判断「哪份是书单」只靠它，改名即视为用户自己的文件。 */
private const val PREFIX = "书单-"

internal fun MainActivity.showExportList() {
    setScreen(Screen.ExportList)
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "导出书单", onBack = { showMy() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    val count = jobsStore().listBooks().size
    // 新的排在前面：导出完第一件事是确认写成功了，最新那份不该藏在列表底部。
    val files = exportDir().listFiles()
        ?.filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    col.addView(Widgets.sectionLabel(this, "书架 $count 本"))
    col.addView(Widgets.button(this, "生成书单") { generateExportList() })
    // 最近那份单独占一行：文件列表里一屏能塞十几行，光靠排序分不出「刚生成的」。
    files.firstOrNull { it.name.startsWith(PREFIX) }?.let {
        col.addView(Md3.text(this, "最近生成 ${it.name} · ${humanBytes(it.length())}",
            Md3.FS_SM, Md3.onSurfaceVariant))
    }

    col.addView(Widgets.sectionLabel(this, "导出目录 · ${files.size} 个"))
    if (files.isEmpty()) {
        col.addView(Widgets.emptyState(this, "list", "导出目录还是空的。\n生成后会出现在这里。"))
    } else {
        for (f in files) {
            col.addView(Widgets.listItem(
                ctx = this,
                title = f.name,
                subtitle = humanBytes(f.length()),
                trailing = "✕",
                onTrailingClick = { askDeleteExport(f) },
            ))
        }
    }
    root.addView(body, Md3.lpWeight(1f))
}

/**
 * 生成清单。逐本读 `job.json` 是几十次小文件 IO，主线程上做就是一次看得见的停顿，
 * 所以整段（含写盘）放后台，回来只重画。
 */
private fun MainActivity.generateExportList() {
    val store = jobsStore()
    val dir = exportDir()
    bg(Screen.ExportList, body = {
        val ids = store.listBooks()
        val lines = ArrayList<String>(ids.size)
        val seen = HashMap<String, Int>()
        for (id in ids) {
            val job = Downloader.restoreLight(store, id) ?: continue
            val author = job.author.ifBlank { "佚名" }
            // 换源重下会留下同一本书的两个作业（bookId 不同），两行一模一样的书名
            // 看着像重复导出；加序号才分得清它们其实是两条记录。
            val key = job.name + "\u0000" + author
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            val title = if (n == 1) job.name else "${job.name}($n)"
            lines += "$title - $author（已下 ${job.count(ChapterStatus.DONE)}/共 ${job.total} 章）"
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val out = uniqueExportFile(dir, "$PREFIX$stamp")
        out.writeText(lines.joinToString("") { it + "\n" })
        Triple(out.name, out.length(), lines.size)
    }) { r ->
        val res = r.getOrNull()
        if (res == null) {
            toast("生成书单失败：${r.exceptionOrNull()?.message?.take(40) ?: "未知原因"}")
        } else {
            toast("已生成 ${res.first} · ${res.third} 本")
            showExportList()      // 重画：文件列表与「最近生成」都要跟着变
        }
    }
}

/**
 * 同一分钟里再点一次会撞名，加 `(2)(3)` 而不是覆盖。
 *
 * 覆盖的代价不对称：多一个文件只是列表长一行，而被盖掉的那份可能正拿在手上看。
 */
private fun uniqueExportFile(dir: File, base: String): File {
    var f = File(dir, "$base.txt")
    var n = 2
    while (f.exists()) {
        f = File(dir, "$base($n).txt")
        n++
    }
    return f
}

/** 删的是自己导出的清单，但同目录里可能还躺着别的文件，所以仍要问一次、点明删哪个。 */
private fun MainActivity.askDeleteExport(f: File) {
    Widgets.styleDialog(AlertDialog.Builder(this)
        .setTitle("删除 ${f.name}")
        .setMessage("会删掉 ${f.name}（${humanBytes(f.length())}）。\n删掉就找不回来。")
        .setNegativeButton("取消", null)
        .setPositiveButton("删除") { _, _ -> deleteExportFile(f) }
        .create()).show()
}

private fun MainActivity.deleteExportFile(f: File) {
    bg(Screen.ExportList, body = { runCatching { f.delete() }.getOrDefault(false) }) { r ->
        toast(if (r.getOrDefault(false)) "已删除 ${f.name}" else "删除失败：${f.name}")
        showExportList()
    }
}
