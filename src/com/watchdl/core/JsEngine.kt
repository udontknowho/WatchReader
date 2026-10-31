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
     * @param budgetMs 脚本预算。默认 2 秒；搜索 URL 这类「先握手再搜索」的脚本
     *   要串多次 `java.ajax`（uu书库），2 秒物理上做不到，由调用方放宽到 6 秒。
     */
    fun eval(code: String, env: Env = Env(), budgetMs: Long = TIMEOUT_MS): String {
        // 死线必须在检测之前立好：以前在 enterContext() 之后才设，而首次调用那次
        // enterContext 要加载 Rhino 的类和建工厂（数百毫秒到数秒），这段不计入预算，
        // 于是「2000ms 超时」实测能跑到 6 秒。自检里就因此偶发不过。
        Guava.set(System.currentTimeMillis() + budgetMs)
        val cx = GuardedFactory.enterContext()
        try {
            cx.setLanguageVersion(Context.VERSION_ES6)
            // -1 = 解释执行。Android 上生成字节码既慢又占内存。
            cx.setOptimizationLevel(-1)
            // 让 Rhino 定期回调 observeInstructionCount，超时才有机会中断死循环
            cx.instructionObserverThreshold = 1000

            val scope = cx.initSafeStandardObjects(null, false)            // Env 没显式给源地址时，回退到线程本地（字段求值走这条）。
            val env2 = if (env.sourceKey.isNotEmpty()) env
                       else env.copy(sourceKey = currentSourceKey())
            installApi(cx, scope, env2)
            // default 参数本机 Rhino 不认（`missing ) after formal parameters`），先降级再喂给它。
            val v = cx.evaluateString(scope, Es5Compat.rewrite(code), "source-js", 1, null)
            // 脚本返回对象数组时（legado 的 chapterList 常这样写，末尾直接写 `list`），
            // `Context.toString` 只会给出 `[object Object],[object Object]` —— 后续
            // `$.n` / `$.u` 就没有作用域可取了。数组/对象一律转成 JSON 文本。
            // 用 Scriptable 判断：本机 Rhino 里 NativeArray **不是** NativeObject 的子类。
            // 响应对象例外：`{{java.ajax(url)}}` 这种写法要的是正文，不是 `{"body":…}`。
            if (v is ResponseMark) return v.body
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
            // legado 的 java.ajax 返回的是响应对象（有 body()/header()/code()），不是裸字符串。
            // 返回裸字符串时，八叉书库这类源在 `new_url.header("location")` 上直接报
            // `Cannot find function header`。返回对象后仍能当字符串用（toString 给正文）。
            "ajax" to { a -> if (env.offline) "" else httpGet(cx, scope, str(a, 0), timeoutOf(a)) },
            // legado 的 `java.get` 是**读源变量**，但实测有源写成 `java.get(url, {}, 12000)`
            // 打请求（八叉书库、AQ 系列）。两种都支持：参数像 URL 就打请求。
            "get" to { a ->
                val x = str(a, 0)
                if (x.startsWith("http")) {
                    if (env.offline) "" else httpGet(cx, scope, x, timeoutOf(a))
                } else env.vars[x] ?: ""
            },
            "post" to { a -> if (env.offline) "" else httpPost(cx, scope, str(a, 0), str(a, 1)) },
            // 批量 GET。串行跑：手表 4 核，并发反而拖慢，还会触发站点风控。
            "ajaxAll" to { a ->
                val arr = cx.newArray(scope, 0)
                val list = (a.getOrNull(0) as? Scriptable)?.let { jsStrings(it) } ?: emptyList()
                var n = 0
                for (u in list) {
                    val v = if (env.offline) "" else httpGet(cx, scope, u, timeoutOf(a))
                    ScriptableObject.putProperty(arr, n++, v)
                }
                arr
            },
            // 书源常写的 `java.getCookie(tag, key)` / `setCookie(tag, cookie)`。
            // 我们的 cookie 表以 tag 为键存整串 cookie，读时按分号拆。
            "getCookie" to { a -> cookieGet(env, str(a, 0), str(a, 1)) },
            "setCookie" to { a -> env.cookies[str(a, 0)] = str(a, 1); "" },
            "removeCookie" to { a -> env.cookies.remove(str(a, 0)); "" },
            "clearCookie" to { a ->
                val k = str(a, 0)
                env.cookies.keys.removeAll { it == k || it.contains(k) }
                ""
            },
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
            // `java.put(k, v)`：脚本把中间结果存起来给后续步骤用（与 `java.getString` 同一张表）。
            "put" to { a ->
                env.vars[a.getOrNull(0)?.toString() ?: ""] = a.getOrNull(1)?.toString() ?: ""
                ""
            },
            // legado 的 `getString(k)`：拿 `java.put` 存过的东西，统一给字符串。
            "getString" to { a -> env.vars[str(a, 0)] ?: "" },

            // ── 登录信息 ──
            // legado 里 `upLoginData` 是回填登录界面输入框。手表上没有登录界面，
            // 但源会用 `source.getLoginInfoMap()` 读回来，所以必须真存。
            "upLoginData" to { a ->
                val m = loginMap(env.sourceKey)
                (a.getOrNull(0) as? Scriptable)?.let { o ->
                    for (id in o.ids) m[id.toString()] = ScriptableObject.getProperty(o, id.toString())?.toString() ?: ""
                    log("书源登录数据", arrayOf<Any?>(m.keys.joinToString(",")))
                }
                ""
            },
            "getLoginInfo" to { a -> loginMap(env.sourceKey)[str(a, 0)] ?: "" },
            "putLoginInfo" to { a ->
                loginMap(env.sourceKey)[str(a, 0)] = str(a, 1); ""
            },
            "getLoginInfoMap" to { _ ->
                val m = loginMap(env.sourceKey)
                val o = mapToJs(cx, scope, m)
                // legado 返回真 Java Map，源里都写 `.get("名")` 取值。
                ScriptableObject.putProperty(o, "get", JsFunction(scope) { a2 -> m[str(a2, 0)] ?: "" })
                ScriptableObject.putProperty(o, "size", m.size)
                o
            },
            "setVariable" to { a -> env.sourceVars[VAR_KEY] = str(a, 0); "" },

            // ── 中文数字章节号 ──
            // 源里写 `replace(/第([一-十]+)章/g, java.toNumChapter(result))`。
            "toNumChapter" to { a -> chineseToNum(str(a, 0)) },

            // ── 界面/桌面语义：一律空实现，但**不能报错** ──
            // 手表上没浏览器、没登录页、没有可刷新的发现页。全吞掉，
            // 源才有机会继续往下跑。
            "startBrowser" to { a -> uiNoop("startBrowser", a); "" },
            "startBrowserAwait" to { a -> uiNoop("startBrowserAwait", a); "" },
            "openUrl" to { a -> uiNoop("openUrl", a); "" },
            "open" to { a -> uiNoop("open", a); "" },
            "refreshExplore" to { _ -> uiNoop("refreshExplore", emptyArray()); "" },
            "refreshContent" to { _ -> uiNoop("refreshContent", emptyArray()); "" },
            "reLoginView" to { a -> uiNoop("reLoginView", a); "" },
            // 延迟执行 → 立即执行。表里没有消息循环，延后的任务等于永远不跑。
            "postDelayed" to { a ->
                (a.getOrNull(0) as? Function)?.let { f ->
                    runCatching { f.call(cx, scope, scope, emptyArray()) }
                }
                ""
            },
            // legado 的 java.searchBook：本引擎不做跨源搜索，空返。
            "searchBook" to { a -> uiNoop("searchBook", a); "" },

            // ── 设备号与源地址 ──
            // 源用它跟自己的服务器绑设备槽位（AQ 系列）。
            // shortcut: 进程内固定、重启会换。真被 429 拦了再接系统 ANDROID_ID。
            "deviceID" to { _ -> DEVICE_ID },
            "androidId" to { _ -> DEVICE_ID },
            "getBookSourceUrl" to { env.sourceKey },

            // ── 选择器 ──
            // `java.getElements(rule)`：在当前 result 上跑一条 legado 选择器，返回字符串数组。
            "getElements" to { a ->
                val arr = cx.newArray(scope, 0)
                var n = 0
                for (seg in LegadoSelector.chain(str(a, 0).trim()).segments) {
                    val vals = when (val p = Selector.parse(seg)) {
                        is Selector.Css -> HtmlSelect.select(env.result, p, Selector.Pick.HTML, null)
                        is Selector.Json -> JsonPathLite.select(env.result, p.path)
                        is Selector.XPath -> XPathLite.select(env.result, p.expr, Selector.Pick.HTML, null)
                        null -> emptyList()
                    }
                    for (v in vals) ScriptableObject.putProperty(arr, n++, v)
                    if (vals.isNotEmpty()) break
                }
                arr
            },
        )
        for ((name, fn) in api) ScriptableObject.putProperty(javaObj, name, JsFunction(scope, fn = fn))
        ScriptableObject.putProperty(scope, "java", javaObj)

        // `Packages.java.*` 最小壳：脚本里出现的只有 String / HashMap / Arrays /
        // Thread.sleep / Inflater / ByteArrayOutputStream / Array.newInstance
        // （5 条源的 jsLib 靠它们做 GBK 转字节与 deflate 解压）。
        // ClassShutter 仍然全禁 —— 这里给的全是本进程实现，没一个能碰到真 Java 反射。
        val javaPkg = javaPkgShell(cx, scope)
        ScriptableObject.putProperty(scope, "Packages", javaPkg)
        val javaLeaf = ScriptableObject.getProperty(javaPkg, "java") as Scriptable
        for (nm in listOf("lang", "util", "io", "security")) {
            ScriptableObject.putProperty(javaObj, nm, ScriptableObject.getProperty(javaLeaf, nm))
        }

        // 黄果封面 AES 解密：`createSymmetricCrypto(trans, key, iv).decrypt(hex)`。
        // 跟 legado/hutool 一致：入参是十六进制密文，返回 Base64 明文。
        ScriptableObject.putProperty(javaObj, "createSymmetricCrypto",
            JsFunction(scope) { a ->
                val trans = str(a, 0)
                val key = str(a, 1)
                val iv = str(a, 2)
                val o = cx.newObject(scope)
                ScriptableObject.putProperty(o, "decrypt", JsFunction(scope) { b ->
                    runCatching {
                        val c = javax.crypto.Cipher.getInstance(trans)
                        c.init(javax.crypto.Cipher.DECRYPT_MODE,
                            javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                            if (iv.isEmpty()) null
                            else javax.crypto.spec.IvParameterSpec(iv.toByteArray(Charsets.UTF_8)))
                        val plain = c.doFinal(hexBytes(str(b, 0)))
                        java.util.Base64.getEncoder().encodeToString(plain)
                    }.getOrElse { "" }
                })
                o
            })

        val sourceObj = cx.newObject(scope)
        // legado 里 `getVariable()` **不带参数**：返回源的单个变量（源拿它存 JWT/token）；
        // 带参数才是取键值变量。两种都要 —— 实测 7 条源调用无参形式。
        ScriptableObject.putProperty(sourceObj, "getVariable", JsFunction(scope) { a ->
            val k = a.firstOrNull()?.toString() ?: ""
            if (k.isEmpty()) env.sourceVars[VAR_KEY] ?: "" else env.sourceVars[k] ?: ""
        })
        ScriptableObject.putProperty(sourceObj, "setVariable", JsFunction(scope) { a ->
            env.sourceVars[VAR_KEY] = a.getOrNull(0)?.toString() ?: ""
            ""
        })
        // legado 的 `source.getLoginInfoMap()`：八叉书库用它取登录后回填的 cf/cb。
        ScriptableObject.putProperty(sourceObj, "getLoginInfoMap", JsFunction(scope) { _ ->
            val m = loginMap(env.sourceKey)
            val o = mapToJs(cx, scope, m)
            ScriptableObject.putProperty(o, "get", JsFunction(scope) { a -> m[str(a, 0)] ?: "" })
            ScriptableObject.putProperty(o, "size", m.size)
            o
        })
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
        // 源会用 `java.getSource()` 拿源对象（`java.getSource() || source`）。
        ScriptableObject.putProperty(javaObj, "getSource", JsFunction(scope) { sourceObj })

        // legado 的 `cache`：跨步骤传中间结果（八叉书库用它存 searchid）。
        // 进程内内存表，按源地址分开；手表内存小，最多留 200 条，旧的先扔。
        val cacheObj = cx.newObject(scope)
        for (name in listOf("get", "getFromMemory", "getFromDisk")) {
            ScriptableObject.putProperty(cacheObj, name, JsFunction(scope) { a ->
                synchronized(cacheMemo) { cacheMemo[cacheKey(env.sourceKey, str(a, 0))] ?: a.getOrNull(1) }
            })
        }
        for (name in listOf("put", "putMemory", "putFromDisk")) {
            ScriptableObject.putProperty(cacheObj, name, JsFunction(scope) { a ->
                synchronized(cacheMemo) { cacheMemo[cacheKey(env.sourceKey, str(a, 0))] = str(a, 1) }
                ""
            })
        }
        for (name in listOf("delete", "deleteMemory", "deleteFromDisk")) {
            ScriptableObject.putProperty(cacheObj, name, JsFunction(scope) { a ->
                synchronized(cacheMemo) { cacheMemo.remove(cacheKey(env.sourceKey, str(a, 0))) }
                ""
            })
        }
        ScriptableObject.putProperty(scope, "cache", cacheObj)
    }

    /** 把白名单 lambda 包成 Rhino 可调用的函数。[ctor] 给了才支持 `new`。 */
    private class JsFunction(
        scope: Scriptable,
        private val ctor: ((Array<Any?>) -> Any?)? = null,
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
            ctor?.invoke(args) as? Scriptable ?: throw TimeoutSignal()
    }

    private const val VAR_KEY = "__var__"

    /** 设备号（`java.deviceID` / `androidId`）：32 位十六进制，源会按这个形状校验。 */
    private val DEVICE_ID: String by lazy {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        b.joinToString("") { "%02x".format(it) }
    }

    /** JS 传进来的数字常是 Double（`1.0.toString()` 不是 "1"），不能直接 toIntOrNull。 */
    private fun num(v: Any?, dflt: Int): Int = when (v) {
        is Number -> v.toInt()
        null -> dflt
        else -> v.toString().toDoubleOrNull()?.toInt() ?: dflt
    }

    /** `java.get(url, {}, 12000)` 的第三个参数是超时毫秒。 */
    private fun timeoutOf(a: Array<Any?>): Int =
        num(a.getOrNull(2), 8000).coerceIn(1000, 30_000)

    /** 界面类 API 的出口：不做事，但留一行痕迹。 */
    private fun uiNoop(name: String, a: Array<Any?>) {
        DevLog.log("书源界面", "$name(" + a.joinToString(",") { (it?.toString() ?: "").take(60) } + ")")
    }

    /** 登录信息（`upLoginData` / `getLoginInfoMap`），按源地址隔离。 */
    private val loginMemo = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<String, String>>()

    private fun loginMap(key: String): LinkedHashMap<String, String> =
        loginMemo.computeIfAbsent(key) { LinkedHashMap() }

    private val CN_DIGIT = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /** 中文数字 → 阿拉伯数字，其他字符原样保留（`java.toNumChapter`）。 */
    private fun chineseToNum(s: String): String {
        fun isCn(c: Char) = CN_DIGIT.containsKey(c) || c == '十' || c == '百' || c == '千'
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (!isCn(s[i])) { sb.append(s[i]); i++; continue }
            var j = i
            while (j < s.length && isCn(s[j])) j++
            var total = 0
            var num = -1
            for (k in i until j) {
                val d = CN_DIGIT[s[k]]
                if (d != null) { num = d; continue }
                val unit = when (s[k]) { '十' -> 10; '百' -> 100; else -> 1000 }
                total += (if (num < 0) 1 else num) * unit     //「十一」= 11
                num = -1
            }
            if (num >= 0) total += num
            sb.append(total)
            i = j
        }
        return sb.toString()
    }

    // ── JS 数组 ↔ 字节 ──

    private fun charsetOf(name: String?): java.nio.charset.Charset = when (name?.uppercase()) {
        "GBK", "GB2312", "GB18030" -> java.nio.charset.Charset.forName("GBK")
        else -> Charsets.UTF_8
    }

    private fun bytesToStr(v: Any?, charset: String?): String = when (v) {
        null -> ""
        is Scriptable -> String(jsBytes(v), charsetOf(charset))
        else -> v.toString()
    }

    private fun jsBytes(s: Scriptable?): ByteArray {
        if (s == null) return ByteArray(0)
        val n = (ScriptableObject.getProperty(s, "length") as? Number)?.toInt() ?: 0
        return ByteArray(n) { i ->
            val x = ScriptableObject.getProperty(s, i)
            (num(x, 0) and 0xFF).toByte()
        }
    }

    private fun jsFromBytes(cx: Context, scope: Scriptable, b: ByteArray): Scriptable {
        val arr = cx.newArray(scope, b.size)
        for (i in b.indices) ScriptableObject.putProperty(arr, i, b[i].toInt() and 0xFF)
        return arr
    }

    private fun jsStrings(s: Scriptable): List<String> {
        val n = (ScriptableObject.getProperty(s, "length") as? Number)?.toInt() ?: 0
        return (0 until n).map { i -> ScriptableObject.getProperty(s, i)?.toString() ?: "" }
    }

    private fun hexBytes(s: String): ByteArray {
        val h = s.trim().removePrefix("0x").filter { it.isLetterOrDigit() }
        if (h.length < 2) return ByteArray(0)
        val n = h.length / 2
        return ByteArray(n) { i ->
            runCatching { h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }.getOrDefault(0)
        }
    }

    /** `new Packages.java.lang.String(bytes, 'UTF-8')` 的结果：能当字符串用，也能 `.getBytes()`。 */
    private class JsStr(cx: Context, scope: Scriptable, private val v: String) : ScriptableObject() {
        init {
            setParentScope(scope)
            ScriptableObject.putProperty(this, "toString", JsFunction(scope) { v })
            ScriptableObject.putProperty(this, "getBytes", JsFunction(scope) { a ->
                jsFromBytes(cx, scope, v.toByteArray(charsetOf(a.firstOrNull()?.toString())))
            })
        }
        override fun getClassName(): String = "String"
        override fun getDefaultValue(hint: Class<*>?): Any = v
        override fun toString(): String = v
    }

    /**
     * `Packages.java.*` 的最小壳。
     *
     * 只实现书源实际用到的那几个：String / HashMap / Arrays.copyOfRange /
     * Thread.sleep / zip.Inflater / io.ByteArrayOutputStream / reflect.Array。
     * 全部是本进程实现 —— ClassShutter 仍然把真 Java 类访问全禁掉。
     */
    private fun javaPkgShell(cx: Context, scope: Scriptable): Scriptable {
        val lang = cx.newObject(scope)
        val util = cx.newObject(scope)
        val io = cx.newObject(scope)
        val zip = cx.newObject(scope)
        val reflect = cx.newObject(scope)

        // java.lang.String
        val strCtor: (Array<Any?>) -> Any? = { a ->
            JsStr(cx, scope, bytesToStr(a.getOrNull(0), a.getOrNull(1)?.toString()))
        }
        ScriptableObject.putProperty(lang, "String", JsFunction(scope,
            fn = { a -> bytesToStr(a.getOrNull(0), a.getOrNull(1)?.toString()) }, ctor = strCtor))

        val byteCls = cx.newObject(scope)
        ScriptableObject.putProperty(byteCls, "TYPE", "byte")
        ScriptableObject.putProperty(lang, "Byte", byteCls)

        // 不能真睡：单次脚本预算只有 2 秒，而源里写的是 sleep(3400~8900)。
        val thread = cx.newObject(scope)
        ScriptableObject.putProperty(thread, "sleep", JsFunction(scope) { _ -> "" })
        ScriptableObject.putProperty(lang, "Thread", thread)

        // java.lang.reflect.Array.newInstance(Byte.TYPE, 8192) → 全 0 的数字数组
        val arrCls = cx.newObject(scope)
        ScriptableObject.putProperty(arrCls, "newInstance", JsFunction(scope) { a ->
            jsFromBytes(cx, scope, ByteArray(num(a.getOrNull(1), 0)))
        })
        ScriptableObject.putProperty(reflect, "Array", arrCls)
        ScriptableObject.putProperty(lang, "reflect", reflect)

        // java.util.HashMap
        fun newMap(): Scriptable {
            val m = LinkedHashMap<String, Any?>()
            val o = cx.newObject(scope)
            ScriptableObject.putProperty(o, "put", JsFunction(scope) { a ->
                m[a.getOrNull(0)?.toString() ?: ""] = a.getOrNull(1); ""
            })
            ScriptableObject.putProperty(o, "get", JsFunction(scope) { a ->
                m[a.firstOrNull()?.toString() ?: ""] ?: ""
            })
            ScriptableObject.putProperty(o, "containsKey", JsFunction(scope) { a ->
                m.containsKey(a.firstOrNull()?.toString() ?: "")
            })
            ScriptableObject.putProperty(o, "keySet", JsFunction(scope) { _ ->
                val ks = cx.newArray(scope, 0)
                var n = 0
                for (k in m.keys) ScriptableObject.putProperty(ks, n++, k)
                ks
            })
            return o
        }
        ScriptableObject.putProperty(util, "HashMap", JsFunction(scope, fn = { newMap() }, ctor = { newMap() }))

        // java.util.Arrays.copyOfRange
        val arrays = cx.newObject(scope)
        ScriptableObject.putProperty(arrays, "copyOfRange", JsFunction(scope) { a ->
            val b = jsBytes(a.getOrNull(0) as? Scriptable)
            val from = num(a.getOrNull(1), 0).coerceIn(0, b.size)
            val to = num(a.getOrNull(2), b.size).coerceIn(from, b.size)
            jsFromBytes(cx, scope, b.copyOfRange(from, to))
        })
        ScriptableObject.putProperty(util, "Arrays", arrays)

        // java.util.zip.Inflater(true) —— 实测源拿它解 deflate 的接包
        val inflater: (Array<Any?>) -> Any? = { a ->
            val inf = java.util.zip.Inflater(a.firstOrNull()?.toString() == "true")
            val o = cx.newObject(scope)
            ScriptableObject.putProperty(o, "setInput", JsFunction(scope) { b ->
                val bytes = jsBytes(b.getOrNull(0) as? Scriptable)
                val off = num(b.getOrNull(1), 0).coerceIn(0, bytes.size)
                val len = num(b.getOrNull(2), bytes.size).coerceIn(0, bytes.size) - off
                if (len > 0) inf.setInput(bytes, off, len)
                ""
            })
            ScriptableObject.putProperty(o, "inflate", JsFunction(scope) { b ->
                val dst = b.getOrNull(0) as? Scriptable
                val size = jsBytes(dst).size
                val tmp = ByteArray(size)
                val n = runCatching { inf.inflate(tmp) }.getOrDefault(0)
                if (dst != null) for (i in 0 until n) ScriptableObject.putProperty(dst, i, tmp[i].toInt() and 0xFF)
                n
            })
            ScriptableObject.putProperty(o, "finished", JsFunction(scope) { _ -> inf.finished() })
            o
        }
        ScriptableObject.putProperty(zip, "Inflater", JsFunction(scope, fn = inflater, ctor = inflater))
        ScriptableObject.putProperty(util, "zip", zip)

        // java.io.ByteArrayOutputStream
        val baos: (Array<Any?>) -> Any? = { _ ->
            val bos = java.io.ByteArrayOutputStream()
            val o = cx.newObject(scope)
            ScriptableObject.putProperty(o, "write", JsFunction(scope) { a ->
                val bytes = jsBytes(a.getOrNull(0) as? Scriptable)
                val off = num(a.getOrNull(1), 0).coerceIn(0, bytes.size)
                val len = num(a.getOrNull(2), bytes.size).coerceIn(0, bytes.size) - off
                if (len > 0) bos.write(bytes, off, len)
                ""
            })
            ScriptableObject.putProperty(o, "toByteArray", JsFunction(scope) { _ ->
                jsFromBytes(cx, scope, bos.toByteArray())
            })
            o
        }
        ScriptableObject.putProperty(io, "ByteArrayOutputStream", JsFunction(scope, fn = baos, ctor = baos))

        val javaPkg = cx.newObject(scope)
        ScriptableObject.putProperty(javaPkg, "lang", lang)
        ScriptableObject.putProperty(javaPkg, "util", util)
        ScriptableObject.putProperty(javaPkg, "io", io)

        // java.security.MessageDigest（七猫聚合拿它算 MD5 签名）
        val security = cx.newObject(scope)
        val mdInst: (Array<Any?>) -> Any? = { a ->
            val alg = str(a, 0)
            val o = cx.newObject(scope)
            ScriptableObject.putProperty(o, "digest", JsFunction(scope) { b ->
                val data = when (val v = b.firstOrNull()) {
                    null -> ByteArray(0)
                    is Scriptable -> jsBytes(v)
                    else -> v.toString().toByteArray(Charsets.UTF_8)
                }
                runCatching {
                    jsFromBytes(cx, scope, java.security.MessageDigest.getInstance(alg).digest(data))
                }.getOrElse { cx.newArray(scope, 0) }
            })
            o
        }
        // 源都以静态方法形态调用：`MessageDigest.getInstance(alg)`，类对象只当容器用。
        val mdCls = cx.newObject(scope)
        ScriptableObject.putProperty(mdCls, "getInstance", JsFunction(scope, fn = mdInst))
        ScriptableObject.putProperty(security, "MessageDigest", mdCls)
        ScriptableObject.putProperty(javaPkg, "security", security)

        // javax.crypto.Mac + spec.SecretKeySpec（哔咔漫画的 HmacSHA256 签名）
        val crypto = cx.newObject(scope)
        val macInst: (Array<Any?>) -> Any? = { a ->
            val alg = str(a, 0)
            var key = ByteArray(0)
            val o = cx.newObject(scope)
            ScriptableObject.putProperty(o, "init", JsFunction(scope) { b ->
                key = jsBytes((b.firstOrNull() as? Scriptable)?.let { ScriptableObject.getProperty(it, "__bytes") } as? Scriptable)
                ""
            })
            ScriptableObject.putProperty(o, "doFinal", JsFunction(scope) { b ->
                val data = when (val v = b.firstOrNull()) {
                    null -> ByteArray(0)
                    is Scriptable -> jsBytes(v)
                    else -> v.toString().toByteArray(Charsets.UTF_8)
                }
                runCatching {
                    val mac = javax.crypto.Mac.getInstance(alg)
                    mac.init(javax.crypto.spec.SecretKeySpec(key, alg))
                    jsFromBytes(cx, scope, mac.doFinal(data))
                }.getOrElse { cx.newArray(scope, 0) }
            })
            o
        }
        val macCls = cx.newObject(scope)
        ScriptableObject.putProperty(macCls, "getInstance", JsFunction(scope, fn = macInst))
        ScriptableObject.putProperty(crypto, "Mac", macCls)
        val spec = cx.newObject(scope)
        val keySpec: (Array<Any?>) -> Any? = { a ->
            val o = cx.newObject(scope)
            ScriptableObject.putProperty(o, "__bytes", jsFromBytes(cx, scope,
                when (val v = a.firstOrNull()) {
                    is Scriptable -> jsBytes(v)
                    else -> (v?.toString() ?: "").toByteArray(Charsets.UTF_8)
                }))
            o
        }
        ScriptableObject.putProperty(spec, "SecretKeySpec", JsFunction(scope, ctor = keySpec, fn = keySpec))
        ScriptableObject.putProperty(crypto, "spec", spec)
        val javaxPkg = cx.newObject(scope)
        ScriptableObject.putProperty(javaxPkg, "crypto", crypto)

        val root = cx.newObject(scope)
        ScriptableObject.putProperty(root, "java", javaPkg)
        ScriptableObject.putProperty(root, "javax", javaxPkg)
        return root
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

    private fun httpGet(cx: Context, scope: Scriptable, url: String, timeoutMs: Int = 8000): Any {
        if (url.isBlank() || !url.startsWith("http")) return ""
        return try {
            response(cx, scope, Http.fetch(url, "GET", null, emptyMap(), null, timeoutMs))
        } catch (e: Exception) {
            // 吞掉异常但必须留痕迹：八叉书库就是这样被误判成「缺 header()」的。
            log("书源请求失败", arrayOf(url.take(80), e.message ?: e.javaClass.simpleName))
            ""
        }
    }

    private fun httpPost(cx: Context, scope: Scriptable, url: String, body: String): Any {
        if (url.isBlank() || !url.startsWith("http")) return ""
        return try {
            response(cx, scope, Http.fetch(url, "POST", body, emptyMap(), null, 8000))
        } catch (e: Exception) {
            log("书源请求失败", arrayOf(url.take(80), e.message ?: e.javaClass.simpleName))
            ""
        }
    }

    /**
     * 把一次请求包成 legado 那样的响应对象。
     *
     * 同时**可当字符串用**：[getDefaultValue] 返回正文，所以 `r + ""`、`String(r)`、
     * 以及引擎里 `{{java.ajax(url)}}` 都还是取正文，老源不会因此挂掉。
     */
    /** 响应对象标记：`eval` 遇到它就只取正文，不 JSON 序列化。 */
    private interface ResponseMark {
        val body: String
    }

    private fun response(cx: Context, scope: Scriptable, r: Http.Response): Scriptable {
        val text = r.body
        val o = object : ScriptableObject(), ResponseMark {
            override val body: String = text
            override fun getClassName(): String = "Response"
            override fun getDefaultValue(hint: Class<*>?): Any = text
            override fun toString(): String = text
        }
        o.setParentScope(scope)
        o.setPrototype(ScriptableObject.getObjectPrototype(scope))
        ScriptableObject.putProperty(o, "body", JsFunction(scope) { text })
        ScriptableObject.putProperty(o, "string", JsFunction(scope) { text })
        ScriptableObject.putProperty(o, "toString", JsFunction(scope) { text })
        ScriptableObject.putProperty(o, "code", JsFunction(scope) { r.code })
        ScriptableObject.putProperty(o, "url", JsFunction(scope) { r.finalUrl })
        ScriptableObject.putProperty(o, "header", JsFunction(scope) { a ->
            r.headers[(a.firstOrNull()?.toString() ?: "").lowercase()] ?: ""
        })
        ScriptableObject.putProperty(o, "headers", JsFunction(scope) { _ ->
            val m = cx.newObject(scope)
            for ((k, v) in r.headers) ScriptableObject.putProperty(m, k, v)
            m
        })
        return o
    }

    /** 进程内 cache 表。键 = 源地址 + "|" + 用户键。 */
    private val cacheMemo = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    private fun cacheKey(sourceKey: String, key: String) = "$sourceKey|$key"

    /** `java.getCookie(tag, key)`：从「tag → 整串 cookie」里取某项。 */
    private fun cookieGet(env: Env, tag: String, key: String): String {
        val raw = env.cookies[tag] ?: return env.cookies[key] ?: ""
        for (part in raw.split(';')) {
            val kv = part.trim()
            if (kv.startsWith("$key=")) return kv.substring(key.length + 1)
        }
        return env.cookies[key] ?: ""
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
