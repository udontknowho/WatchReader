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
        val all = read(dir)
        // 旧档每换一章一条：读的时候按书折叠，只留每本最新那条。
        // 不靠写入时的去重 —— 升级前已经堆下的行不会自己消失。
        val latest = LinkedHashMap<String, ReadingEvent>()
        for (e in all) {
            val k = readingKey(e.bookId, e.readingId)
            val old = latest[k]
            if (old == null || e.at > old.at) latest[k] = e
        }
        return latest.values.sortedBy { it.id }
    }

    private fun read(dir: File): List<ReadingEvent> {
        val f = File(dir, NAME)
        if (!f.isFile) return emptyList()
        // 只需尾部 CAP 行：滚动窗口边读边丢，不把整档堆进内存
        // （旧档每换章一行，几万行时整读再 takeLast 是白贺内存）。
        val tail = ArrayDeque<String>(CAP)
        f.bufferedReader().useLines { lines ->
            lines.forEach {
                tail.addLast(it)
                if (tail.size > CAP) tail.removeFirst()
            }
        }
        return tail.mapNotNull { line ->
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
        }
    }

    /**
     * 记一次阅读位置。
     *
     * 一本书只留一条：同书（同阅读身份）旧行直接替换，不再追加事件。
     * 以前每换一章就多一行，记录屏滚起来全是同一本书，看不出哪本是最近读的。
     */
    @Synchronized
    fun record(dir: File, bookId: String, index: Int, at: Long = System.currentTimeMillis(), readingId: String? = null) {
        if (bookId.isBlank() || index < 0 || at <= 0) return
        val rows = list(dir).toMutableList()
        val key = readingKey(bookId, readingId)
        val same = rows.indexOfFirst { readingKey(it.bookId, it.readingId) == key }
        // 同一本书同一章、五分钟内的重复保存不算新事件（设置重画、生命周期都会触发）。
        if (same >= 0) {
            val old = rows[same]
            if (old.index == index && at - old.at in 0..300_000) return
        }
        val id = maxOf(at, (rows.maxOfOrNull { it.id } ?: 0L) + 1L)
        val event = ReadingEvent(id, bookId, index, at, readingId)
        if (same >= 0) rows[same] = event else rows += event
        save(dir, rows.sortedBy { it.at }.takeLast(CAP))
    }

    /** 同一本书的判定键：有阅读身份就认身份，没有就退到书号。 */
    private fun readingKey(bookId: String, readingId: String?): String =
        readingId?.takeIf { it.isNotBlank() } ?: bookId

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
