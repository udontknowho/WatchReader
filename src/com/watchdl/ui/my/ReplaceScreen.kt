package com.watchdl.ui.my

import com.watchdl.MainActivity
import com.watchdl.core.ReplaceRule
import com.watchdl.core.ReplaceRuleStore
import com.watchdl.core.Screen
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 替换净化（#28）：正文替换规则的开关表。
 *
 * 这一页只做「开/关」和「导入」，不做编辑 —— 手表上没有能舒服敲正则的键盘，
 * 规则由 replace.json 或默认表提供，页面负责把它们快速关掉看原文。
 * 一条规则生效与否，读下一章就能看出来，所以不需要预览按钮。
 */

internal fun MainActivity.showReplace() {
    setScreen(Screen.Replace)
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "替换净化", onBack = { showMy() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    val rules = ReplaceRuleStore.list()
    val on = rules.count { it.enabled }

    col.addView(Widgets.sectionLabel(this, "${rules.size} 条 · 启用 $on 条"))
    if (rules.isEmpty()) {
        col.addView(Widgets.emptyState(this, "refresh", "还没有替换规则。"))
    }
    for (r in rules) {
        col.addView(Widgets.listItem(
            ctx = this,
            title = r.name,
            subtitle = "${if (r.enabled) "启用" else "停用"} · ${scopeLabel(r.scope)}",
            onClick = { toggleRule(r) },
        ))
    }

    // 只放一个开关按钮：多数时候要的是「全关掉看原文」，
    // 两个按钮并排会把屏宽 189dp 的那一行挤成两个半个字。
    col.addView(Widgets.button(this, if (on > 0) "全部停用" else "全部启用", "outline") {
        ReplaceRuleStore.save(rules.map {
            ReplaceRule(it.name, it.pattern, it.replacement, it.isRegex, it.scope, enabled = on == 0)
        })
        toast(if (on > 0) "已全部停用" else "已全部启用")
        showReplace()
    })
    // 导入是唯一能把外部规则弄进来的路：手表上没有粘贴板可用的输入框。
    col.addView(Widgets.button(this, "从 replace.json 导入", "outline") { importRules() })
    root.addView(body, Md3.lpWeight(1f))
}

private fun scopeLabel(scope: String): String = when (scope) {
    "title" -> "标题"
    "both" -> "正文+标题"
    else -> "正文"
}

private fun MainActivity.toggleRule(r: ReplaceRule) {
    // 按「名称 + 模式」定位同一条：规则没有稳定 id，列表内容也可能被外部改过，
    // 直接按下标改会改错行。
    val next = ReplaceRuleStore.list().map {
        if (it.name == r.name && it.pattern == r.pattern)
            ReplaceRule(it.name, it.pattern, it.replacement, it.isRegex, it.scope, !it.enabled)
        else it
    }
    ReplaceRuleStore.save(next)
    toast("${r.name}已${if (r.enabled) "停用" else "启用"}")
    showReplace()
}

/** 文件由用户自己放进应用私有目录（adb push），本页只负责解析与合并。 */
private fun MainActivity.importRules() {
    val f = ReplaceRuleStore.file
    if (f == null || !f.isFile) {
        toast("还没有 replace.json：先把规则文件放进应用目录")
        return
    }
    val raw = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    if (raw == null) {
        toast("replace.json 读不出来")
        return
    }
    val n = ReplaceRuleStore.importJson(raw)
    when {
        n < 0 -> toast("replace.json 不是规则数组")
        n == 0 -> toast("没有新规则（已有的按名称+模式跳过）")
        else -> toast("导入 $n 条")
    }
    showReplace()
}
