package com.watchdl.core

import java.io.File

/** 「全部开始」队列里的一本书。目录与正文都在 job.json / chapters.jsonl 里，这里只存身份。 */
data class QueueItem(val bookId: String, val name: String, val author: String)

/**
 * 待下队列（C8）。
 *
 * 一次只跑一个作业：手表上没有并发的收益，反而更容易触发风控。所以 legado 那个
 * 「全部开始」在这里不能并行开跑，只能排成队 —— 一本跑完，服务收尾时取下一本接着跑。
 *
 * 队列落盘。放内存里的话，用户点了「全部开始」而后台进程被杀，回来只会看到下了一半；
 * 落了盘至少下次启动还能把队接着走。文件格式与 job.json 一样走 [TinyJson] / [Downloader.quote]，
 * 不引第三方 JSON 库。
 */
class DownloadQueue(private val file: File) {

    /** 队列内容。文件不存在、读坏、字段缺失一律当空队 —— 队列出错不该拦住下载。 */
    fun items(): List<QueueItem> {
        val raw = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
            ?: return emptyList()
        return TinyJson.objects(raw, "items").mapNotNull { o ->
            val id = TinyJson.path(o, "bookId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            QueueItem(id, TinyJson.path(o, "name") ?: id, TinyJson.path(o, "author") ?: "")
        }
    }

    /**
     * 剔除一本。整本删除时调用：书都没了还留着队里那条，轮到它时又下一遂。
     * 返回剔除后队列长度；书不在队里等于什么都没做。
     */
    fun remove(bookId: String): Int {
        val list = items()
        val left = list.filterNot { it.bookId == bookId }
        if (left.size != list.size) save(left)
        return left.size
    }

    /**
     * 排进队列，返回排完之后的长度。
     *
     * 已在队里的书不重复排：用户再点一次「全部开始」不该把同一本排两遍，
     * 否则一本下完还排着一份，白跑一遍全书。
     */
    fun enqueue(items: List<QueueItem>): Int {
        val merged = ArrayList(this.items())
        val have = merged.mapTo(HashSet()) { it.bookId }
        for (i in items) if (have.add(i.bookId)) merged.add(i)
        save(merged)
        return merged.size
    }

    /** 取队头并出队。空队返回 null。 */
    fun pop(): QueueItem? {
        val list = items()
        if (list.isEmpty()) return null
        save(list.drop(1))
        return list.first()
    }

    fun clear() {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, file.name + ".tmp").delete() }
    }

    /** 原子写：先 tmp 再 rename，跟 job.json 同一个理由（中途断电不留半截文件）。 */
    private fun save(list: List<QueueItem>) {
        if (list.isEmpty()) {
            clear()
            return
        }
        val json = "{\"items\":[" + list.joinToString(",") { i ->
            "{\"bookId\":${Downloader.quote(i.bookId)},\"name\":${Downloader.quote(i.name)}," +
                "\"author\":${Downloader.quote(i.author)}}"
        } + "]}"
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
    }
}
