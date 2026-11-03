package com.watchdl.ui.my

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
 *
 * **legado 没有这一页**（`ui/` 下没有书单导出屏；`BookshelfManageScreen` 的导出 sheet 导出的是
 * 书籍正文与插图，不是清单）。所以本页照 legado 导出流程的三段结构自查：
 * 「本次导出（N 本）」清单 →「导出」主操作 → 结果可打开核对。legado 导出后能点开文件查看，
 * 这里点文件行就是屏内预览，用同一枚 [renderTextPreview]。
 *
 * legado 导出 sheet 里那一堆开关（格式 / 编码 / 自定义字符集 / WebDav / 插图 / 并行）
 * 是给正文导出用的，一份书名清单没有格式可配，这里一个都不摆假开关。
 */

/** 生成的文件名前缀。判断「哪份是书单」只靠它，改名即视为用户自己的文件。 */
private const val PREFIX = "书单-"

/** 正在预览哪份书单；null 表示看列表。 */
private var previewFile: File? = null

/** 时间戳 → 「08-14 21:30」。副标题第一段，年月对「哪份是刚生成的」没帮助。 */
private fun stamp(ms: Long): String =
    if (ms <= 0L) "" else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

internal fun MainActivity.showExportList() {
    setScreen(Screen.ExportList)
    val pv = previewFile
    if (pv != null) {
        renderTextPreview(pv) {
            previewFile = null
            showExportList()
        }
        return
    }
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

    // 状态收成一行设置行（legado `SettingItem`：标题 + 描述）。
    // 原来这里是「段标题 + 整宽按钮 + 最近生成提示」三行，在 248 dp 高的屏上
    // 把导出目录的第一行挤出视口；按钮移去屏底之后，一行就够说清两头。
    val latest = files.firstOrNull { it.name.startsWith(PREFIX) }
    col.addView(Widgets.groupCard(this, "书单") { g ->
        g.addView(Widgets.groupItem(
            ctx = this,
            title = "书架 $count 本",
            subtitle = latest?.let { "最近生成 ${it.name} · ${stamp(it.lastModified())}" }
                ?: "还没导出过",
            glyph = "list",
        ))
    })

    col.addView(Widgets.groupCard(this, "导出目录 · ${files.size} 个") { g ->
        if (files.isEmpty()) {
            g.addView(Widgets.emptyState(this, "list", "导出目录还是空的。\n导出后会出现在这里。"))
        }
        for (f in files) {
            g.addView(Widgets.groupItem(
                ctx = this,
                title = f.name,
                subtitle = "${stamp(f.lastModified())} · ${humanBytes(f.length())}",
                glyph = "file",
                trailing = "✕",
                onTrailingClick = { askDeleteExport(f) },
                // 点开核对内容：导出完最想确认的就是「书都在里面」。
                onClick = {
                    previewFile = f
                    showExportList()
                },
            ))
        }
    })
    root.addView(body, Md3.lpWeight(1f))
    addExportBar()
}

/**
 * 屏底主操作：导出书单。
 *
 * legado 的主操作在 FAB 上，本项目 2026-11-02 拍板用屏底通栅按钮替代
 * （FAB 会压住列表末行）。放在滚区外面，生成完重画也不会跟着滚走。
 * 按钮文案照 legado 的动词（`export`「导出」）：屏名已经叫「导出书单」，
 * 同一件事在同一屏用两个词（生成 / 导出）会让人以为按钮干的是另一回事。
 */
private fun MainActivity.addExportBar() {
    val bar = Md3.row(this).apply {
        setPadding(Md3.dp(this@addExportBar, Md3.PAD), Md3.dp(this@addExportBar, Md3.GAP / 2),
            Md3.dp(this@addExportBar, Md3.PAD), Md3.dp(this@addExportBar, Md3.GAP))
    }
    bar.addView(Widgets.button(this, "导出书单", "filled") { generateExportList() },
        Md3.lpWeight(1f))
    root.addView(bar)
}

/**
 * 生成清单。逐本读 `job.json` 是几十次小文件 IO，主线程上做就是一次看得见的停顿，
 * 所以整段（含写盘）放后台，回来只重画。
 */
private fun MainActivity.generateExportList() {
    val store = jobsStore()
    val dir = exportDir()
    // 书架空着时照样能写出一份 0 行的文件，看着像导出成功了其实里面什么都没有。
    // legado 对空清单也是明确拒绝（`no_bookmarks_to_export`「没有可导出的书签」）。
    if (store.listBooks().isEmpty()) {
        toast("书架是空的，没有可导出的书")
        return
    }
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
            // 文案照 legado `book_source_export_failed`「导出失败：%1$s」。
            toast("导出失败：${r.exceptionOrNull()?.message?.take(40) ?: "未知原因"}")
        } else {
            toast("已导出 ${res.first} · ${res.third} 本")
            showExportList()      // 重画：状态行与文件列表都要跟着变
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
    Widgets.md3Dialog(this,
        title = "删除 ${f.name}",
        body = "会删掉 ${f.name}（${humanBytes(f.length())}）。\n删掉就找不回来。",
        confirm = "删除",
        onConfirm = { deleteExportFile(f) },
    ).show()
}

private fun MainActivity.deleteExportFile(f: File) {
    bg(Screen.ExportList, body = { runCatching { f.delete() }.getOrDefault(false) }) { r ->
        toast(if (r.getOrDefault(false)) "已删除 ${f.name}" else "删除失败：${f.name}")
        showExportList()
    }
}
