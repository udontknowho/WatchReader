package com.watchdl.ui.my

import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.Screen
import com.watchdl.core.TinyJson
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件管理（#27）：私有目录的浏览、占用与删除。
 *
 * 形态照 legado `FileManageActivity`：点目录进入、返回箭头逐级回退、
 * 目录排在文件前且各自按名称排、点文件看内容。legado 点文件走 `openFileUri`
 * 交给系统应用打开，手表上没有能开 json 的应用，这里改成屏内联预览。
 *
 * 与 legado 有意不同的四处：
 * - 顶层只列四个写死路径的目录加 filesDir 下散文件，不列全部（这一页每行都配删，
 *   多列一行就多一个用户看不懂也不敢碰的东西）；进了目录才全列。
 * - legado 的面包屑行可点跳级，这里省掉那 24 dp，路径写进顶栏副标题，
 *   回退靠返回箭头逐级（189×248 的屏，一行副标题比一条横滚面包屑便宜）。
 * - legado 顶栏有搜索框过滤文件名，这里没有：189 dp 宽上弹软键盘遮掉半屏，
 *   而一层目录最多十来行，滚一眼就到底。
 * - 删除仍是行右可见的 ✕ 加二次确认，不照 legado 的长按弹菜单：手表长按没有
 *   可见提示，藏起来的删除等于找不到，而误删 books/ 的代价是整架书重下。
 */

/** 取用处写死路径的四个目录：下载作业、导出、书源、番茄设备注册。 */
private val MANAGED_DIRS = listOf("books", "export", "sources", "fangqie")

/**
 * 当前所在目录（相对 filesDir，空串是根）。
 *
 * 不放进 `Screen`：`Files` 是 object，加字段要连带改 `screenId` 与恢复逻辑；
 * 而这一页的层级只是浏览位置，进程被回收后回到根即可接受。
 */
private var curPath = ""

/** 正在屏上预览的文件；null 表示看列表。 */
private var previewFile: File? = null

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
    // 目录可能在上次进来之后被别处删掉（导出屏没有，但换源重下会重建 books/）：
    // 落在不存在的目录上会显示成「空目录」，看着像文件都没了。
    if (curPath.isNotEmpty() && !File(filesDir, curPath).isDirectory) curPath = ""

    val pv = previewFile
    setScreen(Screen.Files)
    if (pv != null) {
        renderTextPreview(pv) {
            previewFile = null
            showFiles()
        }
        return
    }

    root.removeAllViews()
    // 顶栏标题写当前目录名，副标题写完整路径 —— legado 的面包屑换成这两行。
    root.addView(Widgets.topBar(
        ctx = this,
        title = if (curPath.isEmpty()) "文件管理" else curPath.substringAfterLast('/'),
        subtitle = if (curPath.isEmpty()) null else "私有目录 / $curPath",
        onBack = { filesGoUp() },
    ))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    // 先给个占位：统计要递归整棵子树，慢的时候屏幕上不能是空白。
    col.addView(Widgets.emptyState(this, "grid", "正在统计…"))
    root.addView(body, Md3.lpWeight(1f))

    // 递归求和在主线程会把界面冻住一两秒（books/ 下每本书几十上百个文件）。
    bg(Screen.Files, body = { scanFileRows() }) { r ->
        renderFileRows(col, r.getOrDefault(emptyList()))
    }
}

/** 返回箭头：非根回上级，到根才离开本屏。legado 的返回键同一语义。 */
private fun MainActivity.filesGoUp() {
    if (curPath.isEmpty()) {
        showMy()
        return
    }
    curPath = if (curPath.contains('/')) curPath.substringBeforeLast('/') else ""
    showFiles()
}

/** 进入子目录。 */
private fun MainActivity.enterDir(row: FileRow) {
    curPath = relPath(row.file)
    showFiles()
}

/** filesDir 起的相对路径，跨进程重启也能用。 */
private fun MainActivity.relPath(f: File): String =
    f.absolutePath.removePrefix(filesDir.absolutePath).trimStart('/')

/** 统计一屏要显示的行。只在后台线程调用。 */
private fun MainActivity.scanFileRows(): List<FileRow> {
    if (curPath.isNotEmpty()) {
        val dir = File(filesDir, curPath)
        // 排序照 legado `FileManageViewModel.upFiles`：目录在前，同组按名称。
        return dir.listFiles()
            ?.sortedWith(compareBy({ it.isFile }, { it.name }))
            ?.map { it.toRow() }
            ?: emptyList()
    }
    // 顶层：四个写死目录（不存在也列一行 0 —— 少一行会让人以为路径已经改到别处）
    // 加 filesDir 直接放的普通文件（dev.log、bookmarks.json 等）。
    val out = mutableListOf<FileRow>()
    MANAGED_DIRS.map { File(filesDir, it) }.sortedBy { it.name }.forEach { out += it.toRow() }
    filesDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { out += it.toRow() }
    return out
}

private fun File.toRow(): FileRow = if (isDirectory) {
    FileRow("$name/", this, true, fileCount(this), fileBytes(this), lastModified())
} else {
    FileRow(name, this, false, 1, length(), lastModified())
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
 * 画两段：目录 / 文件。段标题自带数量与总量。
 *
 * 不在顶上另起一行「共 N 项 · X」，也不在末尾补「合计」：屏只有 248 dp 高，
 * 顶栏之外能看的没几行，三行统计会把最后一行挤出屏（缓存管理页同一取舍）。
 */
private fun MainActivity.renderFileRows(col: LinearLayout, rows: List<FileRow>) {
    col.removeAllViews()
    val dirs = rows.filter { it.isDir }
    val files = rows.filter { !it.isDir }

    if (dirs.isNotEmpty()) {
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
                    // 点行进目录：legado 同一处点按就是进入下一层。
                    onClick = { enterDir(row) },
                ))
            }
        })
    }

    col.addView(Widgets.groupCard(this,
        "${if (curPath.isEmpty()) "根目录文件" else "文件"} ${files.size} 个 · ${humanBytes(files.sumOf { it.bytes })}"
    ) { g ->
        if (files.isEmpty()) {
            g.addView(Widgets.emptyState(this, if (dirs.isEmpty()) "folder" else "file",
                if (dirs.isEmpty()) "这个目录是空的。" else "这一层没有文件，点上面的目录继续进。"))
        }
        for (row in files) {
            g.addView(Widgets.groupItem(
                ctx = this,
                title = row.name,
                subtitle = "${humanBytes(row.bytes)} · ${stamp(row.modified)}",
                glyph = "file",
                trailing = "✕",
                onTrailingClick = { askDeleteFile(row) },
                // legado 点文件是交给系统应用打开；手表上没有能开 json 的应用，
                // 能当文本读的就在屏上读，其余明确说不能预览。
                onClick = {
                    if (row.file.canPreview()) {
                        previewFile = row.file
                        showFiles()
                    } else {
                        toast("${row.name} 不能在屏上预览")
                    }
                },
            ))
        }
    })
}

// ══════════════════ 屏内文本预览 ══════════════════

/** 预览最多读这么多字符：books/ 下的 chapters.jsonl 是几 MB，整份读进来会卡。 */
private const val PREVIEW_CHARS = 3000

/** 能当文本读的扩展名。legado 交给系统应用打开任意类型，我们只认这些。 */
private val TEXT_EXT = setOf(
    "txt", "json", "jsonl", "log", "md", "csv", "xml", "html", "htm",
    "ini", "conf", "properties", "yaml", "yml",
)

private fun File.canPreview(): Boolean = extension.lowercase(Locale.US) in TEXT_EXT

/** 一次预览的结果。[cut] 表示正文被截断（只给了前若干行）。 */
private class PreviewText(val text: String, val bytes: Long, val lines: Int, val cut: Boolean)

/**
 * 读文件开头若干行。只在后台线程调用。
 *
 * 到量就停：几 MB 的 chapters.jsonl 全读进来只为看头几行，是白烧内存。
 */
private fun readPreview(f: File): PreviewText {
    val bytes = runCatching { f.length() }.getOrDefault(0L)
    val sb = StringBuilder()
    var lines = 0
    var cut = false
    runCatching {
        f.bufferedReader().use { br ->
            while (true) {
                val line = br.readLine() ?: break
                if (sb.length >= PREVIEW_CHARS) {
                    cut = true
                    break
                }
                // 单行本身就可能超上限：sources/ 下的书源是压成一行的 JSON。
                // 整行塞进 TextView 要按上千字算折行，排版会明显卡顿，所以按字符截。
                if (line.length > PREVIEW_CHARS) {
                    sb.append(line, 0, PREVIEW_CHARS)
                    cut = true
                    lines++
                    break
                }
                sb.append(line).append('\n')
                lines++
            }
        }
    }
    return PreviewText(sb.toString(), bytes, lines, cut)
}

/**
 * 整页文本预览。文件页用它看自己写的文件（json / jsonl / log 等）。
 *
 * legado 的做法是把文件交给系统应用打开（`openFileUri`），手表上没有这样的应用，
 * 只能自己画。@param onBack 回到来处（文件列表）。
 */
internal fun MainActivity.renderTextPreview(f: File, onBack: () -> Unit) {
    root.removeAllViews()
    root.addView(Widgets.topBar(this, f.name, onBack = { onBack() }))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    col.addView(Widgets.emptyState(this, "file", "正在读取…"))
    root.addView(body, Md3.lpWeight(1f))

    bg(curScreen, body = { readPreview(f) }) { r ->
        col.removeAllViews()
        val p = r.getOrNull()
        if (p == null) {
            col.addView(Widgets.emptyState(this, "warning",
                "读不出来：${r.exceptionOrNull()?.message?.take(40) ?: "未知原因"}"))
            return@bg
        }
        val head = humanBytes(p.bytes) + " · " +
            (if (p.cut) "已截断，只显示开头" else "${p.lines} 行")
        col.addView(Widgets.groupCard(this, head) { g ->
            g.addView(Md3.text(this, p.text.ifBlank { "（空文件）" }, Md3.FS_MD, Md3.onSurface)
                .apply {
                    setLineSpacing(Md3.dp(this@renderTextPreview, 3).toFloat(), 1f)
                    setPadding(Md3.dp(this@renderTextPreview, Md3.PAD), Md3.dp(this@renderTextPreview, Md3.GAP),
                        Md3.dp(this@renderTextPreview, Md3.PAD), Md3.dp(this@renderTextPreview, Md3.GAP))
                })
        })
    }
}

// ══════════════════ 删除 ══════════════════

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
    Widgets.md3Dialog(this,
        title = "删除 ${row.name}",
        body = "会删掉$what，共 ${row.bytes} 字节（${humanBytes(row.bytes)}）。${extra}\n删掉就找不回来。",
        confirm = "删除",
        onConfirm = { deleteFileRow(row) },
    ).show()
}

/**
 * 目录若是一本作业（books/ 下带 job.json 的一级目录），就按作业删。
 *
 * @return true 表示已经交给 [purgeBookCache]，调用方不要再自己删。
 *         读不出 bookId 时退回普通删除：那是已进不书架的孤儿目录，删了不丢书。
 */
private fun MainActivity.purgeBookDir(row: FileRow): Boolean {
    if (row.file.parentFile?.absolutePath != File(filesDir, "books").absolutePath) return false
    val raw = runCatching {
        File(row.file, "job.json").takeIf { it.isFile }?.readText()
            ?: File(row.file, "job.json.tmp").takeIf { it.isFile }?.readText()
    }.getOrNull() ?: return false
    val bookId = TinyJson.path(raw, "bookId")
    if (bookId.isNullOrBlank()) return false
    purgeBookCache(listOf(bookId)) { gone ->
        toast(if (gone > 0) "已删除 ${row.name}" else "删除失败：${row.name}")
        showFiles()
    }
    return true
}

/** 落盘删除放后台：books/ 下几百个文件，主线程删会卡住界面。 */
private fun MainActivity.deleteFileRow(row: FileRow) {
    // books/ 下一级目录就是一本作业。裸删目录会被后台线程建回来（书「诈尸」）：
    // 跑着的作业每章落盘一次，目录连同 job.json 一起重新长出来。
    // 所以先试走整本删除的公用路径：取消服务 → 踢队列 → 落墓碑 → 再删目录。
    if (row.isDir && purgeBookDir(row)) return
    // 顶层四个目录的路径在下载、导出、书架三处写死，目录本身要留着；
    // 里面更深的子目录没有别人依赖，整个删掉，省得留一堆空壳。
    val keepDir = row.isDir && row.file.parentFile == filesDir &&
        MANAGED_DIRS.any { it == row.file.name }
    bg(Screen.Files, body = {
        runCatching {
            if (row.isDir) {
                row.file.listFiles()?.forEach { it.deleteRecursively() }
                if (keepDir) row.file.mkdirs() else row.file.deleteRecursively()
            } else {
                row.file.delete()
            }
        }.getOrDefault(false)
    }) { r ->
        toast(if (r.getOrDefault(false)) "已删除 ${row.name}" else "删除失败：${row.name}")
        showFiles()
    }
}
