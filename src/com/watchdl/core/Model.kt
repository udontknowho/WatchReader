package com.watchdl.core

/** 搜索结果里的一条书。 */
data class BookHit(
    val bookId: String,
    val name: String,
    val author: String,
    val abstractText: String,
    /**
     * 命中的书源名（多源聚合搜索时标注来源）。
     *
     * 单源调用（番茄）留空；[MultiSource] 会把各源的 [LegadoSource.displayName]
     * 或源标识填进来，结果列表据此告诉用户「这本书来自哪个站」。
     */
    val sourceName: String = "",
)

/** 目录里的一章。 */
data class ChapterRef(
    val itemId: String,
    val title: String,
    val locked: Boolean = false,
)

/** 一本书的静态信息（详情 + 目录）。 */
data class BookInfo(
    val bookId: String,
    val name: String,
    val author: String,
    val chapters: List<ChapterRef>,
)

/** 下载任务状态。字段名保持稳定，写进 job.json 后不要随意改。 */
enum class JobState { PENDING, RUNNING, DONE, FAILED, PAUSED, CANCELLED }
