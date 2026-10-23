package com.watchdl.core

/**
 * 下载进度与状态。写进 job.json，字段名一旦定下不要改。
 *
 * 用户对某个 [ChapterRef] 的下载结果。
 */
data class ChapterState(
    val index: Int,
    val itemId: String,
    val title: String,
    val status: ChapterStatus,
    /** 失败原因，成功时为 null。 */
    val error: String? = null,
)

enum class ChapterStatus { PENDING, DONE, FAILED, LOCKED, SKIPPED }

/** 一本书的下载作业。 */
data class BookJob(
    val bookId: String,
    val name: String,
    val author: String,
    val chapters: List<ChapterRef>,
    val state: JobState = JobState.PENDING,
    val updatedAt: Long = 0L,
) {
    val total: Int get() = chapters.size

    fun count(status: ChapterStatus): Int = statuses.count { it.value == status }

    /** 每章的状态表，键是下标。目录变化时按 itemId 重新对齐。 */
    var statuses: Map<Int, ChapterStatus> = emptyMap()
        private set

    fun withStatuses(s: Map<Int, ChapterStatus>): BookJob = apply { statuses = s }

    /** 免费章数量。付费章不尝试下载，避免白等一串 404。 */
    val freeCount: Int get() = chapters.count { !it.locked }

    val isFinished: Boolean
        get() = chapters.indices.all {
            val s = statuses[it]
            s == ChapterStatus.DONE || s == ChapterStatus.LOCKED || s == ChapterStatus.SKIPPED
        }

    val failedIndexes: List<Int>
        get() = chapters.indices.filter { statuses[it] == ChapterStatus.FAILED }

    val pendingIndexes: List<Int>
        get() = chapters.indices.filter { statuses[it] == null || statuses[it] == ChapterStatus.PENDING }
}
