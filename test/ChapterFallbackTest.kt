import com.watchdl.core.*

/**
 * 补章自检：主源失败 → 备源补上、无候选、正文过短、章名归一化、候选上限、取消。
 *
 * 全部用假源，不碰网络。夹具正文必须过 [Downloader.MIN_BODY_CHARS]：
 * 用 5 字假正文的话，「正文过短」这道门永远测不到。
 */
private fun body(s: String) = "<p>$s" + "正文填充".repeat(100) + "</p>"

/** 假源。搜索回 [hits]、目录回 [chapters]、正文按章名查 [bodies]。 */
private class FakeSource(
    val name: String,
    private val hits: List<BookHit> = emptyList(),
    private val chapters: List<String> = emptyList(),
    private val bodies: Map<String, String> = emptyMap(),
    private val tocFails: Boolean = false,
) : BookSource {
    var searches = 0
    var tocs = 0
    var contents = 0

    override fun search(keyword: String, page: Int): List<BookHit> {
        searches++
        return hits.map { it.copy(sourceName = name) }
    }

    override fun toc(bookId: String, name: String, author: String): BookInfo {
        tocs++
        if (tocFails) error("目录取不到")
        return BookInfo(bookId, name, author,
            chapters.mapIndexed { i, t -> ChapterRef("$name#$i", t) })
    }

    override fun open(hit: BookHit): BookInfo = toc(hit.bookId, hit.name, hit.author)

    override fun content(itemId: String, bookId: String): String {
        contents++
        val idx = itemId.substringAfterLast('#').toIntOrNull() ?: error("坏 itemId：$itemId")
        val title = chapters.getOrNull(idx) ?: error("越界：$itemId")
        return bodies[title] ?: error("没有正文：$title")
    }
}

fun main() {
    var failures = 0
    fun check(n: String, ok: Boolean, d: String = "") {
        if (ok) println("  ✓ $n") else { failures++; println("  ✗ $n  $d") }
    }

    val hit = BookHit("b1", "斗破苍穹", "天蚕土豆", "")
    val want = "第1章 风起"
    val good = body("风起")

    fun fbOf(
        vararg sources: FakeSource,
        skip: String = "本书源",
        bookName: String = "斗破苍穹",
        author: String = "天蚕土豆",
        cancelled: () -> Boolean = { false },
    ): ChapterFallback {
        val named = sources.map { MultiSource.NamedSource(it.name, it, "https://${it.name}.test") }
        return ChapterFallback(
            multi = MultiSource(named, concurrency = 2),
            bookName = bookName,
            author = author,
            skipSourceName = skip,
            log = { println("    · $it") },
            isCancelled = cancelled,
        )
    }

    println("[ChapterFallback] 主源失败 → 备源补上")
    val backup = FakeSource("备源", listOf(hit), listOf("第1章 风起", "第2章 云涌"), mapOf(want to good))
    val fb = fbOf(backup)
    val got = fb.fetch(want)
    check("取到合格正文", got != null && Downloader.bodyChars(got) >= Downloader.MIN_BODY_CHARS)
    check("目录只取一次", backup.tocs == 1)
    check("目录里没有的章返回 null", fb.fetch("第999章 无") == null)
    check("候选只搜一次（第二、三章共用）", backup.searches == 1)

    println("[ChapterFallback] 跳过本书源")
    check("本书源不进候选", fbOf(backup, skip = "备源").fetch(want) == null)

    println("[ChapterFallback] 同一本书才算候选")
    val other = FakeSource("别站", listOf(BookHit("b2", "斗破苍穹外传", "天蚕土豆", "")),
        listOf(want), mapOf(want to good))
    check("书名不一致不补", fbOf(other).fetch(want) == null)
    val otherAuthor = FakeSource("伪站", listOf(BookHit("b3", "斗破苍穹", "别人", "")),
        listOf(want), mapOf(want to good))
    check("作者不一致不补", fbOf(otherAuthor).fetch(want) == null)

    println("[ChapterFallback] 正文合格线")
    val short = FakeSource("短站", listOf(hit), listOf(want), mapOf(want to "<p>仅 21 字</p>"))
    check("正文过短 → null", fbOf(short).fetch(want) == null)

    println("[ChapterFallback] 章名归一化")
    val looseTitle = "第 12 章 风起"
    val loose = FakeSource("宽站", listOf(hit), listOf(looseTitle), mapOf(looseTitle to good))
    check("「第12章风起」命中「第 12 章 风起」", fbOf(loose).fetch("第12章风起") != null)

    println("[ChapterFallback] 无候选")
    val empty = FakeSource("空站")
    val fbEmpty = fbOf(empty)
    check("无候选 → null", fbEmpty.fetch(want) == null)
    check("空结果也只搜一次", empty.searches == 1 && fbEmpty.fetch(want) == null && empty.searches == 1)

    println("[ChapterFallback] 坏源不抛异常")
    val broken = FakeSource("坏站", listOf(hit), listOf(want), mapOf(want to good), tocFails = true)
    check("目录取不到也只是 null", fbOf(broken).fetch(want) == null)

    println("[ChapterFallback] 取消")
    val cancelSrc = FakeSource("甲", listOf(hit), listOf(want), mapOf(want to good))
    check("取消后直接 null", fbOf(cancelSrc, cancelled = { true }).fetch(want) == null)
    check("取消时不发搜索", cancelSrc.searches == 0)

    println("[ChapterFallback] 候选上限")
    // 四个源都没有这一章：能看出到底碰了几个源。
    val four = (1..4).map { i -> FakeSource("源$i", listOf(hit), listOf("第9章 别"), emptyMap()) }
    val fbCap = fbOf(*four.toTypedArray())
    check("补不到 → null", fbCap.fetch(want) == null)
    check("候选上限 3：只碰 3 个源", four.count { it.tocs == 1 } == 3, four.joinToString { "${it.name}=${it.tocs}" })

    println("[ChapterFallback] 章节匹配：宁可补不到，不可补错")
    val mi: (List<String>, String) -> Int = { ts, w -> ChapterFallback.matchIndex(ts, w) }
    check("完全相同 → 命中", mi(listOf("第1章 风起", "第2章 云涌"), "第1章 风起") == 0)
    check("双向包含且同号 → 命中", mi(listOf("第3章 出发前"), "第3章 出发") == 0)
    check("双向包含但章号不同 → -1", mi(listOf("第4章 出发"), "第3章 出发") == -1)
    check("中文式章号等值 → 命中", mi(listOf("第十二章 风起"), "第12章 风起") == 0)
    check("同号带空格 → 命中", mi(listOf("第 12 章 风起"), "第12章风起") == 0)
    check("无章号：归一化全等 → 命中", mi(listOf("序章：出发"), "序章 出发") == 0)
    check("无章号：双向包含不算 → -1", mi(listOf("序章 出发之前"), "序章 出发") == -1)
    check("无章号遇到有章号 → -1", mi(listOf("第3章 出发"), "出发") == -1)
    check("同号多义 → -1", mi(listOf("第3章 出发（上）", "第3章 出发（下）"), "第3章 出发") == -1)
    check("同号多义但一条标题体全等 → 取该条",
        mi(listOf("第3章 出发（上）", "第3章 出发", "第3章 出发（下）"), "第3章 出发") == 1)
    check("英文大小写不同 → -1（已知限制）", mi(listOf("Chapter 5 The End"), "chapter 5 the end") == -1)
    check("数字加点章号 → 命中", mi(listOf("12. 风起"), "第12章 风起") == 0)
    check("空标题 → -1", mi(listOf("第1章 风起"), "  ") == -1)

    println("[ChapterFallback] 补章只用全等匹配，不靠近似取名")
    // 备源目录里只有「第4章 出发」，要补的是「第3章 出发」：章号不同就不能拿。
    val nearMiss = FakeSource("近站", listOf(hit), listOf("第4章 出发"),
        mapOf("第4章 出发" to body("出发")))
    check("章号不同的同名章不补 → null", fbOf(nearMiss).fetch("第3章 出发") == null)
    check("不匹配就不取正文", nearMiss.contents == 0)

    if (failures == 0) println("ChapterFallbackTest 全部通过")
    else { println("ChapterFallbackTest 失败 $failures 项"); kotlin.system.exitProcess(1) }
}
