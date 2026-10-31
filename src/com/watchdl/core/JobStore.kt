package com.watchdl.core

import java.io.File
import java.io.RandomAccessFile

/**
 * 下载任务的落盘状态。照抄 TND 的双写设计（见笔记本页 `tnd-download-engine`）：
 * - job.json  全量快照，原子写（tmp + rename），每组章节写完更新一次
 * - chapters.jsonl  逐章追加并 flush，崩溃最多丢一章
 * 恢复时以 jsonl 为准回填 job.json 里 content 为空的条目。
 *
 * 正文以 XHTML 原文落盘，导出时才转纯文本，换格式不用重下。
 */
class JobStore(private val root: File) {

    /** 目录路径。**不建目录**：读一个不存在的作业不该凭空造出空壳（见 [markDeleted]）。 */
    private fun dirOf(bookId: String): File = File(root, safeDirName(bookId))

    fun jobFile(bookId: String): File = File(dirOf(bookId), "job.json")
    fun chaptersFile(bookId: String): File = File(dirOf(bookId), "chapters.jsonl")

    /**
     * 删除作业，并留下墓碑。
     *
     * 报告 P0-2：以前直接 `deleteRecursively()` 就完了，可后台线程可能还在跑，
     * 它每章都会 persist 一次 —— 目录被删之后下一次落盘会连目录一起建回来，
     * 书就「复活」了。墓碑让后续的落盘直接丢弃，跑着的线程由调用方先取消。
     * 返回目录是否真的没了。
     */
    fun markDeleted(bookId: String): Boolean {
        deleted.add(bookId)
        val dir = synchronized(lockFor(bookId)) { dirOf(bookId).also { it.deleteRecursively() } }
        return !dir.exists()
    }

    /** 重新下载同一本书时撤销墓碑。 */
    fun clearDeleted(bookId: String) { deleted.remove(bookId) }

    private fun lockFor(bookId: String): Any =
        locks.computeIfAbsent(jobFile(bookId).canonicalPath) { Any() }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /** 已删除的作业（P0-2）。进程重启后墓碑自然清空：那时目录早没了，也没线程在写。 */
        private val deleted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        /**
         * 把 bookId 变成安全的单层目录名。
         *
         * **不是美化，是安全边界**。bookId 来自书源返回的链接，不可信：
         * 实测 `https://www.min-yuan.com/...` 被直接当目录名，落成
         * `books/https:/www.min-yuan.com/` —— `/` 和 `..` 同理，能写到
         * `files/` 外面。所以 `:` `/` `\` 等一律替掉，并拒绝全点号的名字。
         */
        fun safeDirName(bookId: String): String {
            val cleaned = bookId.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(80)
            // `.`、`..` 会被文件系统当成当前/上级目录
            if (cleaned.isEmpty() || cleaned.all { it == '.' }) {
                return "id_" + bookId.hashCode().toUInt().toString(16)
            }
            return cleaned
        }
    }

    /** 原子写快照。先写 .tmp 再 rename，避免中途断电留下半截文件。 */
    fun writeJob(
        bookId: String,
        json: String,
        isCancelled: () -> Boolean = { false },
        resume: () -> Boolean = { false },
    ) {
        // 整本书的写入串行化：persist 整份重写，writeState 读-改-写，
        // 两路并发时后写的会把前者的状态盖回去 —— 用户取消下载，
        // 界面报的却是「系统中断」，还得从头重下。按 bookId 分锁，不同书互不阻塞。
        synchronized(lockFor(bookId)) {
            if (bookId in deleted) return
            // 进锁后再判一次：删除请求可能刚好抢在入锁之前。
            val dir = dirOf(bookId).apply { mkdirs() }
            val snapshot = if (isCancelled() || (!resume() && TinyJson.path(readJob(bookId) ?: "", "state") == "CANCELLED"))
                setState(json, JobState.CANCELLED) else json
            val tmp = File(dir, "job.json.tmp")
            // 先 fsync 再改名：只 writeText 的话数据还在页缓存里，改名后再断电就是零字节文件。
            java.io.RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                raf.write(snapshot.toByteArray(Charsets.UTF_8))
                raf.fd.sync()
            }
            val dst = jobFile(bookId)
            // 不要先 delete：Linux 的 rename 本身是原子替换，删了反而造出
            // 「真文件已不存在」的窗口（每章一次，3000 章就是 3000 次机会）。
            if (!tmp.renameTo(dst)) {
                // rename 失败时退化为直写，宁可慢也不要丢状态
                dst.writeText(snapshot)
                tmp.delete()
            }
        }
    }

    private fun setState(json: String, state: JobState): String =
        json.replace(Regex("(\"state\"\\s*:\\s*)\"[A-Z]+\""), "$1\"${state.name}\"")

    /**
     * 读作业文件。文件不存在或读失败一律返回 null。
     *
     * **不能抛异常**：这里原先写的是 `takeIf { exists() }?.readText()`，
     * 存在性检查和读之间是两步，下载完成清理作业或进程重启都可能搵在中间，
     * 于是抛出 FileNotFoundException。它在进度广播的 onReceive 里被调用，
     * 直接就是把整个 Activity 崩掉 —— 用户看到的就是「下载时闪退」。
     */
    fun readJob(bookId: String): String? {
        // 读也要进同一把锁：writeJob 是写 tmp + rename，并发读可能落在改名前后。
        // 主文件不在时回退读 tmp —— 进程正好死在 rename 前，数据还在 tmp 里。
        return synchronized(lockFor(bookId)) {
            runCatching {
                jobFile(bookId).takeIf { it.isFile }?.readText()
                    ?: File(dirOf(bookId), "job.json.tmp").takeIf { it.isFile }?.readText()
            }.getOrNull()
        }
    }

    /** 追加一章并立即 flush。内容里的换行会被 JSON 转义，行分隔安全。 */
    fun appendChapter(bookId: String, chapterJson: String) {
        synchronized(lockFor(bookId)) {
            if (bookId in deleted) return
            dirOf(bookId).mkdirs()
            RandomAccessFile(chaptersFile(bookId), "rw").use { raf ->
                raf.seek(raf.length())
                raf.write((chapterJson + "\n").toByteArray(Charsets.UTF_8))
                raf.fd.sync()
            }
        }
    }

    /** 读回已下载的章节：itemId → 正文。坏行跳过，容忍崩溃留下的半截行。 */
    fun readChapters(bookId: String): Map<String, String> = synchronized(lockFor(bookId)) {
        val f = chaptersFile(bookId)
        if (!f.isFile) return@synchronized emptyMap()
        val out = LinkedHashMap<String, String>()
        // 同样不能抛：并发落盘时文件可能正被改名/清理。
        runCatching {
            f.forEachLine { line ->
                val id = Regex("\"itemId\"\\s*:\\s*\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                    ?: return@forEachLine
                out[id] = line
            }
        }
        out
    }

    /** 只改 state 字段，其余原样保留。取消下载时用来把 RUNNING 改成 PAUSED。 */
    fun writeState(bookId: String, state: JobState) {
        synchronized(lockFor(bookId)) {
            // 读和写使用同一把文件锁。
            val raw = readJob(bookId) ?: return
            writeJob(bookId, setState(raw, state)) { true }
        }
    }

    /**
     * 有作业的 bookId 列表（最近修改的在前）。
     *
     * **返回的是 bookId，不是目录名** —— 目录名是 `safeDirName(bookId)` 净化过的，
     * 两者不同。以前这里直接返回目录名，再拿去 [readJob] 就找不到作业。
     * 只看含 `job.json` 的目录，空目录和导出目录不算。
     */
    fun listBooks(): List<String> =
        root.listFiles()
            ?.filter { it.isDirectory && File(it, "job.json").isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { d -> readJobId(d) }
            ?: emptyList()

    /** 从目录里的 job.json 读出原始 bookId；读不到就跳过。 */
    private fun readJobId(dir: File): String? {
        val raw = runCatching { File(dir, "job.json").readText() }.getOrNull() ?: return null
        return TinyJson.path(raw, "bookId")
    }
}
