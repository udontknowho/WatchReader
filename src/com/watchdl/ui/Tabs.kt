package com.watchdl.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.watchdl.MainActivity
import com.watchdl.ui.my.showMy
import com.watchdl.ui.shelf.showShelf
import com.watchdl.ui.source.showDiscover
import com.watchdl.ui.theme.Widgets

/**
 * 底部三栏，照 legado `ui/main/MainDestination`：书架 / 发现 / 我的。
 *
 * legado 是一个 Scaffold 共用底部导航，主体按当前栏切换。我们的每一屏都是
 * `root.removeAllViews()` 后现搭，所以底部导航就在三屏各自收尾时挂上去 ——
 * 每屏加一行 `attachTabs(n)`，比再包一层容器改动小，也不会打乱各屏自己的滚动区。
 *
 * 参数是当前栏下标：0 书架、1 发现、2 我的。
 */
internal fun MainActivity.attachTabs(active: Int) {
    root.addView(Widgets.navBar(this, listOf(
        Triple("shelf", "书架", active == 0),
        Triple("explore", "发现", active == 1),
        Triple("mine", "我的", active == 2),
    )) { i ->
        when (i) {
            // 重复点当前栏：legado `handleMainDestinationClick` 只给书架做
            // 「回到顶部」（`requestBookshelfScrollToTop`），发现 / 我的 再点无动作。
            // 书架滚远了点底栏回顶，是这块屏上唯一一个不用转表冠就能回到开头的手势。
            0 -> if (active == 0) scrollToTop(root) else showShelf()
            1 -> if (active != 1) showDiscover()
            else -> if (active != 2) showMy()
        }
    })
}

/** 把屏里第一个纵向滚动区拉回顶部。找不到就不动 —— 空屏也是合法状态。 */
private fun scrollToTop(from: View) {
    firstScrollView(from)?.smoothScrollTo(0, 0)
}

private fun firstScrollView(v: View): ScrollView? {
    if (v is ScrollView) return v
    if (v !is ViewGroup) return null
    for (i in 0 until v.childCount) firstScrollView(v.getChildAt(i))?.let { return it }
    return null
}
