package com.watchdl.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** 只检查正式发布。联网由调用方放进后台任务，不下载或安装 APK。 */
object AppUpdate {
    const val PROJECT_URL = "https://github.com/udontknowho/WatchReader"
    const val RELEASES_URL = "$PROJECT_URL/releases/latest"
    const val API_URL = "https://api.github.com/repos/udontknowho/WatchReader/releases/latest"
    private const val MAX_BYTES = 1024 * 1024

    data class Release(val version: String, val pageUrl: String, val downloadUrl: String)
    private data class Version(val parts: List<String>, val pre: List<String>)

    private fun version(raw: String): Version {
        val text = raw.trim().removePrefix("v").removePrefix("V")
        val match = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
            .matchEntire(text) ?: throw IllegalArgumentException("版本号格式无效：$raw")
        val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
        require(pre.none { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) {
            "预发布版本号格式无效：$raw"
        }
        return Version((1..3).map { match.groupValues[it] }, pre)
    }

    /** SemVer：比较 major/minor/patch，正式版高于同号预发布，构建元数据不参与比较。 */
    fun compareVersions(left: String, right: String): Int {
        val a = version(left)
        val b = version(right)
        fun number(x: String, y: String): Int =
            x.length.compareTo(y.length).takeIf { it != 0 } ?: x.compareTo(y)
        for (i in 0..2) number(a.parts[i], b.parts[i]).let { if (it != 0) return it }
        if (a.pre.isEmpty() || b.pre.isEmpty()) return when {
            a.pre.isEmpty() && b.pre.isEmpty() -> 0
            a.pre.isEmpty() -> 1
            else -> -1
        }
        for (i in 0 until minOf(a.pre.size, b.pre.size)) {
            val x = a.pre[i]; val y = b.pre[i]
            val xn = x.all(Char::isDigit); val yn = y.all(Char::isDigit)
            val cmp = when {
                xn && yn -> number(x, y)
                xn -> -1
                yn -> 1
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return a.pre.size.compareTo(b.pre.size)
    }

    fun parseRelease(json: String): Release {
        require(json.length <= MAX_BYTES) { "更新响应过大" }
        JsonValidator(json).validate()
        require(json.trimStart().startsWith('{')) { "更新响应不是对象" }
        require(TinyJson.field(json, "draft") == "false") { "更新响应不是已发布版本" }
        require(TinyJson.field(json, "prerelease") == "false") { "更新响应不是正式版" }
        fun string(obj: String, key: String): String {
            val raw = TinyJson.field(obj, key) ?: throw IllegalArgumentException("更新响应缺少 $key")
            require(raw.startsWith('"')) { "更新字段 $key 不是文本" }
            return TinyJson.path(obj, key)!!.trim()
        }
        val tag = string(json, "tag_name")
        require(version(tag).pre.isEmpty()) { "更新版本标签是预发布版本" }
        val page = string(json, "html_url")
        require(isReleaseUrl(page, false)) { "发布页地址无效" }
        val assets = TinyJson.field(json, "assets") ?: "[]"
        require(assets.startsWith('[')) { "更新附件不是数组" }
        var download = page
        for (asset in TinyJson.allValues(assets)) {
            if (!asset.startsWith('{')) throw IllegalArgumentException("更新附件格式无效")
            val name = string(asset, "name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = string(asset, "browser_download_url")
            require(isReleaseUrl(url, true)) { "下载地址无效" }
            download = url
            break
        }
        return Release(tag, page, download)
    }

    private fun isReleaseUrl(raw: String, asset: Boolean): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
            uri.port == -1 && uri.fragment == null && uri.rawQuery == null &&
            uri.path.startsWith("/udontknowho/WatchReader/releases/${if (asset) "download/" else "tag/"}") &&
            uri.path.none { it.isWhitespace() } && !uri.path.contains("/../")
    }.getOrDefault(false)

    fun checkLatest(): Release {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        val deadline = System.nanoTime() + 20_000_000_000L
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "WatchReader-UpdateCheck")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val status = conn.responseCode
            if (status != 200) throw IOException(when (status) {
                403, 429 -> "GitHub 请求受限，请稍后重试（HTTP $status）"
                404 -> "尚无可用的正式发布（HTTP 404）"
                else -> "更新服务返回 HTTP $status"
            })
            val bytes = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("更新检查已取消")
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) throw java.net.SocketTimeoutException("更新检查超时")
                    conn.readTimeout = minOf(left, 8000).toInt().coerceAtLeast(1)
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (bytes.size() + n > MAX_BYTES) throw IOException("更新响应过大")
                    bytes.write(buffer, 0, n)
                }
            }
            return parseRelease(bytes.toString("UTF-8"))
        } finally {
            conn.disconnect()
        }
    }

    /** TinyJson 负责取值。先完整校验语法，避免截断、重复键或伪造字段被当作正式版本。 */
    private class JsonValidator(private val s: String) {
        private var i = 0
        fun validate() { value(0); ws(); require(i == s.length) { "更新 JSON 存在多余内容" } }
        private fun ws() { while (i < s.length && s[i] in " \n\r\t") i++ }
        private fun take(c: Char): Boolean { ws(); return if (i < s.length && s[i] == c) { i++; true } else false }
        private fun expect(c: Char) { require(take(c)) { "更新 JSON 格式无效" } }
        private fun text(): String {
            ws(); val start = i; expect('"')
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return TinyJson.unescape(s.substring(start + 1, i - 1))
                require(c >= ' ') { "更新 JSON 字符串无效" }
                if (c == '\\') {
                    require(i < s.length) { "更新 JSON 转义不完整" }
                    val escape = s[i++]
                    if (escape == 'u') {
                        require(i + 4 <= s.length && s.substring(i, i + 4).all { it in "0123456789abcdefABCDEF" }) { "更新 JSON 转义无效" }
                        i += 4
                    } else require(escape in "\"\\/bfnrt") { "更新 JSON 转义无效" }
                }
            }
            throw IllegalArgumentException("更新 JSON 字符串未闭合")
        }
        private fun value(depth: Int) {
            require(depth < 48) { "更新 JSON 嵌套过深" }; ws()
            require(i < s.length) { "更新 JSON 不完整" }
            when (s[i]) {
                '{' -> {
                    i++; val keys = mutableSetOf<String>()
                    if (take('}')) return
                    do { require(keys.add(text())) { "更新 JSON 存在重复字段" }; expect(':'); value(depth + 1) } while (take(','))
                    expect('}')
                }
                '[' -> {
                    i++; if (take(']')) return
                    do { value(depth + 1) } while (take(','))
                    expect(']')
                }
                '"' -> text()
                else -> {
                    val start = i
                    while (i < s.length && s[i] !in ",]} \r\n\t") i++
                    val token = s.substring(start, i)
                    require(token in listOf("true", "false", "null") || Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token)) { "更新 JSON 值无效" }
                }
            }
        }
    }
}
