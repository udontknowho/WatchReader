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
/**
 * 一本书的正文缓存统计，只给缓存管理页看。
 *
 * 字段对齐 legado 缓存管理的「已缓存/总章数」：那边按章节缓存文件数算，
 * 这边按 `chapters.jsonl` 的实际章数算，含义相同。
 */
data class CacheUsage(
    val bookId: String,
    val name: String,
    /** 作者。缓存页搜索要能按作者找（legado 搜索框也是书名或作者）。 */
    val author: String = "",
    /** 已缓存正文的字节数，即 `chapters.jsonl` 的大小。 */
    val bytes: Long,
    /** 磁盘上真有的章数。 */
    val cached: Int,
    /** 目录章数。 */
    val total: Int,
    /** job.json 里标为 FAILED 的章数。下载失败要能被一眼看见，所以单独给出。 */
    val failed: Int = 0,
    /**
     * job.json 里仍是 PENDING 的章数，即「还没下过」。
     *
     * legado 的「等待 / 暂停」都从这份余量推：跑着的书里这些章在排队，停了的就是暂停。
     */
    val pending: Int = 0,
    /** 作业状态。缓存页用它区分「下载中 / 暂停 / 下完」，不看这个就只能猜。 */
    val state: JobState = JobState.PENDING,
)

class JobStore(private val root: File) {

    /** 根目录锁跨实例共享，不依赖迁移前后的路径。 */
    private val rootKey = root.canonicalPath

    /** 旧缓存只凭快照身份迁移，无快照时不猜测归属。 */
    private fun dirOf(bookId: String): File = synchronized(lockFor(bookId)) {
        val key = rootKey + "\u0000" + bookId
        fallbackDirs[key]?.let { return@synchronized it }
        val target = File(root, safeDirName(bookId))
        val legacy = File(root, legacyDirName(bookId))
        if (target != legacy && readJobId(target) != bookId && readJobId(legacy) == bookId) {
            if (target.exists() || !legacy.renameTo(target)) {
                // 固定回退路径，避免流式写入器打开后目录再次搬动。
                fallbackDirs[key] = legacy
                return@synchronized legacy
            }
        }
        target
    }

    private fun ownsDirectory(dir: File, bookId: String): Boolean =
        (!File(dir, "job.json").exists() && !File(dir, "job.json.tmp").exists()) || readJobId(dir) == bookId

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
        return synchronized(lockFor(bookId)) {
            val dir = dirOf(bookId)
            if (!ownsDirectory(dir, bookId)) false else dir.deleteRecursively() || !dir.exists()
        }
    }

    /** 重新下载同一本书时撤销墓碑。 */
    fun clearDeleted(bookId: String) { deleted.remove(bookId) }

    @Suppress("UNUSED_PARAMETER")
    private fun lockFor(bookId: String): Any = locks.computeIfAbsent(rootKey) { Any() }

    companion object {
        /**
         * 导入暂存目录的后缀，见 [openStaging]。
         *
         * 带 `~` 是刻意的：原样标识不含它，摘要键以十六进制结尾，
         * 所以正式键不可能以 `~building` 结尾。清理方应按快照身份核对目录名，
         * 不能对目录名再次调用 [safeDirName] 判断是否合法。
         */
        internal const val STAGING_SUFFIX = "~building"

        /** 换名时旧目录的临时后缀，见 [commitStaging]。同样在净化白名单之外。 */
        internal const val OLD_SUFFIX = "~old"

        /** 这两个后缀的目录都不是书：一律不进书架，进程启动时可安全清掉。 */
        fun isTransientDirName(name: String): Boolean =
            name.endsWith(STAGING_SUFFIX) || name.endsWith(OLD_SUFFIX)

        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private val fallbackDirs = java.util.concurrent.ConcurrentHashMap<String, File>()

        /** 从 chapters.jsonl 的一行里取 itemId。正文里可能有别的 JSON，只认这个键。 */
        private val ITEM_ID = Regex("\"itemId\"\\s*:\\s*\"([^\"]*)\"")

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
            val cleaned = bookId.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
            if (cleaned == bookId && cleaned.length <= 80 &&
                cleaned.isNotEmpty() && !cleaned.all { it == '.' }) return cleaned
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bookId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            // ~ 不属于原样保留的字符集，普通标识不能冒充摘要键。
            return cleaned.take(14).ifEmpty { "id" } + "~" + digest
        }

        /** 只用于旧目录身份核对，不能再用于创建目录。 */
        private fun legacyDirName(bookId: String): String {
            val cleaned = bookId.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(80)
            return if (cleaned.isEmpty() || cleaned.all { it == '.' })
                "id_" + bookId.hashCode().toUInt().toString(16) else cleaned
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
        // 界面报的却是「系统中断」，还得从头重下。根目录锁同时保护旧目录迁移。
        synchronized(lockFor(bookId)) {
            if (bookId in deleted) return
            // 进锁后再判一次：删除请求可能刚好抢在入锁之前。
            val dir = dirOf(bookId)
            check(ownsDirectory(dir, bookId)) { "作业目录身份不符：${dir.name}" }
            val jsonId = TinyJson.path(json, "bookId")
            require(jsonId == null || jsonId == bookId) { "快照 bookId 不符" }
            val identified = if (jsonId == null) {
                val open = json.indexOf('{')
                require(open >= 0) { "快照不是 JSON 对象" }
                val comma = if (json.substring(open + 1).trimStart().startsWith("}")) "" else ","
                json.replaceRange(open, open + 1, "{\"bookId\":" + Downloader.quote(bookId) + comma)
            } else json
            dir.mkdirs()
            val snapshot = if (isCancelled() || (!resume() && TinyJson.path(readJob(bookId) ?: "", "state") == "CANCELLED"))
                setState(identified, JobState.CANCELLED) else identified
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
                val dir = dirOf(bookId)
                if (!ownsDirectory(dir, bookId)) return@runCatching null
                (File(dir, "job.json").takeIf { it.isFile }?.readText()
                    ?: File(dir, "job.json.tmp").takeIf { it.isFile }?.readText())
                    ?.takeIf { TinyJson.path(it, "bookId") == bookId }
            }.getOrNull()
        }
    }

    /** 追加一章并立即 flush。内容里的换行会被 JSON 转义，行分隔安全。 */
    fun appendChapter(bookId: String, chapterJson: String) {
        synchronized(lockFor(bookId)) {
            if (bookId in deleted) return
            val dir = dirOf(bookId)
            check(ownsDirectory(dir, bookId)) { "作业目录身份不符：${dir.name}" }
            dir.mkdirs()
            RandomAccessFile(chaptersFile(bookId), "rw").use { raf ->
                raf.seek(raf.length())
                raf.write((chapterJson + "\n").toByteArray(Charsets.UTF_8))
                raf.fd.sync()
            }
        }
    }

    /**
     * 一次性写入整本正文（本地导入用）。
     *
     * 逐章走 [appendChapter] 的话每章一次 fsync，一千章的书在手表的闪存上要等好几秒。
     * 这里同样先写 tmp 再 rename，但有且只有一次 fsync：中途断电不会留下半截正文库。
     */
    fun writeChapters(bookId: String, lines: List<String>) {
        synchronized(lockFor(bookId)) {
            if (bookId in deleted) return
            val dir = dirOf(bookId)
            check(ownsDirectory(dir, bookId)) { "作业目录身份不符：${dir.name}" }
            dir.mkdirs()
            val f = chaptersFile(bookId)
            val tmp = File(f.parentFile, "chapters.jsonl.tmp")
            runCatching {
                RandomAccessFile(tmp, "rw").use { raf ->
                    raf.setLength(0)
                    for (line in lines) {
                        raf.write((line + "\n").toByteArray(Charsets.UTF_8))
                    }
                    raf.fd.sync()
                }
                if (!tmp.renameTo(f)) {
                    f.writeText(lines.joinToString("\n", postfix = "\n"))
                    tmp.delete()
                }
            }.onFailure { tmp.delete() }
        }
    }

    /**
     * 流式写整本正文：一章写完立刻落盘，内存只留当前章。
     *
     * 本地导入原来是「整本解成 List、再拼 JSON、再一次性写」，峰值是文件的 5–6 倍：
     * 55MB 正文要 185MB 堆，手表直接 OOM。改成边解析边写之后，峰值只剩单章。
     *
     * 仍然先写 tmp 再 rename，中途失败或进程被杀都不会留下半本正文。
     * 生命周期：`openChapters` → `add` × N → `commit`（或 `abort`）。
     */
    inner class ChapterSink internal constructor(private val bookId: String, private val dir: File) {
        private val tmp = File(dir, "chapters.jsonl.tmp")
        private var raf: RandomAccessFile? = null

        /** 已写章数。 */
        var count = 0
            private set

        internal fun open() = synchronized(lockFor(bookId)) {
            check(ownsDirectory(dir, bookId)) { "作业目录身份不符：${dir.name}" }
            dir.mkdirs()
            tmp.parentFile?.mkdirs()
            raf = RandomAccessFile(tmp, "rw").also { it.setLength(0) }
        }

        /** 追加一行（已编码好的 JSON 对象）。调用方负责转义。 */
        fun add(line: String) {
            val r = raf ?: error("sink 已关闭")
            r.write((line + "\n").toByteArray(Charsets.UTF_8))
            count++
        }

        /** 落定：一次 fsync + 改名。之后的 add 会抛。 */
        fun commit() {
            val r = raf ?: return
            raf = null
            r.fd.sync()
            r.close()
            synchronized(lockFor(bookId)) {
                check(ownsDirectory(dir, bookId)) { "作业目录身份不符：${dir.name}" }
                if (bookId in deleted) { tmp.delete(); return }
                val f = File(dir, "chapters.jsonl")
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            }
        }

        /** 放弃：删掉半截 tmp，主文件保持原样。 */
        fun abort() {
            val r = raf ?: return
            raf = null
            runCatching { r.close() }
            tmp.delete()
        }
    }

    /** 开一个流式正文写入器。 */
    fun openChapters(bookId: String): ChapterSink = synchronized(lockFor(bookId)) {
        ChapterSink(bookId, dirOf(bookId)).also { it.open() }
    }

    /** 导入新书时用的暂存目录。带后缀的目录不会有 job.json，所以永远不会被书架当成一本书。 */
    private fun stagingDir(bookId: String): File = File(root, safeDirName(bookId) + STAGING_SUFFIX)

    /**
     * 开一个写往暂存目录的正文写入器。
     *
     * 重导一本本地书**不能先删旧目录**：produce 中途失败（epub 包坏、编码认不出）
     * 只会 abort 掉 chapters.jsonl 的 tmp，而旧 job.json 已经被 [markDeleted] 删了 ——
     * 用户的书连同正文一起消失。改成先写满 `<目录>.building`，成功后再整体换名。
     */
    fun openStaging(bookId: String): ChapterSink = ChapterSink(bookId, stagingDir(bookId)).also { it.open() }

    /**
     * 把暂存目录换成正式目录，并落定 job.json。
     *
     * 三步换名，**任何一步断电都至少留一份完整可读的目录**：
     * 1. 写 `staging/job.json.tmp`（暂存目录里没有 job.json，书架还看不见这本）
     * 2. 旧目录改名 `<目录>~old`，暂存目录改名成正式目录
     * 3. 删 `<目录>~old`，再把 `job.json.tmp` 改成 `job.json` —— 到这一步书才「上线」
     *
     * 以前是先删旧目录再改名：中间那一下断电只剩一个没有 job.json 的 `.building`，
     * 书架、缓存页、记录页全看不见，用户视角就是书没了。改成「旧目录让位」之后，
     * 断电时要么旧目录还在，要么新目录已经在，不会有两头空的时刻。
     */
    fun commitStaging(bookId: String, json: String) {
        synchronized(lockFor(bookId)) {
            require(TinyJson.path(json, "bookId") == bookId) { "快照 bookId 不符" }
            check(ownsDirectory(dirOf(bookId), bookId)) { "作业目录身份不符" }
            val st = stagingDir(bookId)
            if (!st.isDirectory) return
            // 先把 job.json.tmp 写实。**写不成就整单放弃**：这一刻旧书还在，什么都不该动。
            // 以前这里 runCatching 吞掉结果，磁盘满时会把旧书删了再往上抛，用户的书直接没。
            val tmp = File(st, "job.json.tmp")
            val wrote = runCatching {
                RandomAccessFile(tmp, "rw").use { raf ->
                    raf.setLength(0)
                    raf.write(json.toByteArray(Charsets.UTF_8))
                    raf.fd.sync()
                }
            }.isSuccess && tmp.isFile
            if (!wrote) {
                abortStaging(bookId)
                error("暂存目录写不进 job.json：${st.name}")
            }
            val dir = dirOf(bookId)
            val old = File(root, dir.name + OLD_SUFFIX)
            runCatching { old.deleteRecursively() }
            var hasOld = false
            if (dir.isDirectory) {
                hasOld = dir.renameTo(old)
                if (hasOld) {
                    // 旧目录已经让位，但老写入线程还认这个 bookId：立墓碑挡住它们的落盘。
                    // 走 deleted 而不是 markDeleted：目录已经不在了，再删一次只是白跑一趟。
                    deleted.add(bookId)
                } else {
                    // 改名不成（极少见）就退回「删了再换」：宁可少一层保险，不能把新书堵死。
                    markDeleted(bookId)
                }
            }
            if (!st.renameTo(dir)) {
                // 同一分区里 rename 不该失败；真失败了就地逐个搬，**搬不动就报错，绝不删暂存**。
                // 以前这里忽略每次 renameTo 的返回值，搬失败的文件随后被 deleteRecursively 清掉。
                dir.mkdirs()
                val moved = st.listFiles()?.all { f -> f.renameTo(File(dir, f.name)) } ?: true
                if (!moved) {
                    // 旧目录还在 `~old` 里，放回原位，用户至少还有一本旧书可读。
                    if (hasOld && !dir.listFiles().isNullOrEmpty()) dir.deleteRecursively()
                    if (hasOld) old.renameTo(dir)
                    error("暂存正文搬不进 ${dir.name}")
                }
            }
            runCatching { old.deleteRecursively() }
            clearDeleted(bookId)
            // 到这一步书架才看得见这本书。
            val staged = File(dir, "job.json.tmp")
            val job = File(dir, "job.json")
            if (!staged.renameTo(job)) {
                job.writeText(json, Charsets.UTF_8)
                staged.delete()
            }
        }
    }

    /** 放弃这次导入：只删暂存目录，旧书原样留着。 */
    fun abortStaging(bookId: String) {
        runCatching { stagingDir(bookId).deleteRecursively() }
    }

    /**
     * 已缓存章的 itemId 集合。
     *
     * **只取 itemId，不留正文**：3000 章的书正文能到几十 MB，
     * 缓存页只为画 ✓/✗，不能把整本读进内存（[readChapters] 是整本进内存，别在这用）。
     */
    fun cachedItemIds(bookId: String): Set<String> = synchronized(lockFor(bookId)) {
        if (!ownsDirectory(dirOf(bookId), bookId)) return@synchronized emptySet()
        val f = chaptersFile(bookId)
        if (!f.isFile) return@synchronized emptySet()
        val out = HashSet<String>()
        // 同样不能抛：并发落盘时文件可能正被改名。
        runCatching {
            f.bufferedReader().use { br ->
                br.forEachLine { line ->
                    ITEM_ID.find(line)?.groupValues?.get(1)?.let { out.add(it) }
                }
            }
        }
        out
    }

    /**
     * 删一章正文，并把这一章的状态改回 PENDING（C6）。
     *
     * 重写 chapters.jsonl 时先写 tmp 再 rename，中途断电不会留下半截正文库。
     * job.json 只把**这一章**的 status 改掉：先按 `"itemId":"<id>"` 定位，
     * 再从那里往后找第一个 `"status":"`。不重写整份 json —— 快照里有
     * 别处刚写的字段（updatedAt、别的章状态），拿旧快照改回去会盖掉它们。
     * resume 传 true：否则「原状态是 CANCELLED 就保持」那条分支会把 PENDING 打回去。
     *
     * 返回是否真的删了一行。
     */
    fun deleteChapter(bookId: String, itemId: String): Boolean = synchronized(lockFor(bookId)) {
        if (bookId in deleted || !ownsDirectory(dirOf(bookId), bookId)) return@synchronized false
        val f = chaptersFile(bookId)
        var removed = false
        if (f.isFile) {
            val tmp = File(f.parentFile, "chapters.jsonl.tmp")
            runCatching {
                tmp.bufferedWriter().use { w ->
                    f.bufferedReader().use { r ->
                        r.forEachLine { line ->
                            val id = ITEM_ID.find(line)?.groupValues?.get(1)
                            if (id == itemId) removed = true
                            else {
                                w.write(line)
                                w.write("\n")
                            }
                        }
                    }
                    w.flush()
                }
                if (!tmp.renameTo(f)) {
                    f.writeText(tmp.readText())
                    tmp.delete()
                }
            }.onFailure { tmp.delete() }
        }
        if (removed) pendingChapter(bookId, itemId)
        removed
    }

    /** 把某一章的 status 改为 PENDING。找不到就不动。 */
    private fun pendingChapter(bookId: String, itemId: String) {
        val raw = readJob(bookId) ?: return
        val at = raw.indexOf("\"itemId\":" + Downloader.quote(itemId))
        if (at < 0) return
        val key = "\"status\":\""
        val s = raw.indexOf(key, at)
        if (s < 0) return
        val begin = s + key.length
        val end = raw.indexOf('"', begin)
        if (end <= begin) return
        val json = raw.substring(0, begin) + "PENDING" + raw.substring(end)
        writeJob(bookId, json, isCancelled = { false }, resume = { true })
    }

    /** 读回已下载的章节：itemId → 正文。坏行跳过，容忍崩溃留下的半截行。 */
    fun readChapters(bookId: String): Map<String, String> = synchronized(lockFor(bookId)) {
        if (!ownsDirectory(dirOf(bookId), bookId)) return@synchronized emptyMap()
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

    /**
     * 只读某一章：扫到命中行就停。
     *
     * [readChapters] 会把整本 jsonl 逐行进 Map，三千章的书在手表上要十几兆内存、
     * 秒级的正则；而换章与拼接块预取都只关心当前这一章，顺读时每章付一次整表代价。
     * 先用 `contains` 粗筛再解 itemId：粗筛不命中就不跑正则。
     */
    fun readChapter(bookId: String, itemId: String): String? = synchronized(lockFor(bookId)) {
        if (!ownsDirectory(dirOf(bookId), bookId)) return@synchronized null
        val f = chaptersFile(bookId)
        if (!f.isFile || itemId.isEmpty()) return@synchronized null
        // 不能抛：并发落盘时文件可能正被改名/清理。
        runCatching {
            f.useLines { lines ->
                lines.firstOrNull { line ->
                    line.contains(itemId) &&
                        Regex("\"itemId\"\\s*:\\s*\"([^\"]*)\"").find(line)
                            ?.groupValues?.get(1) == itemId
                }
            }
        }.getOrNull()
    }

    /**
     * 清掉已下载正文，目录与阅读进度留着（#26）。
     *
     * 删的是正文副本，**不是书**：目录、进度、书源都还在，重新下载能接上。
     * 但清完这本书就离线读不了了 —— 点章只能回退联网取，书源失效就取不回。
     * 所以调用方必须先把这句后果告诉用户，不能当一个无害的「清缓存」处理。
     *
     * 只删 chapters.jsonl、把章节与作业状态改回 PENDING：章节表还在 job.json 里，
     * 重下时能直接用回目录，不用重新取。
     * 状态用正则就位改，**不重写整份 json** —— 别处刚写进去的字段会被旧快照盖掉。
     * resume 传 true：writeJob 里那条「原状态是 CANCELLED 就继续保持」的分支会把
     * 我们刚改的 PENDING 又打回 CANCELLED，取消过的书就永远清不成待下。
     */
    fun clearChapters(bookId: String) {
        synchronized(lockFor(bookId)) {
            if (!ownsDirectory(dirOf(bookId), bookId)) return
            chaptersFile(bookId).delete()
            val raw = readJob(bookId) ?: return
            val json = raw
                .replace(Regex("(\"status\"\\s*:\\s*)\"[A-Z]+\""), "$1\"PENDING\"")
                .replace(Regex("(\"state\"\\s*:\\s*)\"[A-Z]+\""), "$1\"PENDING\"")
            writeJob(bookId, json, isCancelled = { false }, resume = { true })
        }
    }

    /**
     * 逐本统计正文缓存，占用大的在前。
     *
     * 「已缓存」数按 `chapters.jsonl` 的实际行数算，**不读 job.json 的 status**：
     * 快照按批写，断电时最后一批 DONE 可能没落盘，状态说已下、磁盘上没有。
     * 这一页是拿来决定删谁的，宁可少算也不能虚报。
     *
     * 只算 job.json 存在的目录：导出目录、空壳目录都不算一本书。
     */
    fun cacheUsages(): List<CacheUsage> = synchronized(lockFor("")) {
        val dirs = root.listFiles()
            ?.filter { it.isDirectory && !isTransientDirName(it.name) && File(it, "job.json").isFile }
            ?: return@synchronized emptyList()
        dirs.mapNotNull { dir ->
            val bookId = readJobId(dir) ?: return@mapNotNull null
            if (dir.name != safeDirName(bookId) && dir.name != legacyDirName(bookId)) return@mapNotNull null
            // 走 readJob：它进同一把文件锁，且主文件不在时会回退读 tmp。
            val raw = readJob(bookId) ?: return@mapNotNull null
            val f = chaptersFile(bookId)
            val lines = runCatching {
                f.bufferedReader().use { br ->
                    var n = 0
                    br.forEachLine { if (it.isNotBlank()) n++ }
                    n
                }
            }.getOrDefault(0)
            val total = TinyJson.objects(raw, "chapters").size
            CacheUsage(
                bookId = bookId,
                name = TinyJson.path(raw, "name")?.ifBlank { bookId } ?: bookId,
                author = TinyJson.path(raw, "author") ?: "",
                bytes = f.length(),
                // 目录缩过水的旧作业里，jsonl 行数可能比目录还多。
                cached = lines.coerceAtMost(total),
                total = total,
                failed = countStatus(raw, "FAILED"),
                pending = countStatus(raw, "PENDING"),
                state = runCatching { JobState.valueOf(TinyJson.path(raw, "state") ?: "PENDING") }
                    .getOrDefault(JobState.PENDING),
            )
        }.distinctBy { it.bookId }.sortedByDescending { it.bytes }
    }

    /**
     * 数 job.json 里某种章节状态的条数。
     *
     * 不走 `TinyJson.objects` 切数组：3000 章会切出 3000 个字符串对象，
     * 只为数个数太贵。这里是一次线性扫描，字段名格式是 [Downloader.persist] 定死的。
     */
    private fun countStatus(raw: String, status: String): Int {
        val needle = "\"status\":\"$status\""
        var n = 0
        var i = raw.indexOf(needle)
        while (i >= 0) {
            n++
            i = raw.indexOf(needle, i + needle.length)
        }
        return n
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
    fun listBooks(): List<String> = synchronized(lockFor("")) {
        root.listFiles()
            ?.filter { it.isDirectory && !isTransientDirName(it.name) && File(it, "job.json").isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { d ->
                val id = readJobId(d) ?: return@mapNotNull null
                id.takeIf { (d.name == safeDirName(id) || d.name == legacyDirName(id)) && readJob(id) != null }
            }
            ?.distinct()
            ?: emptyList()
    }

    /** 从目录里的 job.json 读出原始 bookId；读不到就跳过。 */
    private fun readJobId(dir: File): String? {
        val raw = runCatching {
            val job = File(dir, "job.json")
            if (job.isFile) job.readText() else File(dir, "job.json.tmp").readText()
        }.getOrNull() ?: return null
        return TinyJson.path(raw, "bookId")
    }
}
