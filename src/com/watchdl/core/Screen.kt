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
    data class Hits(
        val keyword: String,
        val hits: List<BookHit>,
        /** 本轮从第几个源开始搜的（换一批源）。 */
        val offset: Int = 0,
        /** 本轮跑了几条源，还没跑的源为换源留钩子。 */
        val searchedSources: Int = 0,
        /**
         * 合并显示前的完整命中（同一本书多源各一条）。
         *
         * 列表按「书名+作者」只出一张卡，而点进去时要能按源依次重试。
         * 没有这个字段时 siblingsOf 只能拿到合并后的列表，候选永远只有它自己。
         */
        val all: List<BookHit> = hits,
    ) : Screen
    data class Toc(val info: BookInfo, val src: BookSource? = null) : Screen
    data class Reader(
        val info: BookInfo,
        val index: Int,
        val text: String,
        val src: BookSource? = null,
    ) : Screen
    object Sources : Screen
    /**
     * 设置。一页拆成两页（照 legado `ui/config/ConfigNavScreen`）：首屏是入口列表，
     * [page] 指明当前在哪一子页（`main` / `theme` / `read` / `crown` / `other`）。
     * 进程被回收后靠它回到同一子页，而不是甩回首屏。
     */
    data class Settings(val page: String = "main") : Screen
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
     * 书架：已下载书籍的封面墙，也是应用首页（照 legado：进应用落在书架）。
     *
     * 不带数据 —— 书架的内容全在本地 `books/` 目录里，每次进屏现读。
     * 下载完成、删除、重命名之后回来就能看到新状态，不用比对缓存。
     */
    object Shelf : Screen
    data class Progress(val info: BookInfo) : Screen
    /** 书签列表。带 info 是为了点一条能直接取那一章正文；书签本身每次进屏现读。 */
    data class Marks(val info: BookInfo) : Screen
    /**
     * 全书书签（#24）：不分书，把所有书的书签聚一屏。
     *
     * 不带数据 —— 列表每次进屏现读，删一条之后回来不会看到旧的。
     */
    object MarksAll : Screen
    /**
     * 换源屏。列出「正在用哪个源」与「还能从哪些源取这本书」，选一条就换过去。
     *
     * [src] 是当前源，用来标「正在用」；换成功后整屏会换成目录，不会停在原地。
     */
    data class Swap(val info: BookInfo, val src: BookSource? = null) : Screen
    /**
     * 全文搜索：搜已下载正文（#23）。
     *
     * [query] 空串表示还没输入，进屏先要关键词；[hits] 是扫到的命中，
     * [scanned] 是已扫过的书数，[done] 区分「扫完了没命中」和「还没开始扫」。
     */
    data class Find(
        val query: String = "",
        val hits: List<FindText.Hit> = emptyList(),
        val scanned: Int = 0,
        val done: Boolean = false,
    ) : Screen
    /**
     * 阅读记录（#25）：不分书，把所有读过的书按最后阅读时间排一屏。
     *
     * 不带数据 —— 进度在 SharedPreferences 里，每次进屏现读，读一章回来就是新的。
     */
    object Records : Screen
    /**
     * 缓存管理（#26）：各书已下载正文的占用，可一键清理正文。
     *
     * 不带数据 —— 字节数每次进屏现算；清理完重画本页就是新值。
     */
    object Cache : Screen
    /**
     * 文件管理（#27）：私有目录的占用，逐项可删。
     *
     * 不带数据 —— 大小每次进屏现算（要递归整棵树，所以放后台），删完重画就是新值。
     */
    object Files : Screen
    /**
     * 替换净化（#28）：正文替换规则的开关表。
     *
     * 不带数据 —— 规则存在 replace.json，每次进屏现读，改完回来不会看到旧值。
     */
    object Replace : Screen
    /**
     * 导出书单（#29）：把书架上的书列成一份纯文本清单。
     *
     * 不带数据 —— 「最近生成的是哪份」按 export/ 里最新的 `书单-*` 文件现算，
     * 不另存状态：屏被重建（进程回收、配置变更）后照样能显示对。
     */
    object ExportList : Screen
    /**
     * 日志（#30）：开关状态、dev.log 体积与行数、最后 40 行。
     *
     * 不带数据 —— 日志每时每刻在长，缓存一次就会看到过期的尾巴。
     */
    object Logs : Screen
    /** 「我的」页：规则与其它两组入口，照 legado `ui/main/my`。不带数据，每次进屏现读。 */
    object My : Screen
    /**
     * 书详情，照 legado `ui/book/info`。
     *
     * 从搜索结果进来时 [info] 的章节表是空的（还没取目录），其余字段都有；
     * 从目录/进度屏进来时字段全。是否为空决定「查看目录」是直接跳还是先取。
     */
    data class Detail(val info: BookInfo, val src: BookSource? = null) : Screen
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
    Screen.Sources -> "sources"
    is Screen.Settings -> "settings:${s.page}"
    Screen.Shelf -> "shelf"
    Screen.My -> "my"
    is Screen.Hits -> "hits:${s.keyword}"
    // 目录屏身份用**书名**而不是 bookId：loadToc 时 bookId 还不确知（可能试多个源，
    // 每条源返回的 id 不同），拿 bookId 做身份会让回调认不出自己。
    is Screen.Toc -> "toc:${s.info.name}${s.info.author}"
    is Screen.Reader -> "reader:${s.info.bookId}"
    is Screen.Progress -> "progress:${s.info.bookId}"
    is Screen.Marks -> "marks:${s.info.bookId}"
    Screen.MarksAll -> "marksAll"
    Screen.Records -> "records"
    Screen.Cache -> "cache"
    Screen.Files -> "files"
    Screen.Replace -> "replace"
    Screen.ExportList -> "exportList"
    Screen.Logs -> "logs"
    is Screen.Swap -> "swap:" + s.info.bookId.ifBlank { s.info.name + s.info.author }
    is Screen.Discover -> "discover:${s.site}:${s.gender}:${s.rankKey}"
    is Screen.Market -> "market:${s.page}"
    // 只比关键词，不比结果：边扫边上屏时结果一直在变，带上结果回调永远认不出自己。
    is Screen.Find -> "find:${s.query}"
    // 搜索命中时 bookId 可能为空（个别源不返回），退回书名+作者做身份。
    is Screen.Detail -> "detail:" + s.info.bookId.ifBlank { s.info.name + s.info.author }
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
