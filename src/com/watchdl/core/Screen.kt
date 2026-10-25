package com.watchdl.core

/**
 * 屏幕模型与屏身份判据。
 *
 * S20：这两样原来在 `MainActivity` 里是 private，测试只能各写一份副本
 * （`AsyncGuardTest` 重写 `postAlive` 的判据、`NavProgressTest` 重写 `readProgress`），
 * 改坏了真实现测试照样全绿。放这里是因为它不依赖任何 Android 类，
 * 纯 JVM 测试能直接调。
 */

/** 当前屏。手表一次只显示一屏内容。 */
sealed interface Screen {
    object Home : Screen
    data class Hits(
        val keyword: String,
        val hits: List<BookHit>,
        /** 本轮从第几个源开始搜的（换一批源）。 */
        val offset: Int = 0,
        /** 本轮跑了几条源，还没跑的源为换源留钩子。 */
        val searchedSources: Int = 0,
    ) : Screen
    data class Toc(val info: BookInfo, val src: BookSource? = null) : Screen
    data class Reader(
        val info: BookInfo,
        val index: Int,
        val text: String,
        val src: BookSource? = null,
    ) : Screen
    object Sources : Screen
    /** 设置页。目前只有阅读显示与导出目录两块，都是本地项。 */
    object Settings : Screen
    /**
     * 发现页（榜单）。
     *
     * `key` 标识当前看的是哪个榜，`hits` 是已拉到的条目（空表示还在拉）。
     * 只带榜单代号与性别，重进这一屏时不缓存数据 —— 榜单本来就要新数据。
     */
    data class Discover(
        /** 站点：`fq` / `qd`。 */
        val site: String = "fq",
        val gender: String = "male",
        /** 选中的榜；空串表示还没选，只显示选择器。 */
        val rankKey: String = "",
        /** 这个榜拉到的条目。空表示正在拉或没选。 */
        val hits: List<BookHit> = emptyList(),
        /** 取数失败原因；空串表示没失败。 */
        val error: String = "",
    ) : Screen
    /** 书源市场（yiove）。`hits` 为拉到的列表，`page` 为已拉到的页码。 */
    data class Market(val hits: List<YioveSource.Item>, val total: Int, val page: Int) : Screen
    /**
     * 书架：已下载书籍的封面墙。
     *
     * 不带数据 —— 书架的内容全在本地 `books/` 目录里，每次进屏现读。
     * 下载完成、删除、重命名之后回来就能看到新状态，不用比对缓存。
     */
    object Shelf : Screen
    data class Progress(val info: BookInfo) : Screen
}

/**
 * 屏幕的**业务身份**：同一屏在不同时刻数据不同，但身份不变。
 *
 * 后台回调只能拿它比对，不能比整个 [Screen] —— `data class` 的 `equals`
 * 连数据一起比，而回调发起时手里的往往是「还没数据」的空白版，
 * 和屏上带着结果的那个永远不相等，回调就全被丢掉了。
 */
fun screenId(s: Screen?): String = when (s) {
    null -> "?"
    Screen.Home -> "home"
    Screen.Sources -> "sources"
    Screen.Settings -> "settings"
    Screen.Shelf -> "shelf"
    is Screen.Hits -> "hits:${s.keyword}"
    // 目录屏身份用**书名**而不是 bookId：loadToc 时 bookId 还不确知（可能试多个源，
    // 每条源返回的 id 不同），拿 bookId 做身份会让回调认不出自己。
    is Screen.Toc -> "toc:${s.info.name}${s.info.author}"
    is Screen.Reader -> "reader:${s.info.bookId}"
    is Screen.Progress -> "progress:${s.info.bookId}"
    is Screen.Discover -> "discover:${s.site}:${s.gender}:${s.rankKey}"
    is Screen.Market -> "market:${s.page}"
}

/**
 * 旧分节存档的一次性解锁（S20）。
 *
 * 旧版存 `章号 * 1000 + 节号`，新版只存纯章号。判据是**有没有 `last_` 记录**，
 * 不是数值大小 —— 用大小猜会把第 1001 章当成旧值，直接扇回第 1 章。
 *
 * @param lastSeen 这本书有没有 `last_` 记录（新版才写）
 * @param raw 存档里的原始值，-1 表示没记录过
 * @return 真实章号；[needsWriteBack] 为 true 时调用方要把结果写回
 */
fun resolveStoredIndex(
    raw: Int,
    lastSeen: Boolean,
    segBase: Int = 1000,
): Pair<Int, Boolean> {
    if (raw < 0) return 0 to false
    if (lastSeen) return raw to false
    val fixed = if (raw >= segBase) raw / segBase else raw
    return fixed to true
}

/**
 * 后台回调该不该执行（S20）。
 *
 * 判据三条：Activity 还活着、回调对应的那屏仍是当前屏、发起时的身份没变。
 * 抽出来是因为这条规则靠肉眼看代码守不住 —— 13 处 `thread {}` 里只有 1 处
 * 原来做了检查，而后加的两处检查也无从验证。
 */
fun shouldDeliverCallback(
    gone: Boolean,
    ownerId: String,
    currentId: String,
): Boolean = !gone && ownerId == currentId
