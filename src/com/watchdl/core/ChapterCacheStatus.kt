package com.watchdl.core

/**
 * 缓存管理章行状态，照 legado-novel `ChapterCacheStatus.kt` 的八态与优先级。
 *
 * 纯函数，JVM 单测能直接覆盖优先级；界面只负责把枚举翻成文案。
 */
enum class ChapterCacheStatus {
    /** 源给了进度标签（如「第 3/12 页」），优先显示它。 */
    ProgressLabel,
    Downloading,
    /** 失败过，还在重试队列里。 */
    ErrorWaitingRetry,
    Waiting,
    Paused,
    Error,
    Cached,
    NotCached,
}

/**
 * 八态判定，优先级与 legado 一致：
 * ProgressLabel > Downloading > ErrorWaitingRetry > Waiting > Paused > Error > Cached > NotCached。
 *
 * 判定顺序为什么是这样：正在做的事最该说；其次是「会自己变好」的（重试中、排队中）；
 * 然后才是静止状态。已缓存排在失败之后 —— 失败过一次的章即便磁盘上有正文，
 * 也要让用户看见「下载失败」，那可能是半截正文。
 */
fun resolveChapterCacheStatus(
    isDownloading: Boolean,
    isWaiting: Boolean,
    isPaused: Boolean,
    isError: Boolean,
    isCached: Boolean,
    hasProgressLabel: Boolean,
): ChapterCacheStatus {
    if (isDownloading && hasProgressLabel) return ChapterCacheStatus.ProgressLabel
    if (isDownloading) return ChapterCacheStatus.Downloading
    if (isWaiting && isError) return ChapterCacheStatus.ErrorWaitingRetry
    if (isWaiting) return ChapterCacheStatus.Waiting
    if (isPaused) return ChapterCacheStatus.Paused
    if (isError) return ChapterCacheStatus.Error
    if (isCached) return ChapterCacheStatus.Cached
    return ChapterCacheStatus.NotCached
}

/** 八态的中文文案，取自 legado `values-zh-rCN/strings.xml` 同名条目。 */
fun chapterCacheStatusText(status: ChapterCacheStatus, progressLabel: String? = null): String =
    when (status) {
        ChapterCacheStatus.ProgressLabel -> progressLabel.orEmpty()
        ChapterCacheStatus.Downloading -> "下载中"
        ChapterCacheStatus.ErrorWaitingRetry -> "下载失败，等待重试"
        ChapterCacheStatus.Waiting -> "待下载"
        ChapterCacheStatus.Paused -> "已暂停"
        ChapterCacheStatus.Error -> "下载失败"
        ChapterCacheStatus.Cached -> "下载完成"
        ChapterCacheStatus.NotCached -> "未缓存"
    }
