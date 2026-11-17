package com.watchdl.core

import java.io.File

/**
 * 本地书源装载。
 *
 * 同一套规则有两处要吃：`MainActivity.buildMulti` 建搜索池，`DownloadService`
 * 建补章池。以前只有前者，补章要换源就得再写一份 —— 两份一起漂移，屏幕上显示的
 * 源名和实际取数的源就对不上。这里收成一处。
 *
 * 顺序即优先级：按文件名排序后再展开。`listFiles()` 的顺序由文件系统决定，
 * 不排序的话每次安装跑的可能都不是同一批源。
 */
object SourceRegistry {

    /**
     * 读出目录下全部书源。
     *
     * 重名加 `@host` 后缀：源库实测 88 组重名，`pickFor` 只按名字找第一条，
     * 不加后缀会把 B 站的书交给 A 站引擎取目录。
     */
    fun load(dir: File, log: (String) -> Unit = {}): List<MultiSource.NamedSource> {
        val pending = mutableListOf<Triple<String, BookSourceRule, String>>()
        // 书源管理屏的启用/移除名单（core/SourcePrefsStore）。源 JSON 不动，
        // 生效点就在这里：被禁用或移除的源不进池。
        val prefs = SourcePrefsStore.load(dir)
        sourceFiles(dir).forEach { f ->
            runCatching {
                val text = f.readText()
                if (text.contains("bookSourceName")) {
                    LegadoImport.importAll(text).forEach { im ->
                        if (im.name in prefs.disabled || im.name in prefs.removed) return@forEach
                        im.rule?.let { pending += Triple(im.name, it, it.host) }
                    }
                }
            }.onFailure { log("载入书源 ${f.name} 失败：${it.message}") }
        }
        val nameCount = pending.groupingBy { MultiSource.tidyName(it.first) }.eachCount()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<MultiSource.NamedSource>()
        for ((name, rule, host) in pending) {
            val bare = MultiSource.tidyName(name)
            val disambiguate = (nameCount[bare] ?: 0) > 1
            val shown = if (disambiguate) {
                val shortHost = host.removePrefix("https://").removePrefix("http://").trimEnd('/')
                "$name @$shortHost"
            } else name
            if (!seen.add(MultiSource.tidyName(shown))) continue   // 同一 host 重复注册，留第一条
            out += MultiSource.NamedSource(shown, LegadoSource.of(rule), host)
        }
        return out
    }

    /**
     * 源目录下的散装 JSON。
     *
     * 只认两类：`legado.json` 整包（唯一会被云端同步覆盖的文件）与不含整包特征的
     * 散装文件（市场单条导入、手动导入）。备份带 `.bak` 后缀，天然被 extension 过滤掉。
     */
    fun sourceFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.filter { !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: emptyList()
}
