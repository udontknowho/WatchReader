package com.watchdl.core

import java.io.File

/**
 * yiove 书源市场（`shuyuan-api.yiove.com`）。接口无鉴权，直连可用。
 *
 * 两层结构，**列表页不含规则**：
 * - `/shuyuan/book-sources?page=N&page_size=100` → 只有 id/name/url，`page_size` 上限 100
 * - `/shuyuan/book-source/{id}` → 完整规则，在 `origin_json` 字段里
 *
 * 全站 31606 条，全量拉要 9 小时、2.5 GB，所以只做「按需拉单条 + 本地缓存」。
 */
object YioveSource {

    private const val HOST = "https://shuyuan-api.yiove.com"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** 列表页一条元数据。 */
    data class Item(val id: String, val name: String, val url: String, val views: Int)

    /** 一页结果。 */
    data class Page(val items: List<Item>, val total: Int, val page: Int, val pages: Int)

    /** 拉一页列表。`page_size` 上限 100，超了服务端报 422。 */
    fun list(page: Int = 1, pageSize: Int = 30, timeoutMs: Int = 15_000): Page {
        val n = pageSize.coerceIn(1, 100)
        val body = Http.fetch(
            "$HOST/shuyuan/book-sources?page=$page&page_size=$n",
            "GET", null, mapOf("User-Agent" to UA), null, timeoutMs,
        ).body
        val items = TinyJson.objects(body, "items").mapNotNull { obj ->
            val id = TinyJson.path(obj, "id") ?: return@mapNotNull null
            Item(
                id = id,
                name = TinyJson.path(obj, "name") ?: "（无名）",
                url = TinyJson.path(obj, "url") ?: "",
                views = TinyJson.path(obj, "view_total")?.toDoubleOrNull()?.toInt() ?: 0,
            )
        }
        return Page(
            items = items,
            total = TinyJson.path(body, "total")?.toIntOrNull() ?: items.size,
            page = TinyJson.path(body, "page")?.toIntOrNull() ?: page,
            pages = TinyJson.path(body, "pages")?.toIntOrNull() ?: 1,
        )
    }

    /**
     * 拉一条完整规则（legado 格式的 JSON 对象原文）。
     *
     * 端点说明：早期用 `/shuyuan/book-source/{id}`，它现在整条被 Cloudflare 拦成 520，
     * 且响应是 `origin_json` 包一层。现行可用端点是 `/import/book-source/{id}`，
     * 直接返回**数组**，元素就是规则对象本身，没有再包一层。
     *
     * 注意：**这里不做反序列化再序列化**。规则里的 `searchUrl`、`exploreUrl` 常常是
     * 多行 JS 字符串，经过一次解析再拼回去，转义与换行都会变样。
     * 所以直接按括号配对从响应文本里切出第一段对象原文。
     */
    fun fetchRule(id: String, timeoutMs: Int = 20_000): String {
        val body = Http.fetch(
            "$HOST/import/book-source/$id",
            "GET", null, mapOf("User-Agent" to UA), null, timeoutMs,
        ).body
        // 老端点的响应形状仍然认一下，站点回滚时不用再改代码。
        TinyJson.path(body, "origin_json")?.let { if (it.isNotBlank()) return it }
        return firstObject(body)
            ?: error("响应里没有规则对象（id=$id）")
    }

    /**
     * 从 JSON 文本里切出第一段 `{...}` 原文，按括号配对，跳过字符串内的括号与转义。
     *
     * 只做切片，不解析：保留原始字节，JS 字符串里的换行和转义一个都不动。
     */
    private fun firstObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                inStr -> {}
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * 把一条 yiove 源导入并落盘。
     *
     * 返回值与书源管理页的标记一致：`ok` 为真表示规则可用（能跑搜索），
     * `issue` 非空说明降级或失败的原因。
     */
    data class Installed(val name: String, val file: File, val ok: Boolean, val issue: String?)

    /**
     * @param dir 书源目录（`filesDir/sources/yiove`），已存在即可
     * @return null 表示这条源在本引擎不可用（`importOne` 没产出规则）
     */
    fun install(dir: File, item: Item): Installed {
        val ruleJson = fetchRule(item.id)
        // importAll 收 JSON 数组，单条包一层
        val imported = LegadoImport.importAll("[$ruleJson]")
        val first = imported.firstOrNull()
            ?: return Installed(item.name, File(dir, ""), false, "解析不出书源")

        if (first.rule == null) {
            return Installed(first.name, File(dir, ""), false, first.issue ?: "本引擎不支持该源")
        }
        dir.mkdirs()
        val f = File(dir, safeName(item.id, first.name) + ".json")
        // 存成数组格式，与其它书源文件一致，管理页可直接复用
        f.writeText("[$ruleJson]", Charsets.UTF_8)
        return Installed(first.name, f, true, first.issue)
    }

    /** 已安装的源文件（按 id 存，所以重复导入会覆盖而不是堆文件）。 */
    fun installed(dir: File): List<File> =
        dir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name } ?: emptyList()

    /** 文件名用 id 打头，避免同名源互相覆盖。 */
    private fun safeName(id: String, name: String): String {
        val clean = name.replace(Regex("[^\\p{L}\\p{N}]"), "_").take(40)
        return "${id.take(8)}_$clean"
    }
}
