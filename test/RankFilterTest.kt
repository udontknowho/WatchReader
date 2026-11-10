import com.watchdl.core.RankConfig as C
import com.watchdl.core.RankSource as S
import java.io.File

/** 离线契约测试；传入抓取目录可复验真实页面。传 --live 才发网络请求。 */
fun main(args: Array<String>) {
    var count = 0
    fun verify(label: String, ok: Boolean) { check(ok) { label }; count++; println("通过 $label") }
    fun rejects(label: String, block: () -> Unit) = verify(label, runCatching(block).isFailure)
    fun rank(board: String, gender: C.Gender = C.Gender.MALE) = C.BUILTIN.first {
        it.board == board && it.gender == gender && it.site == C.Site.QIDIAN
    }
    val read = rank("readindex")
    val fans = rank("newfans")
    verify("九榜且无收藏榜", C.QIDIAN_BOARDS.size == 9 && C.qidianBoard("collectlist") == null)
    verify("全站加十四分类", C.qidianCategories(read).size == 15)
    verify("书友分类相同", C.qidianCategories(fans) == C.qidianCategories(read))
    verify("书友周月", C.qidianPeriods(fans).map { it.path } == listOf("3", "2"))
    verify("阅读无周期", C.qidianPeriods(read).isEmpty())
    verify("其他榜不暴露猜测分类", C.qidianCategories(rank("rec")).size == 1)
    verify("女频不误用男分类", C.qidianCategories(rank("readindex", C.Gender.FEMALE)).size == 1)
    verify("阅读全站路径", S.qidianUrl(read) == "https://m.qidian.com/rank/readindex/catid-1/")
    verify("玄幻分类不是 catid-21", S.qidianUrl(read, C.QidianFilter(21)).endsWith("/catid21/"))
    verify("书友默认周", S.qidianUrl(fans).endsWith("/newfans/catid-1/3/"))
    verify("书友月不是第二页", S.qidianUrl(fans, C.QidianFilter(21, C.QidianPeriod.MONTH)).endsWith("/catid21/2/"))
    verify("女频独立域名", S.qidianUrl(rank("readindex", C.Gender.FEMALE)) == "https://m.qdmm.com/rank/readindex/")
    rejects("阅读不支持月榜") { S.qidianUrl(read, C.QidianFilter(period = C.QidianPeriod.MONTH)) }
    rejects("未知分类拒绝") { S.qidianUrl(fans, C.QidianFilter(999)) }
    rejects("未确认榜分类拒绝") { S.qidianUrl(rank("newbook"), C.QidianFilter(21)) }
    rejects("不伪造分页") { S.fetch(read, "male", 2) }
    verify("男频九榜", C.select(C.BUILTIN, C.Site.QIDIAN, C.Gender.MALE).size == 9)
    verify("女频九榜", C.select(C.BUILTIN, C.Site.QIDIAN, C.Gender.FEMALE).size == 9)
    verify("番茄男频十九类", C.select(C.BUILTIN, C.Site.FANQIE, C.Gender.MALE).size == 19)
    verify("所有起点禁分页", C.BUILTIN.filter { it.site == C.Site.QIDIAN }.all { it.pages == 0 })
    fun html(records: String, extra: String = "") = """<html><script id="vite-plugin-ssr_pageContext" type="application/json">{"pageContext":{"pageProps":{"pageData":{"total":510,"isLast":0,"records":$records$extra}}}}</script></html>"""
    val parsed = S.parseQidianPage(html("""[{"bid":"1","bName":"甲","desc":"第一本"},{"bid":"2","bName":"乙","bAuth":"作者乙","cat":"玄幻"}]"""))
    verify("缺作者不字段错位", parsed.books[0].author == "" && parsed.books[1].author == "作者乙")
    verify("正常页的WAF通用脚本不误判", S.parseQidianPage("<script>var x='x-waf-captcha-referer'</script>" + html("[]")).books.isEmpty())
    verify("total和isLast不推导分页", parsed.total == 510 && parsed.isLast == false && !parsed.canLoadMore)
    verify("真实空数组", S.parseQidianPage(html("[]")).books.isEmpty())
    rejects("WAF不是空榜") { S.parseQidianPage("<title>WAF拦截页面</title>") }
    rejects("缺SSR不是空榜") { S.parseQidianPage("<html>加载中</html>") }
    rejects("缺records不是空榜") { S.parseQidianPage(html("[]").replace("\"records\"", "\"changed\"")) }
    rejects("坏记录不吞掉") { S.parseQidianPage(html("""[{"bid":"1"}]""")) }
    rejects("records不能是对象") { S.parseQidianPage(html("{}")) }
    verify("旧配置归一且禁分页", C.parse("""{"ranks":[{"key":"old","kind":"qidian","board":"yuepiaolist","pages":10}]}""").single().let { it.board == "yuepiao" && it.pages == 0 })
    verify("无效旧收藏榜剔除", C.parse("""{"ranks":[{"key":"old","kind":"qidian","board":"collectlist"}]}""").isEmpty())
    args.firstOrNull { !it.startsWith("--") }?.let { dir ->
        for ((file, total) in listOf("read.html" to 510, "fans.html" to 500)) {
            val page = S.parseQidianPage(File(dir, file).readText())
            verify("实抓${file}首批20条", page.books.size == 20 && page.total == total && !page.canLoadMore)
        }
    }
    if ("--live" in args) {
        for ((r, filter) in listOf(read to C.QidianFilter(),
            read to C.QidianFilter(21), fans to C.QidianFilter(),
            fans to C.QidianFilter(period = C.QidianPeriod.MONTH))) {
            val page = S.qidian(r, filter)
            verify("Http真实${r.board}/${filter.categoryId}/${filter.period.path}", page.books.size == 20)
            println("首条 ${page.books.first().name}，total=${page.total}，isLast=${page.isLast}")
        }
    }
    // 假 Cookie 专用于离线契约，不包含抓取会话。
    val qdUrl = "https://m.qidian.com/rank/yuepiao/"
    fun monthly(month: String = "203101", gender: String = "male", extra: String = "") = """<script id="vite-plugin-ssr_pageContext">{"pageContext":{"routeParams":{"pageType":"yuepiao","gender":"$gender"},"pageProps":{"pageData":{"gender":"$gender","pageNum":1,"isLast":0,"total":1000,"records":[{"bid":"1","bName":"甲"}],"filters":[{"key":"catId","selectedIndex":0,"items":[{"value":-1}]},{"key":"yearmonth","selectedIndex":1,"items":[{"value":"199901"},{"value":"$month"}]}]$extra}}}}</script>"""
    val fakeCookie = "other=one; Path=/; Expires=Wed, 01 Jan 2031 00:00:00 GMT, _csrfToken=offline-token; Path=/; HttpOnly"
    val first = S.parseQidianPage(monthly(), qdUrl, fakeCookie)
    verify("月票有效匿名会话可翻页", first.canLoadMore && first.pageNum == 1 && first.session != null)
    verify("无Cookie不开放分页", !S.parseQidianPage(monthly(), qdUrl).canLoadMore)
    verify("女频不泛化分页", !S.parseQidianPage(monthly(gender = "female"), "https://m.qdmm.com/rank/yuepiao/", fakeCookie).canLoadMore)
    verify("其他榜不泛化分页", !S.parseQidianPage(monthly(), "https://m.qidian.com/rank/readindex/", fakeCookie).canLoadMore)
    verify("月份读取官网选中项", first.session!!.requestUrl(2).contains("yearmonth=203101"))
    verify("换月份不硬编码", S.parseQidianPage(monthly("203102"), qdUrl, fakeCookie).session!!.requestUrl(2).contains("yearmonth=203102"))
    verify("Cookie不参与快照字符串", !first.toString().contains("offline-token"))
    verify("完整匿名Cookie传递", first.session!!.headers(qdUrl)["Cookie"] == "other=one; _csrfToken=offline-token")
    fun api(page: Int = 2, last: Int = 0, records: String = """[{"bid":"2","bName":"乙"}]""") =
        """{"code":0,"data":{"gender":"male","pageNum":$page,"isLast":$last,"total":1000,"records":$records}}"""
    val second = S.parseQidianResponse(api(), first)
    verify("第二页推进并继承上下文", second.pageNum == 2 && second.canLoadMore && second.session === first.session && second.books.single().bookId == "2")
    verify("末页结束", !S.parseQidianResponse(api(last = 1), first).canLoadMore)
    verify("空页结束", !S.parseQidianResponse(api(records = "[]"), first).canLoadMore)
    rejects("API非成功码不算空榜") { S.parseQidianResponse("""{"code":1,"data":{}}""", first) }
    rejects("API WAF不算空榜") { S.parseQidianResponse("<title>WAF拦截</title>", first) }
    rejects("页码不符拒绝") { S.parseQidianResponse(api(page = 1), first) }
    rejects("重复页拒绝") { S.parseQidianResponse(api(records = """[{"bid":"1","bName":"甲"}]"""), first) }
    rejects("缺结束标记拒绝") { S.parseQidianResponse(api().replace("\"isLast\":0,", ""), first) }
    rejects("缺记录数组拒绝") { S.parseQidianResponse(api().replace("\"records\"", "\"changed\""), first) }
    verify("失败不消耗原页码", first.pageNum == 1 && first.canLoadMore)
    if ("--live-pagination" in args) {
        // 两次请求，父代理统一运行；只打印条数与交集，不打印会话或请求 URL。
        val liveFirst = S.qidian(rank("yuepiao"))
        verify("Http月票首批建立会话", liveFirst.books.size == 20 && liveFirst.canLoadMore)
        val liveSecond = S.qidianNext(liveFirst)
        verify("Http第二页20条且无交集", liveSecond.pageNum == 2 && liveSecond.books.size == 20 &&
            liveSecond.books.none { b -> liveFirst.books.any { it.bookId == b.bookId } })
    }
    println("榜单筛选测试通过 $count 项")
}
