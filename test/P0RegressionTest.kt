/**
 * 产品测试报告里的三个 P0 回归自检。
 *
 * 每条断言都对应报告里的一个具体现象，防止再退回去。
 */
import com.watchdl.core.BookHit
import com.watchdl.core.JobStore
import com.watchdl.core.MultiSource
import java.io.File

fun main() {
    var bad = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("  ✓ $name")
        else { bad++; println("  ✗ $name${if (detail.isEmpty()) "" else " → $detail"}") }
    }

    // ══════ P0-1：搜索结果标题露出 <em> ══════
    println("[P0-1] 书名/作者的标签剥离")
    // dedupe 是私有的，借 searchDetailed 走不通（要网络），所以直接验 JobStore
    // 这边先把可测的剥离行为用一个假源跑一遍是不可能的 —— 改为验证净化本身。
    // 剥离逻辑在 MultiSource 私有方法里，用反射调用最省事。
    val ms = MultiSource(emptyList())
    val cleanM = MultiSource::class.java.getDeclaredMethod("clean", BookHit::class.java)
        .apply { isAccessible = true }
    fun clean(h: BookHit): BookHit = cleanM.invoke(ms, h) as BookHit

    val withEm = BookHit("1", "重生<em>1980</em>", "作者A", "")
    check("去 <em>", clean(withEm).name == "重生1980", clean(withEm).name)

    val withFont = BookHit("2", "重回<font color=red>1980</font>", "", "")
    check("去 <font>", clean(withFont).name == "重回1980", clean(withFont).name)

    val entity = BookHit("3", "斗破&amp;苍穹", "天蚕&amp;土豆", "")
    check("解 &amp;", clean(entity).name == "斗破&苍穹", clean(entity).name)
    check("作者也洗", clean(entity).author == "天蚕&土豆", clean(entity).author)

    val plain = BookHit("4", "普通书名", "普通作者", "")
    check("干净的不动", clean(plain).name == "普通书名" && clean(plain).author == "普通作者")

    val nested = BookHit("5", "《书》&lt;上&gt;", "", "")
    check("解 &lt;&gt;", clean(nested).name == "《书》<上>", clean(nested).name)

    // ══════ P0-3：bookId 未净化导致路径穿越 ══════
    println("[P0-3] bookId 目录名净化")
    check("URL 被压成单层并带摘要", JobStore.safeDirName("https://www.min-yuan.com/x").matches(Regex("[^/\\\\]+~[0-9a-f]{64}")),
        JobStore.safeDirName("https://www.min-yuan.com/x"))
    check("不再含斜杠", !JobStore.safeDirName("https://a.com/b").contains('/'))
    // 关键不是「不含 .. 这个子串」（`.._.._etc` 作为单层名字是安全的），
    // 而是「不含路径分隔符」—— 那才是穿越的前提。
    val escaped2 = JobStore.safeDirName("../../etc/passwd")
    check("不含路径分隔符", !escaped2.contains('/') && !escaped2.contains('\\'), escaped2)
    check("是单层名字", File(escaped2).parent == null, escaped2)
    check("纯点号有兜底", JobStore.safeDirName("..").isNotEmpty() && JobStore.safeDirName("..") != "..",
        JobStore.safeDirName(".."))
    check("空串有兜底", JobStore.safeDirName("").isNotEmpty())
    check("数字 id 原样保留", JobStore.safeDirName("7490785501058712088") == "7490785501058712088")
    check("长度有上限", JobStore.safeDirName("x".repeat(500)).length <= 80)

    // 真的写一个目录，确认没跑出根目录
    val root = File(System.getProperty("java.io.tmpdir"), "jobstore-" + ProcessHandle.current().pid())
        .apply { mkdirs() }
    val store = JobStore(root)
    store.writeJob("https://evil.com/../escape", """{"name":"测试"}""")
    val escaped = File(root.parentFile, "escape")
    check("没有写到根目录之外", !escaped.exists())
    check("job.json 落在 root 里面",
        root.walkTopDown().any { it.name == "job.json" } || File(root, "job.json").exists())
    check("能按同一 bookId 读回", store.readJob("https://evil.com/../escape") != null)

    // ══════ P0-3：只认含 job.json 的目录 ══════
    println("[P0-3] 最近下载只认真作业")
    val books = File(root, "books").apply { mkdirs() }
    File(books, "empty1").mkdirs()
    File(books, "export").mkdirs()
    File(books, "https:").mkdirs()
    val good = File(books, "goodbook").apply { mkdirs() }
    File(good, "job.json").writeText("""{"bookId":"goodbook","name":"真书"}""")

    // 调真实现 JobStore.listBooks。以前这里把 MainActivity 的过滤链复刻一遍
    // 再断言副本，真实现改了测试照样绿（N12）。
    val listed = JobStore(books).listBooks()
    check("只列出有 job.json 的", listed.size == 1, listed.toString())
    check("空目录被排除", listed.none { it.contains("empty1") }, listed.toString())
    check("export 被排除", listed.none { it.contains("export") }, listed.toString())

    sourceRowsCheck()
    bad += assetsMustNotClobberCloudCheck()

    println(if (bad == 0) "P0 回归自检通过" else "P0 回归失败 $bad 项")
    if (bad > 0) kotlin.system.exitProcess(1)
}

/**
 * assets 不得把云端已下发的源库盖回去。
 *
 * 踩过：`copyAsset` 只在长度相同时跳过，长度不同就无条件覆盖。
 * 于是云端已经拉到 v5（43 条）后，用户一进书源页就被包里的 v4（42 条）盖回去，
 * 永远停在内置版本。表现为“明明更新了，源数还是旧的”。
 */
private fun assetsMustNotClobberCloudCheck(): Int {
    println("[assets] 不覆盖云端下发的源库")
    var n = 0
    fun ck(name: String, cond: Boolean) {
        if (cond) println("  ✓ $name") else { n++; println("  ✗ $name") }
    }
    val f = java.io.File("src/com/watchdl/MainActivity.kt")
    if (!f.isFile) { println("  ✗ 找不到 MainActivity.kt"); return 1 }
    val src = f.readText()
    // 只锚函数名：重构会把 private 改成 internal，写死可见性会让自检误报。
    val i = src.indexOf(" fun extractAssets()")
    if (i < 0) { println("  ✗ 找不到 extractAssets"); return 1 }
    // 只取函数体：取一大段会把别处的 continue / FILE_NAME 也算进来，自检就抓不住回归了。
    val end = src.indexOf("\n    }", i)
    if (end < 0) { println("  ✗ extractAssets 没有结束括号"); return 1 }
    val body = src.substring(i, end)
    ck("extractAssets 里判断了 CloudSource.FILE_NAME", body.contains("CloudSource.FILE_NAME"))
    ck("云端文件已存在时跳过复制",
        body.contains("n == CloudSource.FILE_NAME") && body.contains("continue"))
    ck("不再无条件 copyAsset",
        !body.contains("copyAsset(\"sources/$n\", File(srcDir, n))"))
    return n
}

/**
 * 换源改造的回归：同名书按源各占一行，源名清洗，pickFor 精确匹配。
 * 单独一个 main 不行（一个文件只能有一个 main），所以并进本文件末尾。
 */
fun sourceRowsCheck() {
    var bad = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("  ✓ $name")
        else { bad++; println("  ✗ $name${if (detail.isEmpty()) "" else " → $detail"}") }
    }

    val ms = MultiSource(emptyList())
    val dedupeM = MultiSource::class.java
        .getDeclaredMethod("dedupe", List::class.java).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    fun dedupe(l: List<BookHit>): List<BookHit> = dedupeM.invoke(ms, l) as List<BookHit>

    println("[换源] 同名书按源各占一行")
    val rows = listOf(
        BookHit("u1", "斗破苍穹", "天蚕土豆", "", "松鹤阅读"),
        BookHit("u2", "斗破苍穹", "天蚕土豆", "", "七点小说"),
        BookHit("u3", "斗破苍穹", "天蚕土豆", "", "爱推书君"),
    )
    val out = dedupe(rows)
    check("三条都保留（不再合并）", out.size == 3, out.size.toString())
    check("源名各不相同", out.map { it.sourceName }.toSet().size == 3, out.map { it.sourceName }.toString())
    check("bookId 未被改动", out.map { it.bookId }.toSet() == setOf("u1", "u2", "u3"))

    println("[换源] 同一个源重复返回同一本书要去掉")
    val dup = dedupe(listOf(
        BookHit("a", "书A", "作者", "", "源X"),
        BookHit("b", "书A", "作者", "", "源X"),
    ))
    check("同源同名去重后 1 条", dup.size == 1, dup.size.toString())

    println("[换源] 源名清洗")
    val tidyM = MultiSource::class.java
        .getDeclaredMethod("tidySourceName", String::class.java).apply { isAccessible = true }
    fun tidy(s: String) = tidyM.invoke(ms, s) as String
    check("去前后空格", tidy("  ●QB  qq女榜") == "QB qq女榜", tidy("  ●QB  qq女榜"))
    check("压缩中间空白", tidy("a   b") == "a b", tidy("a   b"))
    check("空串安全", tidy("") == "", tidy(""))

    println(if (bad == 0) "换源自检通过" else "换源失败 $bad 项")
    if (bad > 0) kotlin.system.exitProcess(1)
}
