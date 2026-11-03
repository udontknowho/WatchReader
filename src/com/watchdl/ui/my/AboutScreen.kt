package com.watchdl.ui.my

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.Screen
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 关于：从「我的」进来，独立成屏。
 *
 * 结构照 legado `ui/about/AboutScreen.kt` + `AboutCatalog.kt` + `AboutSheets.kt`：
 * 头部块（应用名 / 版本 / 一句说明）→ 列表组 → 点开是文本的条目。
 * 原来这里只有一句 toast（版本号 + 一句标语），看完就散，什么也留不下。
 *
 * 条目照 `AboutCatalog` 的三段走，顺序与标题都对齐中文串：
 * 用户隐私与协议 / 开源许可 / 免责声明，然后是崩溃日志 / 保存日志。
 * 行上写「开源许可」、点开的标题才是「许可证」—— legado 也是两个 key
 * （`license` 与 `about_license_title`），不是同一句话印两遍。
 *
 * legado 关于页上有四项手表上不适用，这里逐项交代去处，不留点了没反应的行：
 * - 检查更新 / 项目主页 / GitHub：本机没有浏览器，也没有对外发布渠道
 *   （APK 由 build.sh 手搓，装机靠 adb），三项都没有可跳的目标。
 * - 开发人员：单人项目，没有名单可列，行尾的 GitHub 也点不开。
 * - 创建堆转储：`Debug.dumpHwHeap` 只对 debuggable 包有效，.hprof 动辄几十 MB，
 *   手表存储放不下也导不出来。
 */
internal fun MainActivity.showAbout() {
    setScreen(Screen.About)
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "关于", onBack = { showMy() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    col.addView(aboutHeader())

    // 组「文档」：legado `AboutCatalog.documents`（用户隐私与协议 / 开源许可 / 免责声明）。
    // 组标题是我们加的：legado 在手机上整串列表不带标题（`SplicedColumnGroup(title = "")`），
    // 但 189×248 的屏上一屏只放得下三行，没有组名就分不清「文档」与「诊断」。
    col.addView(Widgets.groupCard(this, "文档") { g ->
        g.addView(Widgets.groupItem(ctx = this, title = "用户隐私与协议",
            subtitle = "数据只在本机，不上传", glyph = "check",
            onClick = { showPrivacy() }))
        g.addView(Widgets.groupItem(ctx = this, title = "开源许可",
            subtitle = "Apache 2.0 · 脚本引擎 MPL 2.0", glyph = "list",
            onClick = { showLicense() }))
        g.addView(Widgets.groupItem(ctx = this, title = "免责声明",
            subtitle = "内容与版权归原作者及原站点", glyph = "warning",
            onClick = { showDisclaimer() }))
    })

    // 组「诊断」：legado `AboutCatalog.diagnostics`（崩溃日志 / 保存日志 / 创建堆转储）。
    // 「崩溃日志」链到日志页而不是在本页再摊一份尾巴：日志页已有开关、体积、
    // 最后 40 行与空态，本页再读一次文件是第二份实现，两处必然走样。
    // legado 那边是 `CrashLogSheet`（文件列表 + 顶栏清除）→ 点开 `LogDetailSheet`；
    // 我们只有一个 dev.log，一次点击换不来什么，直接进日志页。
    col.addView(Widgets.groupCard(this, "诊断") { g ->
        g.addView(Widgets.groupItem(ctx = this, title = "崩溃日志",
            subtitle = "开关、体积与最后 40 行", glyph = "history",
            onClick = { showLogs() }))
        g.addView(Widgets.groupItem(ctx = this, title = "保存日志",
            subtitle = "写文件并存到文档", glyph = "save",
            onClick = { saveLog() }))
    })

    root.addView(body, Md3.lpWeight(1f))
}

/**
 * 头部块：应用名 / 版本名（带版本号）/ 一句说明，三行居中对齐。
 *
 * legado 头部是「120×160dp 图标 + 应用名 + 版本 TextCard + 说明」。
 * 手表屏只有 189×248dp，120dp 高的图标会把下面的版本与说明全挤出首屏，
 * 所以图标整块去掉 —— 应用名本身就够认。
 * 版本号从清单读，不在这里再写一份与 build.sh 不同步的常量。
 */
private fun MainActivity.aboutHeader(): LinearLayout {
    val box = Md3.column(this)
    box.gravity = Gravity.CENTER_HORIZONTAL
    box.setPadding(Md3.dp(this, Md3.PAD), Md3.dp(this, Md3.GAP), Md3.dp(this, Md3.PAD), 0)

    box.addView(Md3.text(this, "WatchDownloader", Md3.FS_LG, Md3.onSurface, bold = true)
        .apply { gravity = Gravity.CENTER },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
    val name = info?.versionName ?: "?"
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
        info?.longVersionCode ?: 0L
    } else {
        (info?.versionCode ?: 0).toLong()
    }
    // 版本名装在一枚胶囊里：legado 用的是 TextCard（圆角容器包住版本串），
    // 直接一行灰字会和下面的说明混成一段。
    box.addView(Md3.text(this, "$name（$code）", Md3.FS_SM, Md3.onSurfaceVariant).apply {
        gravity = Gravity.CENTER
        background = Md3.bg(Md3.surface3, Md3.R_GRID.toFloat())
        setPadding(Md3.dp(this@aboutHeader, 8), Md3.dp(this@aboutHeader, 2),
            Md3.dp(this@aboutHeader, 8), Md3.dp(this@aboutHeader, 2))
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = Md3.dp(this@aboutHeader, Md3.GAP / 2) })

    box.addView(Md3.text(this, "手表版 legado · 方向 A\n把已下载的正文做成离线阅读器",
        Md3.FS_SM, Md3.onSurfaceVariant).apply { gravity = Gravity.CENTER },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Md3.dp(this@aboutHeader, Md3.GAP / 2) })

    return box
}

/**
 * 保存日志。
 *
 * legado `AboutViewModel.saveLog` 先看「记录日志」开关：没开就提示
 * `about_log_recording_disabled`（未开启日志记录，请去其他设置里打开记录日志），
 * 再照旧拷文件。手表上「导出一个几乎空的文件」同样说不清，所以这里照它的提示走，
 * 但提示完就停 —— 省掉一次白写盘与一次「已存到文档」的假成功。
 */
private fun MainActivity.saveLog() {
    if (!devLogOn()) {
        toast("未开启日志记录，先到日志页打开「记录日志」")
        return
    }
    exportDevLog()
}

/**
 * 用户隐私与协议。legado 读 assets 里的 `PRIVACY_POLICY.md`（Markdown sheet），
 * 我们没有那份材料，就把实际成立的三条写死在对话框里：
 * 数据落在哪、什么时候联网、卸载会发生什么。清单里除 INTERNET 与
 * 网络状态、通知、前台服务、唤醒锁、导出用的存储权限之外没有别的权限。
 */
private fun MainActivity.showPrivacy() {
    Widgets.md3Dialog(this,
        title = "隐私政策",
        body = "书源、书单、正文缓存与阅读进度只写在应用私有目录，不上传，也不做统计上报。\n" +
            "只有取书源数据时会访问你配置的站点；日志只写本机的 dev.log。\n" +
            "卸载会连同私有目录一起删掉，要留档先导出。",
        dismiss = "关闭",
    ).show()
}

/**
 * 许可证。legado 是 Markdown sheet 读 `LICENSE` 文件，我们没有 assets 里的 markdown，
 * 所以直接把要说的几行写进对话框。
 *
 * 只写实际成立的两条：本应用自身 Apache 2.0，脚本引擎 Rhino 走 MPL 2.0；
 * 其余依赖都是 AndroidX（Apache 2.0），不再逐条列，免得写错。
 */
private fun MainActivity.showLicense() {
    Widgets.md3Dialog(this,
        title = "许可证",
        body = "Apache License 2.0：可自由使用、修改与再分发，须保留版权与许可声明，不提供担保。\n" +
            "脚本引擎 Rhino 走 MPL 2.0，其余为 AndroidX。",
        dismiss = "关闭",
    ).show()
}

/**
 * 免责声明。逐条对着本应用真会出问题的地方写，不抄通用套话：
 * 书源是用户自己加的、正文会缺章、数据只在本机。
 */
private fun MainActivity.showDisclaimer() {
    Widgets.md3Dialog(this,
        title = "免责声明",
        body = "书源由你自行添加，内容与版权归原作者及原站点，请勿转载或商业分发。\n" +
            "站点改版、正文缺章、下载失败都可能发生，不保证内容完整。\n" +
            "数据只存在本机私有目录，卸载即删，不做任何上传。",
        dismiss = "关闭",
    ).show()
}
