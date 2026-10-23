package com.watchdl.core

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 书源脚本引擎（legado 的 `@js:` 与 `<js>`）。
 *
 * **保守模式**：手表只有 1 GB 内存，脚本是第三方代码，所以做了三层限制：
 *
 * 1. **禁 Java 类访问**（[ClassShutter]）—— 脚本不能 `new java.util.ArrayList()`。
 *    实测 yiove 源里 `Packages.` 出现 310 次，放开的代价是内存与 CPU 完全不可控。
 * 2. **只暴露白名单 API** —— 见 [installApi]，网络、编码这些常用能力。
 * 3. **超时中断** —— 死循环最多跑 [TIMEOUT_MS] 毫秒就被掐断。
 *
 * 关掉 JIT（`setOptimizationLevel(-1)`）：Android 上生成字节码既慢又占内存，
 * 解释执行对书源这种小脚本足够。
 */
object JsEngine {

    /** 单次执行上限。手表上宁可不跑，也不能卡住界面。 */
    private const val TIMEOUT_MS = 2000L

    /** 脚本执行失败。调用方应当降级处理，不要把书源整体判死。 */
    class JsError(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 当前线程正在处理的源地址（`bookSourceUrl`）。
     *
     * [FieldRule] 的 `@js:` 字段也会调 `source.getKey()`，但字段求值拿不到
     * [BookSourceRule]。用线程本地传递，避免给每个字段函数都加一个参数。
     * [com.watchdl.core.SourceEngine] 在每次求值前 [setSourceKey]。
     */
    private val threadSourceKey = ThreadLocal.withInitial { "" }

    fun setSourceKey(key: String) = threadSourceKey.set(key)

    /** 当前线程的源地址（没有则为空串）。 */
    fun currentSourceKey(): String = threadSourceKey.get() ?: ""

    /** 一次执行注入的上下文变量（名字对齐 legado，脚本才不用改）。 */
    data class Env(
        /** 上一步的结果（legado 的 `result`）。 */
        val result: String = "",
        /** 当前页 URL（`baseUrl`）。 */
        val baseUrl: String = "",
        /** 书籍信息（`book` 的字段）。 */
        val book: Map<String, String> = emptyMap(),
        /** 章节信息（`chapter` 的字段）。 */
        val chapter: Map<String, String> = emptyMap(),
        /** 当前请求的响应体（`response` / `src`）。 */
        val response: String = "",
        /** 书源自定义变量（`source.getVariable` / `putVariable`）。 */
        val vars: MutableMap<String, String> = mutableMapOf(),
        /**
         * 书源的 `bookSourceUrl`（legado 的 `source.getKey()`）。
         *
         * 大量书源的 searchUrl / header 里写 `{{source.getKey()}}`，
         * 用它拼出本站地址。不给的话脚本直接抛
         * `TypeError: Cannot find function getKey`（实测 2 条源）。
         */
        val sourceKey: String = "",
        /** 书源变量持久层（legado 的 `source.put` / `source.get`）。 */
        val sourceVars: MutableMap<String, String> = mutableMapOf(),
        /** 本会话的 cookie 表（`cookie.removeCookie` 等）。 */
        val cookies: MutableMap<String, String> = mutableMapOf(),
        /**
         * 禁止脚本联网。导入期解析 header 时置真。
         *
         * 书源 JSON 是第三方文件。`@js:` header 的注释说它「只产生静态请求头」，
         * 但脚本里能调 `java.ajax` 发任意请求 —— 导入一份恶意源就能上报设备信息。
         * 需要联网的 header 脚本本来就不该在导入期跑，运行时再跑不迟。
         */
        val offline: Boolean = false,
    )

    /**
     * 求值一段脚本，返回字符串结果。
     *
     * @param code 脚本正文（已剥掉 `@js:` 前缀）
     * @param env 注入的上下文
     */
    fun eval(code: String, env: Env = Env()): String {
        // 死线必须在检测之前立好：以前在 enterContext() 之后才设，而首次调用那次
        // enterContext 要加载 Rhino 的类和建工厂（数百毫秒到数秒），这段不计入预算，
        // 于是「2000ms 超时」实测能跑到 6 秒。自检里就因此偶发不过。
        Guava.set(System.currentTimeMillis() + TIMEOUT_MS)
        val cx = GuardedFactory.enterContext()
        try {
            cx.setLanguageVersion(Context.VERSION_ES6)
            // -1 = 解释执行。Android 上生成字节码既慢又占内存。
            cx.setOptimizationLevel(-1)
            // 让 Rhino 定期回调 observeInstructionCount，超时才有机会中断死循环
            cx.instructionObserverThreshold = 1000

            val scope = cx.initSafeStandardObjects(null, false)
            // Env 没显式给源地址时，回退到线程本地（字段求值走这条）。
            val env2 = if (env.sourceKey.isNotEmpty()) env
                       else env.copy(sourceKey = currentSourceKey())
            installApi(cx, scope, env2)
            val v = cx.evaluateString(scope, code, "source-js", 1, null)
            // 脚本返回对象数组时（legado 的 chapterList 常这样写，末尾直接写 `list`），
            // `Context.toString` 只会给出 `[object Object],[object Object]` —— 后续
            // `$.n` / `$.u` 就没有作用域可取了。数组/对象一律转成 JSON 文本。
            // 用 Scriptable 判断：本机 Rhino 里 NativeArray **不是** NativeObject 的子类。
            if (v is Scriptable) {
                ScriptableObject.putProperty(scope, "__r", v)
                return Context.toString(cx.evaluateString(scope, "JSON.stringify(__r)",
                    "source-js", 1, null))
            }
            return Context.toString(v)
        } catch (e: TimeoutSignal) {
            throw JsError("脚本超时（>${TIMEOUT_MS}ms）", e)
        } catch (e: Exception) {
            throw JsError("脚本出错：${e.message?.take(120)}", e)
        } finally {
            Guava.clear()
            Context.exit()
        }
    }

    /** 超时信号。用 Error 而不是 Exception —— Rhino 内部会吞 RuntimeException。 */
    private class TimeoutSignal : Error("script timeout")

    /**
     * 工厂 + 超时检查。
     *
     * `observeInstructionCount` 每 [Context.getInstructionObserverThreshold] 条指令回调一次，
     * 在这里比对死线。这是 Rhino 官方推荐的「脚本运行超时」做法。
     */
    private object GuardedFactory : ContextFactory() {
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val d = Guava.get()
            if (d > 0 && System.currentTimeMillis() > d) throw TimeoutSignal()
        }
    }

    /**
     * 死线。[GuardedFactory] 是单例，但 eval 是并发的（多源搜索 5 路、下载 3 路），
     * 以前用一个全局变量，各线程互相覆盖：A 刚立好死线，B 进了 finally 就清零，
     * A 的死循环再也不会被中断；反过来 B 的死线也会被 A 覆盖，正常脚本被误杀。
     * 改成 ThreadLocal：每个 eval 各自一份，[observeInstructionCount] 在同一个线程上跑。
     */
    private object Guava {
        private val tl = ThreadLocal.withInitial { 0L }
        fun set(v: Long) = tl.set(v)
        fun get(): Long = tl.get() ?: 0L
        fun clear() = tl.remove()
    }

    /**
     * 注入白名单 API。名字与 legado 保持一致，书源脚本才不用改。
     *
     * 沙箱靠 [ClassShutter]：它在 [Context] 上设置，拦掉一切来自脚本的 Java 类访问。
     * 不设的话脚本能直接 `java.lang.Runtime.getRuntime().exec(...)`。
     */
    private fun installApi(cx: Context, scope: Scriptable, env: Env) {
        cx.setClassShutter(ClassShutter { className ->
            // 只放行 Rhino 自己的运行期类，否则脚本连 String/Object 都用不了。
            // 书源脚本要用的 Java 能力一律走下面的白名单 API。
            className.startsWith("org.mozilla.javascript.")
        })

        ScriptableObject.putProperty(scope, "result", env.result)
        ScriptableObject.putProperty(scope, "baseUrl", env.baseUrl)
        ScriptableObject.putProperty(scope, "response", env.response)
        ScriptableObject.putProperty(scope, "src", env.response)
        ScriptableObject.putProperty(scope, "book", mapToJs(cx, scope, env.book))
        ScriptableObject.putProperty(scope, "chapter", mapToJs(cx, scope, env.chapter))
        // legado 的 `cookie` 不是字符串而是一个对象：
        //   cookie.removeCookie(key)  清登录态（大量书源在 searchUrl 开头调它）
        //   cookie.getCookie(key)     读
        // 以前这里是空字符串 ""，脚本一调 .removeCookie 就 TypeError。
        val cookieObj = cx.newObject(scope)
        ScriptableObject.putProperty(cookieObj, "removeCookie",
            JsFunction(scope) { a -> env.cookies.remove(a.firstOrNull()?.toString() ?: ""); "" })
        ScriptableObject.putProperty(cookieObj, "putCookie",
            JsFunction(scope) { a ->
                env.cookies[a.getOrNull(0)?.toString() ?: ""] = a.getOrNull(1)?.toString() ?: ""
                ""
            })
        ScriptableObject.putProperty(cookieObj, "getCookie",
            JsFunction(scope) { a -> env.cookies[a.firstOrNull()?.toString() ?: ""] ?: "" })
        ScriptableObject.putProperty(scope, "cookie", cookieObj)

        // 自定义变量当裸名注入（`{{java.encodeURI(key)}}` 里的 key 就是这样用的）
        for ((k, v) in env.vars) {
            if (!ScriptableObject.hasProperty(scope, k)) ScriptableObject.putProperty(scope, k, v)
        }

        val javaObj = cx.newObject(scope)
        val api: List<Pair<String, (Array<Any?>) -> Any?>> = listOf(
            "ajax" to { a -> if (env.offline) "" else httpGet(str(a, 0)) },
            "get" to { a -> if (env.offline) "" else httpGet(str(a, 0)) },
            "post" to { a -> if (env.offline) "" else httpPost(str(a, 0), str(a, 1)) },
            // URL 编码。legado 脚本常用 `{{java.encodeURI(key)}}` 拼搜索地址。
            "encodeURI" to { a -> pctEncode(str(a, 0), keepSlash = true) },
            "encodeURIComponent" to { a -> pctEncode(str(a, 0), keepSlash = false) },
            "decodeURI" to { a -> decodeUri(str(a, 0)) },
            "decodeURIComponent" to { a -> decodeUri(str(a, 0)) },
            "base64Encode" to { a -> b64Enc(str(a, 0)) },
            "base64Decode" to { a -> b64Dec(str(a, 0)) },
            "md5Encode" to { a -> md5(str(a, 0)) },
            "timeFormat" to { a -> timeFormat(a) },
            "timeFormatUTC" to { a -> timeFormat(a) },
            // 十六进制/base64 互转。部分源用它们编解码正文地址。
            "hexDecodeToString" to { a -> hexDecode(str(a, 0)) },
            "hexEncodeToString" to { a -> hexEncode(str(a, 0)) },
            "strToBytes" to { a -> str(a, 0).toByteArray(Charsets.UTF_8).joinToString(",") { it.toString() } },
            // 简繁转换。源里常写 `java.t2s(result)` 把繁体书名转简体。
            "t2s" to { a -> T2s.toSimplified(str(a, 0)) },
            "s2t" to { a -> str(a, 0) },
            // 取当前时间戳（秒）。早期源用它防缓存。
            "time" to { _ -> (System.currentTimeMillis() / 1000).toString() },
            "getWebViewUA" to { _ -> UA },
            "log" to { a -> log("书源", a); "" },
            // 长 toast 与普通 toast 同样不弹（手表上会顶掉界面）
            "longToast" to { a -> log("书源toast", a); "" },
            // 手表上不弹 toast：脚本刷屏会把界面顶掉。只记日志。
            "toast" to { a -> log("书源toast", a); "" },
            // `java.put(k, v)`：脚本把中间结果存起来给后续步骤用。
            // 实测七猫小说用 `java.put("headers", ...)` 传请求头，
            // 不给就抛 `TypeError: Cannot find function put in object`。
            // 注意：`java.get` 已存在（httpGet），不能再重定义成读变量。
            "put" to { a ->
                env.vars[a.getOrNull(0)?.toString() ?: ""] = a.getOrNull(1)?.toString() ?: ""
                ""
            },
        )
        for ((name, fn) in api) ScriptableObject.putProperty(javaObj, name, JsFunction(scope, fn))
        ScriptableObject.putProperty(scope, "java", javaObj)

        val sourceObj = cx.newObject(scope)
        ScriptableObject.putProperty(sourceObj, "getVariable",
            JsFunction(scope) { env.vars[it.firstOrNull()?.toString() ?: ""] ?: "" })
        ScriptableObject.putProperty(sourceObj, "putVariable",
            JsFunction(scope) { a ->
                env.vars[a.getOrNull(0)?.toString() ?: ""] = a.getOrNull(1)?.toString() ?: ""
                ""
            })
        // `source.getKey()` 返回 bookSourceUrl —— 书源用它拼本站地址。
        ScriptableObject.putProperty(sourceObj, "getKey",
            JsFunction(scope) { env.sourceKey })
        // 也有书源直接写 `source.key`（属性访问，不调方法）。实测书迷楼。
        ScriptableObject.putProperty(sourceObj, "key", env.sourceKey)
        // `source.put(k, v)` / `source.get(k)`：书源级持久变量（legado 的 SourceApi）。
        ScriptableObject.putProperty(sourceObj, "put",
            JsFunction(scope) { a ->
                env.sourceVars[a.getOrNull(0)?.toString() ?: ""] = a.getOrNull(1)?.toString() ?: ""
                ""
            })
        ScriptableObject.putProperty(sourceObj, "get",
            JsFunction(scope) { a -> env.sourceVars[a.firstOrNull()?.toString() ?: ""] ?: "" })
        ScriptableObject.putProperty(scope, "source", sourceObj)
    }

    /** 把白名单 lambda 包成 Rhino 可调用的函数。 */
    private class JsFunction(
        scope: Scriptable,
        private val fn: (Array<Any?>) -> Any?,
    ) : ScriptableObject(), Function {
        private val parent: Scriptable = scope

        override fun getClassName(): String = "Function"
        override fun getParentScope(): Scriptable = parent
        override fun setParentScope(s: Scriptable?) {}
        override fun getDefaultValue(hint: Class<*>?): Any = ""
        override fun get(i: Int, start: Scriptable?): Any? = NOT_FOUND
        override fun get(name: String, start: Scriptable?): Any? = NOT_FOUND
        override fun has(i: Int, start: Scriptable?): Boolean = false
        override fun has(name: String, start: Scriptable?): Boolean = false
        override fun put(i: Int, start: Scriptable?, value: Any?) {}
        override fun put(name: String, start: Scriptable?, value: Any?) {}
        override fun delete(i: Int) {}
        override fun delete(name: String) {}
        override fun getIds(): Array<Any> = emptyArray()
        override fun getPrototype(): Scriptable? = null
        override fun setPrototype(p: Scriptable?) {}

        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            fn(args)

        override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
            throw TimeoutSignal()
    }

    private fun mapToJs(cx: Context, scope: Scriptable, m: Map<String, String>): Scriptable {
        val o = cx.newObject(scope)
        for ((k, v) in m) ScriptableObject.putProperty(o, k, v)
        return o
    }

    // ── 白名单实现 ──
    private fun str(a: Array<Any?>, i: Int): String = a.getOrNull(i)?.toString() ?: ""

    private fun log(tag: String, a: Array<Any?>) {
        println("[$tag] " + a.joinToString(" ") { it?.toString() ?: "null" })
    }

    private fun httpGet(url: String): String {
        if (url.isBlank() || !url.startsWith("http")) return ""
        return runCatching { Http.fetch(url, "GET", null, emptyMap(), null, 8000).body }.getOrDefault("")
    }

    private fun httpPost(url: String, body: String): String {
        if (url.isBlank() || !url.startsWith("http")) return ""
        return runCatching { Http.fetch(url, "POST", body, emptyMap(), null, 8000).body }.getOrDefault("")
    }

    /** 暴露给测试（私有成员测不到）。 */
    /** 请求头里的默认 UA，给 `java.getWebViewUA()` 用。 */
    internal val EXTRA_UA get() = UA

    private val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    internal fun EXTRA_HEX_DECODE(s: String): String = hexDecode(s)

    private fun hexDecode(s: String): String {
        val h = s.trim().removePrefix("0x")
        if (h.isEmpty() || h.length % 2 != 0) return ""
        return runCatching {
            ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                .toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun hexEncode(s: String): String =
        s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private fun b64Enc(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** JS 的 `encodeURI`（keepSlash=false 时等同 encodeURIComponent）。 */
    private fun pctEncode(s: String, keepSlash: Boolean): String {
        val keep = "-_.!~*'()" + if (keepSlash) ";/?:@&=+$," else ""
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || keep.indexOf(c.toChar()) >= 0)) {
                sb.append(c.toChar())
            } else sb.append("%%%02X".format(c))
        }
        return sb.toString()
    }

    private fun decodeUri(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun b64Dec(s: String): String =
        runCatching { String(java.util.Base64.getDecoder().decode(s), Charsets.UTF_8) }.getOrDefault("")

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun timeFormat(a: Array<Any?>): String {
        val t = a.getOrNull(0)?.toString()?.toLongOrNull() ?: System.currentTimeMillis()
        val fmt = a.getOrNull(1)?.toString() ?: "yyyy-MM-dd HH:mm"
        // legado 用 YYYY/DD 的写法，Java 需要小写
        val pattern = fmt.replace("YYYY", "yyyy").replace("DD", "dd")
        return runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
                .format(java.util.Date(if (t < 10_000_000_000L) t * 1000 else t))
        }.getOrDefault("")
    }
}
