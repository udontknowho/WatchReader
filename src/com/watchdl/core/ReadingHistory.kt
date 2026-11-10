package com.watchdl.core

import java.io.File
import java.util.Base64

/** 保存位置事件，不估算阅读时长。旧偏好由界面回退显示，不伪造事件。 */
data class ReadingEvent(val id: Long, val bookId: String, val index: Int, val at: Long,
    val readingId: String? = null)

object ReadingHistory {
    private const val NAME = "reading-history.tsv"
    private const val CAP = 500

    @Synchronized
    fun list(dir: File): List<ReadingEvent> {
        val f = File(dir, NAME)
        if (!f.isFile) return emptyList()
        return f.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    val p = line.split('\t')
                    require(p.size == 4 || p.size == 5)
                    ReadingEvent(p[0].toLong(), String(Base64.getUrlDecoder().decode(p[1]), Charsets.UTF_8),
                        p[2].toInt(), p[3].toLong(), p.getOrNull(4)?.takeIf { it.isNotBlank() }?.let {
                            String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8)
                        }).also {
                        require(it.id > 0 && it.bookId.isNotBlank() && it.index >= 0 && it.at > 0)
                    }
                }.getOrNull()
            }.toList().takeLast(CAP)
        }
    }

    /** 连续同书同章五分钟内合并，设置重画或生命周期重复保存不刷屏。 */
    @Synchronized
    fun record(dir: File, bookId: String, index: Int, at: Long = System.currentTimeMillis(), readingId: String? = null) {
        if (bookId.isBlank() || index < 0 || at <= 0) return
        val rows = list(dir).toMutableList()
        val last = rows.lastOrNull()
        if (last != null && last.bookId == bookId && last.readingId == readingId && last.index == index && at - last.at in 0..300_000) return
        val id = maxOf(at, (rows.maxOfOrNull { it.id } ?: 0L) + 1L)
        rows += ReadingEvent(id, bookId, index, at, readingId)
        save(dir, rows.takeLast(CAP))
    }

    fun record(dir: File, info: BookInfo, index: Int, at: Long = System.currentTimeMillis()) =
        record(dir, info.bookId, index, at, readingId(info))

    @Synchronized
    fun remove(dir: File, ids: Set<Long>) = save(dir, list(dir).filter { it.id !in ids })

    @Synchronized
    fun removeBooks(dir: File, books: Set<String>) = save(dir, list(dir).filter { it.bookId !in books })

    private fun save(dir: File, rows: List<ReadingEvent>) {
        dir.mkdirs()
        val target = File(dir, NAME)
        val tmp = File(dir, "$NAME.tmp")
        try {
            tmp.outputStream().use { stream ->
                val text = rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n") {
                    val book = Base64.getUrlEncoder().withoutPadding().encodeToString(it.bookId.toByteArray(Charsets.UTF_8))
                    val identity = it.readingId?.let { id -> "\t" + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(id.toByteArray(Charsets.UTF_8)) }.orEmpty()
                    "${it.id}\t$book\t${it.index}\t${it.at}$identity"
                }
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(tmp.renameTo(target)) { "阅读历史写入失败" }
        } finally { tmp.delete() }
    }
}
