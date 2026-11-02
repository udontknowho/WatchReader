package com.watchdl.ui.book

import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 换源屏（最小版）。
 *
 * legado 的换源是「把书名丢给全部源再搜一遍，列表给你挑」。手表上这么干
 * 要跑几十条源、十几秒起步，所以这里分两段：
 * 1. 搜索结果里已经握着的同名书命中 —— 不用联网，点了直接试目录；
 * 2. 全部源 —— 点了只搜这一条源，有这本书才换。
 *
 * 换成功的判据与 [loadToc] 一致：目录非空、首章正文过长度线。不合格的源
 * 会被 loadToc 自己挡掉并报失败，不会悄悄换到一条坏源上。
 */

/** 一屏最多列多少条源。源库实测 88 组重名、总数七八十条，全画出来手表滚不完。 */
private const val SWAP_ROWS = 60

/**
 * 上一次换源还在联网。用文件级变量而不是 Activity 字段：它只是这一屏的瞬时状态，
 * 进程重建后本来就该是 false。
 */
private var swapBusy = false

internal fun MainActivity.showSwap(info: BookInfo, cur: BookSource?) {
    setScreen(Screen.Swap(info, cur))
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "换源", onBack = { goBackToPrevious() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)

    val curName = srcName(cur)
    col.addView(Widgets.sectionLabel(this, "正在用"))
    col.addView(Widgets.listItem(this, curName.ifBlank { "未知源" },
        "章节与正文都从这条源取", twoLine = true) { })

    // 搜索结果里的同名书：手上现成的命中项，换源零等待。
    val same = detailCandidates
        .filter { it.name.trim() == info.name.trim() && it.sourceName != curName }
        .distinctBy { it.sourceName }
    if (same.isNotEmpty()) {
        col.addView(Widgets.sectionLabel(this, "同名书命中 ${same.size} 条"))
        for (hit in same.take(SWAP_ROWS)) {
            col.addView(Widgets.listItem(this, hit.sourceName.ifBlank { "未命名源" },
                "点了直接试这条源的目录", twoLine = true) {
                rememberReturn()
                loadToc(info.name, info.author, listOf(hit))
            })
        }
    }

    val all = multi.allNamed()
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
 */
internal fun MainActivity.swapToSource(info: BookInfo, ns: MultiSource.NamedSource) {
    if (swapBusy) { toast("上一次换源还在找，等一下"); return }
    swapBusy = true
    toast("正在「${ns.name}」找这本书…")
    val owner: Screen = Screen.Swap(info, null)
    bg(owner, { ns.source.search(info.name, 1) }) { res ->
        swapBusy = false
        val hits = res.getOrNull().orEmpty()
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
