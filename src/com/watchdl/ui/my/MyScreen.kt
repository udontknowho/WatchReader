package com.watchdl.ui.my

import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.Screen
import com.watchdl.ui.attachTabs
import com.watchdl.ui.reader.showAllMarks
import com.watchdl.ui.reader.showRecords
import com.watchdl.ui.search.askFind
import com.watchdl.ui.source.showSources
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 「我的」页。
 *
 * 结构照 legado `ui/main/my/MyScreen`：两组（规则 / 其它），组内条目不额外留白。
 * 标签取自 `values-zh-rCN/strings.xml` 原文。
 *
 * 手表上 12 项一屏放不下，但**纵向列表能滚**，所以不删条目 ——
 * 拿「一屏显示」当裁剪理由会把入口全部砍光。
 *
 * 已经能跳的 4 项接上了，其余 8 项照 legado 的排位保留，点了说一句还没做。
 * shortcut: 8 项是占位行，等 P4/P5 落地再逐项接上。
 */
internal fun MainActivity.showMy() {
    setScreen(Screen.My)
    root.removeAllViews()
    // 根屏（底栏三栏之一），不挂返回箭头：在栏之间切换而不是返回上一步。
    root.addView(Widgets.headerRow(this, "我的"))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)

    // 图标集里没有的（替换净化/字典规则/标签规则等）先不给图标，
    // 有对应图标的（书源 cloud、设置 settings、书签 bookmark、记录 history、
    // 缓存 download、关于 more）照 legado 的 Material 图标补上。
    col.addView(Widgets.groupCard(this, "规则") { g ->
        fun e(
            title: String,
            glyph: String? = null,
            sub: String? = null,
            action: (() -> Unit)? = null,
        ) {
            g.addView(Widgets.groupItem(ctx = this@showMy, title = title, subtitle = sub,
                glyph = glyph, onClick = action ?: { toast("$title 还没做") }))
        }
        // legado 这一项带副标题「新建 · 导入 · 编辑或管理书源」。
        e("书源管理", "cloud", "新建 · 导入 · 编辑或管理书源") { showSources() }
        // 替换净化（#28）：正文的广告尾巴/求票行靠它对掉，可逐条关。
        e("替换净化", "refresh") { showReplace() }
        e("TXT 目录规则", "list")
        e("字典规则", "shelf")
        e("标签规则", "bookmark")
    })

    col.addView(Widgets.groupCard(this, "其它") { g ->
        fun e(title: String, glyph: String? = null, action: (() -> Unit)? = null) {
            g.addView(Widgets.groupItem(ctx = this@showMy, title = title, glyph = glyph,
                onClick = action ?: { toast("$title 还没做") }))
        }
        e("设置", "settings") { showSettings() }
        // 全文搜索（#23）：搜已下载正文，离线可用，与「搜索」栏的联网找书是两回事。
        e("全文搜索", "search") { askFind() }
        e("书签", "bookmark") { showAllMarks() }
        // 阅读记录（#25）：跨书按最后阅读时间列书，点一条接着读。
        e("阅读记录", "history") { showRecords() }
        // 缓存管理（#26）：看正文占多少，可一键清正文（保留目录与进度）。
        e("缓存管理", "download") { showCache() }
        // 文件管理（#27）：看私有目录占多少，逐项删。
        e("文件管理", "grid") { showFiles() }
        // 导出书单（#29）：把书架列成纯文本清单，落到私有 export/ 目录。
        e("导出书单", "list") { showExportList() }
        // 日志（#30）：开发者日志的开关、体积、尾巴，导出与清空都在这。
        e("日志", "history") { showLogs() }
        e("关于", "more") { showAbout() }
        // 不再放「退出」：手表上直接划掉就结束进程，单独一个按钮反而含义含糊
        // （点了退，后台下载线程还在跑）。要真停下载去作业菜单逐本停。
    })

    root.addView(body, Md3.lpWeight(1f))
    attachTabs(2)
}

/** 关于：版本号从 BuildConfig 拿不到（手搓流水线不生成），直接读清单里的值。 */
private fun MainActivity.showAbout() {
    val v = packageManager.getPackageInfo(packageName, 0)
    val name = v.versionName ?: "?"
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) v.longVersionCode else 0L
    toast("WatchDownloader $name（$code）\n手表版 legado · 方向 A")
}
