import com.watchdl.core.*
import java.io.File
import java.security.MessageDigest

/** 单源独立进程：由 SourceAcceptance.py 限制墙钟时间，实际运行 LegadoSource。 */
fun main(args: Array<String>) {
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
    var stage = "import"
    fun emit(status: String, detail: String, extra: String = "") {
        println("ACCEPTANCE {\"status\":${q(status)},\"stage\":${q(stage)},\"detail\":${q(detail)}$extra}")
    }
    try {
        val index = args[1].toInt()
        @Suppress("UNCHECKED_CAST")
        val map = (JsonParser.parse(File(args[0]).readText()) as List<*>)[index] as Map<String, Any?>
        val imported = LegadoImport.importOne(map["bookSourceName"].toString(), map)
        val rule = imported.rule ?: run { emit("import_unsupported", imported.issue.orEmpty()); return }
        val source = LegadoSource.of(rule)
        val failures = mutableListOf<String>()
        for (kw in listOf("斗破苍穹", "凡人修仙传", "三体")) {
            stage = "search"
            val hits = source.search(kw).take(2)
            for (hit in hits) {
                try {
                    stage = "toc"
                    val toc = source.open(hit)
                    if (toc.truncated || toc.chapters.size < 3) { failures += "toc:${toc.chapters.size},truncated=${toc.truncated}"; continue }
                    val picks = listOf(0, toc.chapters.size / 2, toc.chapters.size - 1)
                    val texts = mutableListOf<String>()
                    val counts = mutableListOf<Int>()
                    val hashes = mutableListOf<String>()
                    stage = "content"
                    var invalid = false
                    for (p in picks) {
                        val text = source.content(toc.chapters[p].itemId, hit.bookId).replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), "")
                        val chinese = text.filter { it in '\u4e00'..'\u9fff' }
                        if (chinese.length < 300 || chinese.toSet().size < 80 || Regex("访问过于频繁|验证您是人类|请完成验证|AccessDenied|Justamoment|章节内容不存在|本章尚未更新|请先登录后阅读", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                            failures += "content:${chinese.length}"; invalid = true; break
                        }
                        if (texts.any { old -> old == chinese || (old.length > 500 && chinese.length > 500 && old.take(500) == chinese.take(500)) }) { failures += "duplicate"; invalid = true; break }
                        texts += chinese; counts += chinese.length
                        hashes += MessageDigest.getInstance("SHA-256").digest(chinese.toByteArray()).joinToString("") { "%02x".format(it) }
                    }
                    if (!invalid && texts.size == 3) {
                        emit("passed", "搜索、非截断目录及前中后三章通过", ",\"keyword\":${q(kw)},\"book\":${q(hit.name)},\"bookId\":${q(hit.bookId)},\"tocCount\":${toc.chapters.size},\"truncated\":false,\"positions\":${picks},\"chineseCounts\":${counts},\"hashes\":[${hashes.joinToString(",") { q(it) }}],\"chapterUrls\":[${picks.joinToString(",") { q(toc.chapters[it].itemId) }}]")
                        return
                    }
                } catch (e: Exception) { failures += "$stage:${e.javaClass.simpleName}:${e.message?.take(180)}" }
            }
        }
        emit("not_passed", failures.joinToString(" | ").ifEmpty { "三个关键词无搜索结果" })
    } catch (e: Throwable) {
        val detail = "${e.javaClass.simpleName}:${e.message?.take(300)}"
        emit(if (Regex("syntax|EvaluatorException|Unexpected token|缺少|不支持", RegexOption.IGNORE_CASE).containsMatchIn(detail)) "syntax_unsupported" else "exception", detail)
    }
}
