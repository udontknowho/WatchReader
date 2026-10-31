import com.watchdl.core.Es5Compat
import com.watchdl.core.JsEngine

/**
 * API 面自检：书上写到的每个 `java.*` 名字都得在。
 *
 * 起因：连续三轮都是「跑源报错 → 补一个 API」，用户原话「最开始就说了做个完整的引擎」。
 * 这个自检把 84 条源里出现过的名字一次列全，缺一个就红 —— 以后再漏当场发现。
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("  ✓ $name") else { failures++; println("  ✗ $name  $detail") }
    }
    fun eval(code: String): String =
        runCatching { JsEngine.eval(code) }.getOrElse { "<抛错:${it.message}>" }

    println("[JsApi] 名字齐全")
    // 来源：app/assets/sources/legado.json + yiove 一周 25 条 + 合集 9 条，统计见笔记本页
    val names = listOf(
        "ajax", "ajaxAll", "get", "post", "put", "getString",
        "getCookie", "setCookie", "removeCookie", "clearCookie",
        "encodeURI", "encodeURIComponent", "decodeURI", "decodeURIComponent",
        "base64Encode", "base64Decode", "md5Encode",
        "hexEncodeToString", "hexDecodeToString", "strToBytes",
        "timeFormat", "timeFormatUTC", "time", "t2s", "s2t", "getWebViewUA",
        "log", "toast", "longToast",
        "upLoginData", "getLoginInfo", "putLoginInfo", "getLoginInfoMap", "setVariable",
        "toNumChapter",
        "startBrowser", "startBrowserAwait", "openUrl", "open",
        "refreshExplore", "refreshContent", "reLoginView", "postDelayed", "searchBook",
        "deviceID", "androidId", "getBookSourceUrl", "getElements", "getSource",
        "createSymmetricCrypto", "lang", "util", "io",
    )
    val js = "var miss=[];" +
        names.joinToString("") { "if(typeof java.$it==='undefined')miss.push('$it');" } +
        "miss.join(',')"
    val missing = eval(js)
    check("java.* 全在（${names.size} 个）", missing.isBlank(), "缺 $missing")

    // legado 里没有 getCookieStore，源自己拿它当探测器：给了反而走错分支。
    check("getCookieStore 故意不给", eval("typeof java.getCookieStore") == "undefined")

    check("source.* 全在",
        eval("['getVariable','putVariable','setVariable','get','put','getKey','key']" +
            ".filter(function(k){return typeof source[k]==='undefined'}).join(',')").isBlank())
    check("cache.* 全在",
        eval("['get','put','delete','getFromMemory','putMemory']" +
            ".filter(function(k){return typeof cache[k]==='undefined'}).join(',')").isBlank())
    // 注意：Rhino 对这个 slim 包装进去的宿主函数报 `typeof` 为 "object" 而不是 "function"。
    // 全量 84 条源里一处 `typeof java.X` 都没有，所以先不管；将来真撞上再说。
    val pkgTypes = eval(
        "var v=[Packages.java.lang.String, Packages.java.util.HashMap, Packages.java.util.zip.Inflater," +
            " Packages.java.io.ByteArrayOutputStream, Packages.java.lang.Thread.sleep];" +
            " v.filter(function(x){return typeof x==='undefined'}).length")
    check("Packages 壳", pkgTypes == "0", "缺 $pkgTypes 个")

    println("[JsApi] 语义")
    check("put→getString 同表", eval("java.put('a','7'); java.getString('a')") == "7")
    check("get 非 URL 读变量", eval("java.put('b','8'); java.get('b')") == "8")
    check("getString 缺失给空串", eval("java.getString('没有这个键')") == "")
    check("toNumChapter 中文数字", eval("java.toNumChapter('一百二十三')") == "123")
    check("toNumChapter 保留非数字", eval("java.toNumChapter('第12章')") == "第12章")

    check("upLoginData → getLoginInfoMap",
        eval("java.upLoginData({x:'9'}); java.getLoginInfoMap().get('x')") == "9")
    check("putLoginInfo → getLoginInfo",
        eval("java.putLoginInfo('y','11'); java.getLoginInfo('y')") == "11")

    check("设备号 32 位十六进制",
        eval("java.deviceID()").matches(Regex("[0-9a-f]{32}")) &&
            eval("java.androidId()") == eval("java.deviceID()"))

    println("[JsApi] Packages 壳")
    check("HashMap put/get",
        eval("var m=new Packages.java.util.HashMap(); m.put('k','v'); m.get('k')") == "v")
    check("ByteArrayOutputStream + String",
        eval(
            "var o=new Packages.java.io.ByteArrayOutputStream();" +
                " o.write([65,66],0,2);" +
                " String(new Packages.java.lang.String(o.toByteArray(),'UTF-8'))"
        ) == "AB")
    check("String.getBytes 往返",
        eval("var b=new Packages.java.lang.String('AB').getBytes('UTF-8'); b[0]") == "65")
    check("Arrays.copyOfRange",
        eval("var a=Packages.java.util.Arrays.copyOfRange([1,2,3],1,3); a.length + ':' + a[0]") == "2:2")
    // 静态方法形态：源写 `MessageDigest.getInstance('MD5')`，不是直接调用
    val md = eval(
            "var b=Packages.java.security.MessageDigest.getInstance('MD5')" +
                ".digest(new Packages.java.lang.String('a').getBytes('UTF-8'));" +
                " var h=''; for(var i=0;i<b.length;i++) h+=('0'+(b[i]&255).toString(16)).slice(-2); h"
        )
    check("MessageDigest MD5", md == "0cc175b9c0f1b6a831c399e269772661", "得 $md")
    val hmac = eval(
            "var m=Packages.javax.crypto.Mac.getInstance('HmacSHA256');" +
                " m.init(new Packages.javax.crypto.spec.SecretKeySpec(new Packages.java.lang.String('k').getBytes('UTF-8'),'HmacSHA256'));" +
                " var b=m.doFinal(new Packages.java.lang.String('a').getBytes('UTF-8')); b.length"
        )
    check("HmacSHA256", hmac == "32", "得 $hmac")
    check("source.getLoginInfoMap",
        eval("typeof source.getLoginInfoMap") != "undefined")

    println("[JsApi] 界面类 API 不抛错")
    check("startBrowser/openUrl/refresh* 无副作用",
        eval(
            "java.startBrowser('about:blank','t','<b>x</b>'); java.startBrowserAwait('u','t');" +
                " java.openUrl('u'); java.open('login'); java.refreshExplore();" +
                " java.refreshContent(); java.reLoginView(false); 'ok'"
        ) == "ok")
    check("postDelayed 立即执行",
        eval("java.postDelayed(function(){ java.put('ran','1') }, 100); java.getString('ran')") == "1")

    println("[Es5Compat] 可选链与空值合并")
    check("?. 命中", Es5Compat.rewrite("a?.b").contains("void 0"))
    check("?? 命中", Es5Compat.rewrite("a ?? b").contains("!= null"))
    check("字符串里的 ?. 不动", Es5Compat.rewrite("var s='a?.b'") == "var s='a?.b'")
    check("?. 取到值", eval("var o={a:{b:2}}; String(o?.a?.b)") == "2")
    check("?. 断链给 undefined", eval("var o={}; String(o?.a?.b)") == "undefined")
    check("?. 调用", eval("var o={f:function(x){return x+1}}; String(o?.f(1))") == "2")
    check("?. 下标", eval("var o={a:[5]}; String(o?.a[0])") == "5")
    check("?? 空值取右", eval("String(null ?? 3)") == "3")
    check("?? 0 不取右", eval("var x=0; String(x ?? 3)") == "0")
    check("?? 与 ?. 混用", eval("var o={}; String(o.a?.b ?? 'def')") == "def")

    println(if (failures == 0) "JsApiSurfaceTest: 全绿" else "JsApiSurfaceTest: $failures 项失败")
    if (failures != 0) kotlin.system.exitProcess(1)
}
