import com.watchdl.core.*

/** 番茄全链路活体自检：搜索 → 目录 → 正文。真联网。 */
fun main(args: Array<String>) {
    val kw = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "斗破苍穹"
    var failed = 0
    val dev = DeviceRegister.get()
    val fq = FanqieApp(dev)

    println("== 番茄 APP 接口 ==")
    val hits = runCatching { fq.search(kw, 1) }.getOrElse { e ->
        println("  搜索 FAIL: ${e.message}"); failed++; emptyList()
    }
    // 书名不能带 HTML 标签：搜索接口返回的是 <em> 高亮片段。
    val dirty = hits.count { it.name.contains('<') || it.author.contains('<') }
    println("  书名清洗 %s 带标签的 %d 条".format(if (dirty == 0) "OK  " else "FAIL", dirty))
    if (dirty > 0) failed++
    println("  搜索 %s 条数=%d %s".format(
        if (hits.size >= 3) "OK  " else "FAIL", hits.size,
        hits.take(2).joinToString(" / ") { "${it.name}·${it.author}" }))
    if (hits.size < 3) failed++

    val hit = hits.firstOrNull()
    if (hit != null) {
        val toc = runCatching { fq.toc(hit.bookId, hit.name, hit.author) }.getOrElse { e ->
            println("  目录 FAIL: ${e.message}"); failed++; null
        }
        if (toc != null) {
            println("  目录 %s %d 章  前3: %s".format(
                if (toc.chapters.size > 10) "OK  " else "FAIL", toc.chapters.size,
                toc.chapters.take(3).joinToString(" / ") { it.title.take(10) }))
            if (toc.chapters.size <= 10) failed++

            val ch = toc.chapters.firstOrNull { !it.locked }
            if (ch != null) {
                val t0 = System.currentTimeMillis()
                val body = runCatching { fq.content(ch.itemId, hit.bookId) }.getOrElse { e ->
                    println("  正文 FAIL: ${e.message}"); failed++; ""
                }
                val ms = System.currentTimeMillis() - t0
                val txt = Downloader.htmlToText(body)
                println("  正文 %s %d 字，%d ms".format(
                    if (txt.length > 200) "OK  " else "FAIL", txt.length, ms))
                if (txt.length <= 200) failed++
            }
        }
    }

    if (failed > 0) { println("\n番茄自检：$failed 项失败"); kotlin.system.exitProcess(1) }
    println("\n番茄自检：全部通过")
}
