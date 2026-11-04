package com.watchdl.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 本地书籍导入（txt / epub）。
 *
 * 设计要点：**导入即落成一本普通作业**。分出来的每一章按既有格式写进
 * `books/<bookId>/chapters.jsonl`，目录写进 `job.json`。于是阅读屏、目录屏、
 * 书签、导出、缓存管理全都照旧工作 —— 不要在阅读链路里新开一条「本地书分支」，
 * 那条路一旦分叉，后面的功能都得写两遍。
 *
 * 本地书的身份靠 bookId 前缀 [PREFIX]（`local:`）认，网络书的 bookId 是链接或纯数字，
 * 两者不会撞。需要区别对待的地方散落在几处：不加到换源/搜索里、不要拿它去联网
 * 更新目录、不要交给下载服务。
 *
 * 纯 JVM，不碰 Android 类 —— 自检能直接跑 [splitTxt] / [epubChapters] / [importInto]。
 */
object LocalImport {

    /** 本地书的 bookId 前缀。 */
    const val PREFIX = "local:"

    /** 导入文件的大小上限。手表内存小，整本读进内存要有封顶（解码后还要再翻一倍）。 */
    const val MAX_BYTES: Long = 12L * 1024 * 1024

    /** epub 单个条目封顶。 */
    private const val ENTRY_CAP = 4 * 1024 * 1024

    /** epub 文本条目累计封顶。 */
    private const val MAX_EPUB_TEXT = 48L * 1024 * 1024

    /** 读最多 [cap] 字节，不把流整个吃进内存。 */
    private fun readCapped(ins: InputStream, cap: Int): ByteArray {
        val buf = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream(minOf(cap, 64 * 1024))
        var total = 0
        while (total < cap) {
            val n = ins.read(buf, 0, minOf(buf.size, cap - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** 是不是本地书。 */
    fun isLocal(bookId: String): Boolean = bookId.startsWith(PREFIX)

    /** 一章的纯文本。 */
    data class Chapter(val title: String, val body: String)

    /** 导入结果，给界面报数用。 */
    data class Result(
        val bookId: String,
        val name: String,
        val chapters: Int,
        /** 识别的容器格式：`TXT` / `EPUB`。 */
        val format: String,
    )

    // ══════════════════ 编码 ══════════════════

    /**
     * 字节 → 文本。
     *
     * 顺序：BOM（UTF-8 / UTF-16 两种端序）→ 严格 UTF-8 → GB18030 兜底。
     * 判据是替换符个数：GBK 的正文字节按 UTF-8 解几乎必然出 `\uFFFD`，
     * 反过来 UTF-8 按 GB18030 解也会出。两种都解一遍，取坏的少的那种。
     *
     * 不用 [CharsetFontDecoder]：那套是番茄私用区码位的字符表还原，
     * 与「文件是什么编码」是两件事，解决不了 GBK 文件。
     */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)

        // 无 BOM 的 UTF-16：单字节字符的高字节是 0，按 UTF-8 解会得到一串 \u0000，
        // 而替换符为 0，会被当成合法 UTF-8 —— 整本变成带洞的乱码书。
        // 只看替换符不够，还要看 0 字节密度（前 4KB 就够判了）。
        val head = bytes.copyOfRange(0, minOf(bytes.size, 4096))
        val oddZ = head.withIndex().count { (i, b) -> i % 2 == 1 && b == 0.toByte() }
        val evenZ = head.withIndex().count { (i, b) -> i % 2 == 0 && b == 0.toByte() }
        // 逐字节看奇偶位：UTF-16 的单字节字符把 0 全挤在一侧，GB18030 中文几乎没有 0。
        val utf16Le = head.size >= 16 && oddZ >= 4 && oddZ * 3 > evenZ
        val utf16Be = head.size >= 16 && evenZ >= 4 && evenZ * 3 > oddZ
        if (utf16Le || utf16Be) {
            // LE / BE 各解一次，哪种的控制字符少就用哪种。
            val cands = listOfNotNull(
                if (utf16Le) runCatching { String(bytes, Charsets.UTF_16LE) }.getOrNull() else null,
                if (utf16Be) runCatching { String(bytes, Charsets.UTF_16BE) }.getOrNull() else null,
            )
            cands.minByOrNull { s -> s.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' } }
                ?.let { return it.trim('\uFEFF') }
        }

        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '\uFFFD' }
        if (bad == 0) return utf8
        val gb = runCatching { String(bytes, charset("GB18030")) }.getOrNull() ?: return utf8
        return if (gb.count { it == '\uFFFD' } < bad) gb else utf8
    }

    // ══════════════════ txt 分章 ══════════════════

    /**
     * 章节标题判定。照 legado `TextFile.chapterNameMatcher` 的形状裁出这一版：
     * 行首少量空白 + 「第 N 章/节/回/卷/集/部/篇/幕」或序章/楔子/番外 之类，
     * 后面允许挂一句标题（最多 40 字）。
     *
     * 为什么不用 `contains("第") && contains("章")`：正文里出现「第三章」的句子极多，
     * 按包含判定会把一整段切碎。这里要求**整行**就是标题。
     */
    /**
     * 带编号的标题：`第12章 [标题]`、`第三章：少年`。
     *
     * 编号后面的标题**必须用空白或标点隔开**，或干脆没有标题。
     * 不这样卡的话，「第一章的少年时代」这种句子会被当成标题。
     */
    private val NUM_HEAD = Regex(
        "^第[ 　\\t]{0,6}[0-9０１２３４５６７８９零〇一二三四五六七八九十百千万两]{1,12}[ 　\\t]{0,6}[章回节卷集部篇幕]" +
            "(?:$|[ 　\\t：:·.、，,（(【［「]{1,4}[^\\n]{0,40}$)",
    )

    /**
     * 不编号的固定词：`序章 引子`、`楔子`、`番外 一`。
     *
     * 同样要求后面有空白或标点。早期版本允许直接接任意 40 字，结果
     * 「引子正文。」「正文。」都成了标题，把正文切得只剩空章 ——
     * 识别标题宁可漏一个，也不能把正文当标题吃掉。
     */
    private val WORD_HEAD = Regex(
        "^(?:序章|序言|楔子|引子|前言|后记|尾声|终章|完本感言|正文|番外(?:篇|章|[0-9０-９一二三四五六七八九十]{1,3})?)" +
            "(?:$|[ 　\\t：:·.、，,（(【［「]{1,4}[^\\n]{0,40}$)",
    )

    /** 英文标题：`Chapter 12 The End` / `CHAPTER 3` / `Chapter One` / `Chapter12`。 */
    private val EN_HEAD = Regex(
        "^(?i:chapter|part|book)[ 　\\t]{0,4}" +
            "(?:[0-9]{1,4}|[IVXLCivxlc]{1,6}|[A-Za-z]{1,10})" +
            "(?:[ 　.:：-]{0,3}[^\\n]{0,40})?$",
    )

    /** 这一行是不是章节标题。 */
    fun isChapterHead(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty() || s.length > 60) return false
        return NUM_HEAD.matches(s) || WORD_HEAD.matches(s) || EN_HEAD.matches(s)
    }

    /**
     * txt 全文 → 章节表。
     *
     * - 一个标题都没有：整篇算一章，标题叫「全书」。短篇与整本单章的书都走这条。
     * - 第一个标题之前的正文：够长（≥200 个有效字）才单独留一章「开篇」，
     *   否则当成书名页/免责声明丢掉。
     * - 标题行不进正文（阅读屏的顶栏已经显示章名，正文再重复一遍很碍眼）。
     * - 正文为空的章丢掉：很多 txt 把目录抄在正文前，那些「第N章 xxx」后面紧跟下一个标题。
     */
    fun splitTxt(text: String): List<Chapter> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) return emptyList()
        val lines = normalized.split('\n')
        val heads = ArrayList<Int>()
        for (i in lines.indices) if (isChapterHead(lines[i])) heads.add(i)
        if (heads.isEmpty()) return listOf(Chapter("全书", normalized.trim()))

        val out = ArrayList<Chapter>(heads.size + 1)
        val pre = lines.subList(0, heads[0]).joinToString("\n").trim()
        if (pre.count { it.isLetterOrDigit() } >= 200) out.add(Chapter("开篇", pre))
        for ((n, at) in heads.withIndex()) {
            val end = if (n + 1 < heads.size) heads[n + 1] else lines.size
            val title = lines[at].trim().take(60)
            val body = lines.subList(at + 1, end).joinToString("\n").trim()
            if (body.isNotEmpty()) out.add(Chapter(title, body))
        }
        // 全书只有一个空标题、正文全被当标题吃掉了 —— 退回整篇一章，宁可章名难看也不能空书。
        if (out.isEmpty()) return listOf(Chapter("全书", normalized.trim()))
        return out
    }

    // ══════════════════ epub ══════════════════

    /** epub 里值得读进内存的条目（图片、字体一律跳过：表盘上看不了图，还吃内存）。 */
    private fun isTextEntry(path: String): Boolean {
        val p = path.lowercase(Locale.US)
        return p.endsWith(".xml") || p.endsWith(".opf") || p.endsWith(".ncx") ||
            p.endsWith(".xhtml") || p.endsWith(".html") || p.endsWith(".htm")
    }

    /**
     * epub 字节 → 章节表。按 spine 顺序取 XHTML，转纯文本。
     *
     * 流程：`META-INF/container.xml` → rootfile（OPF）→ manifest 建 id→href → spine 的
     * itemref 顺序 → 逐个读 XHTML。没有 container.xml 时按扩展名找 `.opf` 兜底。
     *
     * 标题取 `<h1>`…`<h3>`（优先，书里通常就是这个），没有再用 `<title>`，
     * 都没有就写「第N章」。
     */
    fun epubChapters(bytes: ByteArray): List<Chapter> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                val path = e.name.replace('\\', '/')
                if (!isTextEntry(path) || entries.containsKey(path)) continue
                // 上限真的要有：坏包里一个巨型 xml 能把内存吃光（注释写了、代码没写）。
                if (total > MAX_EPUB_TEXT) continue
                val one = runCatching { readCapped(zis, ENTRY_CAP) }.getOrElse { ByteArray(0) }
                if (one.isEmpty()) continue
                total += one.size
                entries[path] = one
            }
        }
        if (entries.isEmpty()) throw IllegalStateException("EPUB 里没有文本文件")

        val container = entries.entries
            .firstOrNull { it.key.equals("META-INF/container.xml", ignoreCase = true) }?.value
        val fromContainer = container?.let { b ->
            Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(decodeText(b))?.groupValues?.get(1)
        }
        // container 里的 full-path 常写成 `./OEBPS/content.opf`，不归一化就查不到。
        val opfPath = fromContainer?.let { resolveHref("", it) }
            ?: entries.keys.firstOrNull { it.lowercase(Locale.US).endsWith(".opf") }
        // 没有 OPF 不是错：自制包很常见，直接按扩展名扫 XHTML。
        val opf = opfPath?.let { p ->
            (entries[p] ?: entries.entries
                .firstOrNull { it.key.equals(p, ignoreCase = true) }?.value)?.let { decodeText(it) }
        }
        val base = opfPath?.substringBeforeLast('/', "") ?: ""

        val manifest = HashMap<String, String>()
        for (m in Regex("<item\\b[^>]*>").findAll(opf ?: "")) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            manifest[id] = resolveHref(base, href)
        }
        val order = Regex("<itemref\\b[^>]*>").findAll(opf ?: "")
            .mapNotNull { attr(it.value, "idref") }
            .mapNotNull { manifest[it] }
            .toList()

        // 没有 spine 的包（自制 epub 常见）按文件名顺序把 XHTML 全读一遍。
        val reads = if (order.isNotEmpty()) order else entries.keys
            .filter { it.lowercase(Locale.US).let { p -> p.endsWith(".xhtml") || p.endsWith(".html") || p.endsWith(".htm") } }
            .sorted()

        val lowered = entries.entries.associateBy { it.key.lowercase(Locale.US) }
        val out = ArrayList<Chapter>(reads.size)
        var seq = 0
        for (path in reads) {
            val raw = entries[path] ?: lowered[path.lowercase(Locale.US)]?.value ?: continue
            val html = decodeText(raw)
            val body = Downloader.htmlToText(html)
            val title = epubTitle(html)
            // 导航页、版权页、只有一张图的页：正文少于 10 个有效字就跳过。
            if (body.count { it.isLetterOrDigit() } < 10) continue
            seq++
            out.add(Chapter(title ?: "第${seq}章", dropLeadingTitle(body, title)))
        }
        if (out.isEmpty()) throw IllegalStateException("EPUB 里没有可读的正文")
        return out
    }

    /** 正文首行与章节标题相同时删掉，否则阅读页顶栏与正文第一行重复。 */
    private fun dropLeadingTitle(body: String, title: String?): String {
        if (title.isNullOrBlank()) return body
        val nl = body.indexOf('\n')
        val firstLine = (if (nl < 0) body else body.substring(0, nl)).trim()
        if (firstLine.isEmpty()) return body
        val a = firstLine.replace(Regex("[\\s　]"), "")
        val b = title.replace(Regex("[\\s　]"), "")
        if (a != b && !a.startsWith(b)) return body
        return if (nl < 0) "" else body.substring(nl + 1).trimStart()
    }

    /** 一章的标题：`<h1>`–`<h3>` 优先，其次 `<title>`。 */
    private fun epubTitle(html: String): String? {
        val h = Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)?.groupValues?.get(1)
        val t = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
        for (cand in listOf(h, t)) {
            val s = plain(cand ?: continue)
            if (s.isNotBlank()) return s.take(60)
        }
        return null
    }

    /** 标签剥掉、实体还原、空白压成一个空格。标题只用它。 */
    private fun plain(s: String): String =
        Downloader.decodeEntities(s.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ").trim()

    /** 取标签属性值。 */
    private fun attr(tag: String, name: String): String? =
        Regex("(?i)\\b$name\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)

    /** href 相对 OPF 所在目录解析，处理 `..`、开头的 `/` 与 `%20` 之类的百分号转义。 */
    private fun resolveHref(base: String, href: String): String {
        val clean = percentDecode(href.substringBefore('#')).trim()
        val raw = when {
            clean.startsWith("/") -> clean.trimStart('/')
            base.isEmpty() -> clean
            else -> "$base/$clean"
        }
        val parts = ArrayList<String>()
        for (seg in raw.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        // 按 UTF-8 字节序列解，不能一个字节转一个 char：%E7%AC%AC 是「第」。
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    // ══════════════════ 落成一本作业 ══════════════════

    /**
     * 导入并落盘，返回新书的 bookId。
     *
     * 落盘顺序是**先正文后 job.json**：`job.json` 存在即在书架里可见，
     * 先写它的话中途断电会留下「有书没正文」的书。反过来崩溃只留一个不进书架的孤儿目录。
     *
     * 同名文件重复导入 = 覆盖同一本（bookId 由文件名推出）。这条要写进回报与笔记，
     * 否则用户会疑惑「为什么导第二本重名的没多出来」。
     */
    fun importInto(store: JobStore, fileName: String, bytes: ByteArray): Result {
        if (bytes.isEmpty()) throw IllegalArgumentException("文件是空的")
        if (bytes.size > MAX_BYTES) {
            throw IllegalArgumentException("文件太大（${bytes.size / 1024 / 1024}MB），上限 ${MAX_BYTES / 1024 / 1024}MB")
        }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val looksEpub = bytes.size > 4 && bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte() && ext != "txt"
        val format: String
        val chapters: List<Chapter>
        if (ext == "epub" || looksEpub) {
            format = "EPUB"
            chapters = epubChapters(bytes)
        } else {
            format = "TXT"
            chapters = splitTxt(decodeText(bytes))
        }
        if (chapters.isEmpty()) throw IllegalStateException("分不出任何章节，可能不是文本文件")

        val name = fileName.substringBeforeLast('.').trim().ifBlank { "本地书" }.take(60)
        // bookId 只取书名会撞：`三体.txt` 与 `三体.epub` 会落成同一个目录，静默覆盖。
        // 带上格式；字节数不能进 id —— 同名重导要落在同一本书上（覆盖旧正文），
        // 进了 id 就变成「每改一次文件多一本」。
        val bookId = PREFIX + JobStore.safeDirName(name) + "_" + format.lowercase(Locale.US)
        // 重导入：先清掉旧目录（同一本），否则正文会与旧章节混在一起。
        store.markDeleted(bookId)
        store.clearDeleted(bookId)

        val lines = chapters.mapIndexed { i, c ->
            "{\"itemId\":${Downloader.quote(itemId(i))},\"title\":${Downloader.quote(c.title)}," +
                "\"content\":${Downloader.quote(c.body)}}"
        }
        store.writeChapters(bookId, lines)

        val json = StringBuilder(64 + chapters.size * 96)
            .append("{\"bookId\":").append(Downloader.quote(bookId))
            .append(",\"name\":").append(Downloader.quote(name))
            .append(",\"author\":\"\"")
            // 书架封面左下角标签读 originName；空着会落到「本地」，这里显式写出来。
            .append(",\"originName\":\"本地\"")
            .append(",\"state\":\"DONE\",\"total\":").append(chapters.size)
            .append(",\"updatedAt\":").append(System.currentTimeMillis())
            .append(",\"chapters\":[")
            .toString()
        val chapterJson = chapters.mapIndexed { i, c ->
            "{\"itemId\":${Downloader.quote(itemId(i))},\"title\":${Downloader.quote(c.title)}," +
                "\"locked\":false,\"status\":\"DONE\"}"
        }.joinToString(",")
        store.writeJob(bookId, json + chapterJson + "]}", isCancelled = { false }, resume = { true })

        return Result(bookId, name, chapters.size, format)
    }

    /** 章 id。本地书只在自己这一本的目录里，序号就够，不必造 URL。 */
    private fun itemId(index: Int): String = "c" + (index + 1)
}
