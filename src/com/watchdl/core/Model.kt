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
    /** 封面图地址。榜单接口会给，搜索接口一般没有。 */
    val cover: String = "",
    /**
     * 列表上的一行附加说明：字数、排名、最新章节之类。
     *
     * 各榜想显示的辅助信息不同，与其给 [BookHit] 加一堆专用字段，
     * 不如让取数的榜自己拼好这一句。
     */
    val extra: String = "",
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
    /**
     * 目录可能不完整（翻页预算耗尽或被超时截断）。
     *
     * 为真时上层应提示用户，并把这本书标成「目录不完整」而不是正常完成 ——
     * 以前截断只 `break`，`toc()` 照常返回已有章节，后续章节标 DONE，
     * 书悄悄少几十章（N6）。
     */
    val truncated: Boolean = false,
)

/** 下载任务状态。字段名保持稳定，写进 job.json 后不要随意改。 */
enum class JobState { PENDING, RUNNING, DONE, FAILED, PAUSED, CANCELLED }
