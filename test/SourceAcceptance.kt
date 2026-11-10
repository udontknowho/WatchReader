import com.watchdl.core.*
import java.io.File
import java.security.MessageDigest

/** 明确的非正文条目仅从抽样候选中排除，完整目录及数量不变。 */
fun acceptanceAnnouncement(title: String): Boolean {
    val t = title.trim().replace(Regex("^[【\\[（(]|[】\\]）)]$"), "")
    // 有章节编号的标题即使提到公告也仍抽样，不误删叙事章节。
    if (Regex("第[零〇一二三四五六七八九十百千万两0-9]+[章回节]").containsMatchIn(t)) return false
    return Regex("^(?:公告|通知|请假(?:条)?|请假一天|停更通知|更新通知|上架感言|完本感言|完结感言|作者的话|致读者|关于更新)(?:[：:，,。！!\\s].*|$)").matches(t)
}

fun acceptancePositions(chapters: List<ChapterRef>): List<Int> {
    val eligible = chapters.indices.filter { !acceptanceAnnouncement(chapters[it].title) }
    if (eligible.size < 3) return emptyList()
    return listOf(eligible.first(), eligible[eligible.size / 2], eligible.last())
}

fun acceptanceTimeout(error: Throwable): Boolean = generateSequence(error) { it.cause }
    .take(12).any { it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException ||
        Regex("timeout|timed out|超时|预算", RegexOption.IGNORE_CASE).containsMatchIn(it.message.orEmpty()) }

/** 单源独立进程，由 Python 限制墙钟时间。超时仅表示本轮未证实可用。 */
fun main(args: Array<String>) {
    fun q(s: String) = "\"" + s.flatMap { c -> when (c) {
        '\\' -> "\\\\".toList(); '"' -> "\\\"".toList()
        else -> if (c.code < 32) "\\u%04x".format(c.code).toList() else listOf(c)
    } }.joinToString("") + "\""
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
        var timedOut = false
        var truncated = false
        fun record(e: Exception) {
            timedOut = timedOut || acceptanceTimeout(e)
            failures += "$stage:${e.javaClass.simpleName}:${e.message?.take(180)}"
        }
        for (kw in listOf("斗破苍穹", "凡人修仙传", "三体")) {
            stage = "search"
            val hits = try { source.search(kw).take(2) } catch (e: Exception) { record(e); continue }
            for (hit in hits) {
                try {
                    stage = "toc"
                    val toc = source.open(hit)
                    truncated = truncated || toc.truncated
                    val picks = acceptancePositions(toc.chapters)
                    if (toc.truncated || picks.size < 3) {
                        failures += "toc:${toc.chapters.size},truncated=${toc.truncated},samples=${picks.size}"
                        continue
                    }
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
                        if (texts.any { old -> old == chinese || (old.length > 500 && chinese.length > 500 && old.take(500) == chinese.take(500)) }) {
                            failures += "duplicate"; invalid = true; break
                        }
                        texts += chinese; counts += chinese.length
                        hashes += MessageDigest.getInstance("SHA-256").digest(chinese.toByteArray()).joinToString("") { "%02x".format(it) }
                    }
                    if (!invalid && texts.size == 3) {
                        emit("passed", "搜索、完整目录及首中末非公告正文通过", ",\"keyword\":${q(kw)},\"book\":${q(hit.name)},\"bookId\":${q(hit.bookId)},\"tocCount\":${toc.chapters.size},\"truncated\":false,\"positions\":${picks},\"chineseCounts\":${counts},\"hashes\":[${hashes.joinToString(",") { q(it) }}],\"chapterUrls\":[${picks.joinToString(",") { q(toc.chapters[it].itemId) }}]")
                        return
                    }
                } catch (e: Exception) { record(e) }
            }
        }
        emit(when { timedOut -> "inconclusive_timeout"; truncated -> "inconclusive_truncated"; else -> "not_passed" },
            failures.joinToString(" | ").ifEmpty { "三个关键词无搜索结果" })
    } catch (e: Throwable) {
        val detail = "${e.javaClass.simpleName}:${e.message?.take(300)}"
        emit(when {
            acceptanceTimeout(e) -> "inconclusive_timeout"
            Regex("syntax|EvaluatorException|Unexpected token|缺少|不支持", RegexOption.IGNORE_CASE).containsMatchIn(detail) -> "syntax_unsupported"
            else -> "exception"
        }, detail)
    }
}
