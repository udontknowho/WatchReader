package com.watchdl.ui.book

import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 换源屏。
 *
 * legado 的换源是「把书名丢给全部源再搜一遍，列表给你挑」。手表上这么干
 * 要跑几十条源，所以这一屏给两条路：
 * 1. 全量搜索 —— 跨 `pool` 里**全部**源现搜一遍（并发 12、预算 25 s），
 *    边跑边出命中，一条源一条命中的行，点了直接试这条源的目录。慢源会被记失败，
 *    界面照实报「失败 f 条」，不假装全都问到了；
 * 2. 全部源 —— 点了只搜这一条源，心里已经有目标源时走这条，一两秒就有结果。
 *
 * 以前这里列的是搜索结果里现成的同名书（`detailCandidates`）。那是旧结果，
 * 换源恰恰发生在「搜索结果里没有别的源」的时候，所以整段废掉了。
 *
 * 换成功的判据与 [loadToc] 一致：目录非空、首章正文过长度线。不合格的源
 * 会被 loadToc 自己挡掉并报失败，不会悄悄换到一条坏源上。
 */

/** 全量源列表最多列多少条。源库实测 88 组重名、总数七八十条，全画出来手表滚不完。 */
private const val SWAP_ROWS = 60

/**
 * 上一次换源还在联网。用文件级变量而不是 Activity 字段：它只是这一屏的瞬时状态，
 * 进程重建后本来就该是 false。
 */
private var swapBusy = false

internal fun MainActivity.showSwap(info: BookInfo, cur: BookSource?) {
    // 本地书没有第二个源可换：它的正文就是用户手机上那个文件。
    // 不拦住的话会把「本地书名」拿去全网搜，搜到的同书不同源也对不上已读进度。
    if (LocalImport.isLocal(info.bookId)) { toast("本地书没有可换的书源"); return }
    setScreen(Screen.Swap(info, cur))
    // 切屏时 bg 会把上一次的搜索 cancel 掉，忙标志没人清就会永久卡在「还在找」。
    // 重建这一屏说明上一次已经作废，这里直接放开。
    swapBusy = false
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "换源", onBack = { goBackToPrevious() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)

    val curName = srcName(cur)
    val all = multi.allNamed()
    col.addView(Widgets.sectionLabel(this, "正在用"))
    col.addView(Widgets.listItem(this, curName.ifBlank { "未知源" },
        "章节与正文都从这条源取", twoLine = true, highlight = true))

    // 命中区与状态行要在动作行之前建好，点击时闭包能直接抓到它们。
    val hitsBox = Md3.column(this)
    val status = Md3.text(this, "先搜索，再点选验证可用的书源", Md3.FS_SM, Md3.onSurfaceVariant).apply {
        setPadding(Md3.dp(this@showSwap, Md3.PAD), 0,
            Md3.dp(this@showSwap, Md3.PAD), Md3.dp(this@showSwap, Md3.GAP / 2))
    }
    // 已出现过的源名（只在主线程里改）：命中按源名去重，每源只留第一条，
    // 否则七八条源各命中一次，同一本书会在屏上排十几行。
    val shown = mutableSetOf<String>()

    /**
     * 全量搜索。跨 `pool` 全部源、并发 12、预算 25 s。
     *
     * 边跑边出：`onPartial` 到一批就插一批，用户不用盯着空屏等十几秒。
     * 两个回调都在任意线程上，动视图前必须先 `postAlive` 回主线程。
     */
    fun startAll() {
        if (swapBusy) { toast("上一次换源还在找，等一下"); return }
        if (all.isEmpty()) { status.text = "没有可用书源，请先在设置中导入或启用书源"; return }
        swapBusy = true
        shown.clear()
        hitsBox.removeAllViews()
        status.text = "正在问全部 ${all.size} 条源…"
        val owner: Screen = Screen.Swap(info, null)
        bg(owner, {
            multi.searchAll(info.name, 1,
                onProgress = { done, total ->
                    postAlive(owner) { status.text = "已问 $done/$total 条源…" }
                },
                onPartial = { hits, done, total ->
                    postAlive(owner) {
                        status.text = "已问 $done/$total 条源…"
                        for (hit in hits) {
                            val sn = hit.sourceName.ifBlank { "未命名源" }
                            // 当前源不必再试：正在用的就是它。
                            if (sn == curName || !shown.add(sn)) continue
                            hitsBox.addView(Widgets.listItem(this, sn,
                                "${hit.author} · 点了直接试这条源的目录", twoLine = true) {
                                rememberReturn()
                                loadToc(info.name, info.author,
                                    listOf(hit.copy(sourceName = hit.sourceName.ifBlank { sn })))
                            })
                        }
                    }
                })
        }) { res ->
            swapBusy = false
            res.fold(onSuccess = { r ->
                status.text = when {
                    r.hits.isEmpty() -> "全部源都没有这本书"
                    // 命中全落在当前源上：屏上没有可换的行，也得说清。
                    shown.isEmpty() -> "命中 ${r.hits.size} 条，都在当前源"
                    else -> "命中 ${shown.size} 条源 · 失败 ${r.failed.size} 条 / 共 ${r.total} 条源"
                }
            }, onFailure = { e ->
                // 搜索本身不该抛（源异常都在内部吞掉了），真抛了就说清是哪一类。
                status.text = "搜索中断：${e.javaClass.simpleName}"
            })
        }
    }

    // 动作行放在当前源卡片之后：这一屏最该按的就是它。
    val act = Widgets.listItem(this, "全量搜索这本书",
        "把书名丢给全部 ${all.size} 条源并发搜一遍，约十几秒", twoLine = true) { startAll() }
    col.addView(act)
    col.addView(status, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    col.addView(hitsBox)

    col.addView(Widgets.sectionLabel(this, "全部源 ${all.size} 条"))
    col.addView(Md3.text(this, "点一条就在这条源里找这本书，找到才换过去",
        Md3.FS_SM, Md3.onSurfaceVariant).apply {
        setPadding(0, 0, 0, Md3.dp(this@showSwap, Md3.GAP / 2))
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // 当前源排最前，其余按登记顺序：常用的源本来就在前面。
    val ordered = all.sortedBy { if (srcName(it.source) == curName) 0 else 1 }
    for (ns in ordered.take(SWAP_ROWS)) {
        val isCur = srcName(ns.source) == curName
        col.addView(Widgets.listItem(this, ns.name,
            if (isCur) "正在用" else null, twoLine = isCur) {
            if (!isCur) swapToSource(info, ns)
        })
    }
    if (ordered.size > SWAP_ROWS) {
        col.addView(Md3.text(this, "另有 ${ordered.size - SWAP_ROWS} 条未显示", Md3.FS_SM,
            Md3.onSurfaceVariant))
    }

    root.addView(body)
}

/**
 * 在指定源里找这本书，找到就换过去。
 *
 * 只搜这一条源（约 1~3 秒），不跑全量：全量要十几秒，而用户心里已经有目标源。
 * 匹配顺序：书名与作者都一致 > 书名一致 > 书名包含。都不中就明说没有，
 * 不硬换 —— 换成另一本书比换失败更糟。
 *
 * 「换源后回原章」的标题由阅读页发起换源时记好（`ReaderUi.swapReturnTitle`），
 * 这里只管挑源与 `loadToc`，不碰它。
 */
internal fun MainActivity.swapToSource(info: BookInfo, ns: MultiSource.NamedSource) {
    if (swapBusy) { toast("上一次换源还在找，等一下"); return }
    swapBusy = true
    toast("正在「${ns.name}」找这本书…")
    val owner: Screen = Screen.Swap(info, null)
    bg(owner, { ns.source.search(info.name, 1) }) { res ->
        swapBusy = false
        val hits = res.getOrElse { e ->
            toast("「${ns.name}」搜索失败：${e.message?.take(32) ?: "网络不可用"}")
            return@bg
        }
        val want = info.name.trim()
        val wantAuthor = info.author.trim()
        val pick = hits.firstOrNull {
            it.name.trim() == want && (wantAuthor.isEmpty() || it.author.trim() == wantAuthor)
        } ?: hits.firstOrNull { it.name.trim() == want }
          ?: hits.firstOrNull { it.name.contains(want) }
        if (pick == null) {
            toast("「${ns.name}」没有这本书")
        } else {
            rememberReturn()
            loadToc(info.name, info.author,
                listOf(pick.copy(sourceName = pick.sourceName.ifBlank { ns.name })))
        }
    }
}
