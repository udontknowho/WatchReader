package com.watchdl.ui.my

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.AppUpdate
import com.watchdl.core.Screen
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/** 关于页沿用现有卡片主题。检查更新仅在用户点击后联网，不下载或安装。 */
internal fun MainActivity.showAbout() {
    setScreen(Screen.About)
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "关于", onBack = { showMy() }))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    col.addView(aboutHeader())

    // 状态仅属于本次页面。切屏后 bg 的生命周期检查会丢弃旧回调。
    var checking = false
    col.addView(Widgets.groupCard(this, "项目与更新") { g ->
        val status = Md3.text(this, "仅检查正式版，不自动下载或安装", Md3.FS_SM, Md3.onSurfaceVariant).apply {
            setPadding(Md3.dp(this@showAbout, Md3.PAD), Md3.dp(this@showAbout, 4),
                Md3.dp(this@showAbout, Md3.PAD), Md3.dp(this@showAbout, 8))
        }
        g.addView(Widgets.groupItem(ctx = this, title = "检查更新",
            subtitle = "从 GitHub 获取最新正式版", glyph = "history",
            onClick = check@{
                if (checking) { toast("正在检查更新"); return@check }
                checking = true
                status.text = "正在检查更新…"
                bg(Screen.About, body = { AppUpdate.checkLatest() }) { result ->
                    checking = false
                    result.onSuccess { release ->
                        val current = currentVersion()
                        val comparison = runCatching { AppUpdate.compareVersions(release.version, current) }
                        val message = comparison.fold(onSuccess = { cmp -> when {
                            cmp > 0 -> "发现新版本 ${release.version}"
                            cmp == 0 -> "已是最新正式版 ${release.version}"
                            else -> "当前版本高于最新正式版 ${release.version}"
                        } }, onFailure = { "最新正式版 ${release.version}，当前版本无法比较" })
                        status.text = message
                        showUpdateResult(release, current, message)
                    }.onFailure { error ->
                        val reason = when (error) {
                            is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
                            is java.net.UnknownHostException -> "无法连接 GitHub，请检查网络"
                            else -> error.message ?: "未知错误"
                        }
                        status.text = "检查失败，点击可重试"
                        Widgets.md3Dialog(this, "检查更新失败", body = reason,
                            confirm = "发布地址", dismiss = "关闭",
                            onConfirm = { showAboutLink("正式发布", AppUpdate.RELEASES_URL) }).show()
                    }
                }
            }))
        g.addView(status)
        g.addView(Widgets.groupItem(ctx = this, title = "项目地址",
            subtitle = "WatchReader · GitHub", glyph = "list",
            onClick = { showAboutLink("项目地址", AppUpdate.PROJECT_URL) }))
    })
    col.addView(Widgets.groupCard(this, "文档") { g ->
        g.addView(Widgets.groupItem(ctx = this, title = "用户隐私与协议",
            subtitle = "本地存储与联网用途", glyph = "check", onClick = { showPrivacy() }))
        g.addView(Widgets.groupItem(ctx = this, title = "开源许可",
            subtitle = "GPL-3.0 · 脚本引擎 MPL 2.0", glyph = "list", onClick = { showLicense() }))
        g.addView(Widgets.groupItem(ctx = this, title = "免责声明",
            subtitle = "内容与版权归原作者及原站点", glyph = "warning", onClick = { showDisclaimer() }))
    })
    col.addView(Widgets.groupCard(this, "诊断") { g ->
        g.addView(Widgets.groupItem(ctx = this, title = "运行日志",
            subtitle = "记录开关与最近日志", glyph = "history", onClick = { showLogs() }))
        g.addView(Widgets.groupItem(ctx = this, title = "保存日志",
            subtitle = "手动导出到文档", glyph = "save", onClick = { saveLog() }))
    })
    root.addView(body, Md3.lpWeight(1f))
}

private fun MainActivity.currentVersion(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "未知"

private fun MainActivity.aboutHeader(): LinearLayout {
    val box = Md3.column(this)
    box.gravity = Gravity.CENTER_HORIZONTAL
    box.setPadding(Md3.dp(this, Md3.PAD), Md3.dp(this, Md3.GAP), Md3.dp(this, Md3.PAD), 0)
    box.addView(Md3.text(this, "WatchReader", Md3.FS_LG, Md3.onSurface, bold = true)
        .apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info?.longVersionCode ?: 0L
        else (info?.versionCode ?: 0).toLong()
    box.addView(Md3.text(this, "${info?.versionName ?: "未知"}（$code）", Md3.FS_SM, Md3.onSurfaceVariant).apply {
        gravity = Gravity.CENTER
        background = Md3.bg(Md3.surface3, Md3.R_GRID.toFloat())
        setPadding(Md3.dp(this@aboutHeader, 8), Md3.dp(this@aboutHeader, 2),
            Md3.dp(this@aboutHeader, 8), Md3.dp(this@aboutHeader, 2))
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = Md3.dp(this@aboutHeader, Md3.GAP / 2) })
    box.addView(Md3.text(this, "手表上的书源阅读器\n搜索、缓存与离线阅读", Md3.FS_SM, Md3.onSurfaceVariant)
        .apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Md3.dp(this@aboutHeader, Md3.GAP / 2) })
    return box
}

/** 地址不使用自动链接，只有用户点击“浏览器打开”才触发外部 Activity。 */
private fun MainActivity.showAboutLink(title: String, url: String) {
    val content = Md3.column(this)
    content.addView(Md3.text(this, url, Md3.FS_SM, Md3.onSurfaceVariant).apply {
        setTextIsSelectable(true)
    })
    content.addView(Widgets.groupItem(ctx = this, title = "复制地址", glyph = "list", onClick = {
        runCatching {
            val clipboard = getSystemService(ClipboardManager::class.java)
                ?: throw IllegalStateException("剪贴板不可用")
            clipboard.setPrimaryClip(ClipData.newPlainText(title, url))
        }.fold(onSuccess = { toast("地址已复制") }, onFailure = { toast("无法复制，请长按地址选择文本") })
    }))
    Widgets.md3Dialog(this, title, content = content, confirm = "浏览器打开", dismiss = "关闭",
        onConfirm = {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { toast("没有可用的浏览器，请复制地址到其他设备打开") }
        }).show()
}

private fun MainActivity.showUpdateResult(release: AppUpdate.Release, current: String, message: String) {
    val content = Md3.column(this)
    content.addView(Md3.text(this, "当前版本：$current\n最新正式版：${release.version}",
        Md3.FS_SM, Md3.onSurfaceVariant))
    content.addView(Widgets.groupItem(ctx = this, title = "下载地址", subtitle = "查看、复制或手动打开",
        glyph = "save", onClick = { showAboutLink("下载地址", release.downloadUrl) }))
    content.addView(Widgets.groupItem(ctx = this, title = "发布说明", subtitle = "查看 GitHub 正式发布页",
        glyph = "list", onClick = { showAboutLink("发布说明", release.pageUrl) }))
    Widgets.md3Dialog(this, "更新检查", body = message, content = content, dismiss = "关闭").show()
}

private fun MainActivity.saveLog() {
    if (!devLogOn()) { toast("未开启日志记录，先到日志页打开“记录日志”"); return }
    exportDevLog()
}

private fun MainActivity.showPrivacy() {
    Widgets.md3Dialog(this, title = "隐私政策",
        body = "书源配置、书架、正文缓存与阅读进度保存在本机。应用不主动向项目方上传这些数据，也不做统计上报。\n" +
            "搜索、目录、正文下载与书源导入会访问书源配置的站点或导入地址；书源脚本也可能发起网络请求，请只导入可信书源。\n" +
            "点击检查更新会请求 GitHub Release API；打开项目或下载地址由外部浏览器处理。远端服务可接收 IP、请求头和查询内容，适用各自政策。\n" +
            "日志写在本机，可能包含书名、地址或错误信息，仅由你手动导出。导出文件由你选择保存。卸载会删除应用私有数据，不会自动删除已导出的文件。",
        dismiss = "关闭").show()
}

private fun MainActivity.showLicense() {
    Widgets.md3Dialog(this, title = "许可证",
        body = "GNU GPL v3.0：可自由使用、修改与再分发，分发衍生作品须同样以 GPL-3.0 开放源码，不提供担保。\n" +
            "脚本引擎 Rhino 采用 MPL 2.0，其余依赖保留各自原许可。", dismiss = "关闭").show()
}

private fun MainActivity.showDisclaimer() {
    Widgets.md3Dialog(this, title = "免责声明",
        body = "书源由你自行添加，内容与版权归原作者及原站点，请勿转载或商业分发。\n" +
            "站点改版、正文缺章、下载失败都可能发生，不保证内容完整。\n" +
            "卸载会删除应用私有数据，需保留的数据请先手动导出。", dismiss = "关闭").show()
}
