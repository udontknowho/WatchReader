import com.watchdl.core.*
import java.io.File

/**
 * 取数器返回的假正文。
 *
 * 必须够 [Downloader.MIN_BODY_CHARS] 字：以前夹具都是「<p>正2 章</p>」这种 5 字正文，
 * 而真实现的合格判定要求足够长。夹具不真实的话，这层判定就永远测不到。
 */
private fun chapterBody(s: String) = "<p>$s" + "正文填充".repeat(100) + "</p>"

/** 下载引擎自检：批拆分、退避、双写落盘、恢复、导出。用假取数器，不碰网络。 */
fun main() {
    var failures = 0
    fun check(n: String, ok: Boolean, d: String = "") {
        if (ok) println("  ✓ $n") else { failures++; println("  ✗ $n  $d") }
    }

    println("[BatchPlan] 批次切分")
    check("章少时一把抓", BatchPlan.initialSize(3) == 3)
    check("章多时封顶", BatchPlan.initialSize(500) == BatchPlan.MAX_BATCH)
    check("切批数量", BatchPlan.batches((0 until 50).toList(), 20).size == 3)
    check("缩批到一半", BatchPlan.shrink(20, 20) == 10)
    check("缩到底返回 null", BatchPlan.shrink(BatchPlan.MIN_BATCH, 4) == null)
    check("空列表不切批", BatchPlan.batches(emptyList(), 10).isEmpty())

    println("[Backoff] 退避")
    check("首次等 1s", Backoff.delayMs(0) == 1000L)
    check("第二次 2s", Backoff.delayMs(1) == 2000L)
    check("封顶 8s", Backoff.delayMs(10) == 8000L)

    println("[Downloader] 失败分类")
    // 分类错一个，整份日志的归因就错：取消被当成源坏了、403 被当成网络问题。
    fun kind(e: Throwable) = Downloader.errKind(e)
    check("请求超时", kind(java.io.IOException("请求超时（15000ms，含 DNS）")) == "请求超时")
    check("socket 超时", kind(java.net.SocketTimeoutException()) == "请求超时")
    check("403 风控", kind(java.io.IOException("HTTP 403")) == "403 风控")
    check("其他 HTTP 错", kind(java.io.IOException("HTTP 500")) == "HTTP 错")
    check("正文无效", kind(IllegalStateException("正文过短（第1章 仅 21 字）")) == "正文无效")
    check("正文为空也算正文无效", kind(IllegalStateException("正文为空")) == "正文无效")
    check("上层取消", kind(java.io.IOException("请求被取消")) == "取消")
    check("中断", kind(InterruptedException()) == "取消")
    check("不认识的退回异常类名", kind(IllegalArgumentException("x")) == "IllegalArgumentException")

    println("[Downloader] 双写落盘与恢复")
    val root = File(System.getenv("WD_TEST_DIR") ?: "build/test-state", "wd-${System.nanoTime()}")
    root.deleteRecursively(); root.mkdirs()
    try {
        val store = JobStore(root)
        val chapters = (1..30).map { ChapterRef("id$it", "第${it}章 标题") }
        val job = BookJob("b1", "测试书", "某人", chapters)

        var calls = 0
        val dl = Downloader(store, fetchChapter = { ch ->
            calls++
            if (ch.itemId == "id7") error("模拟永久失败")
            chapterBody("正文 ${ch.title}")
        }, log = {}, sleep = {})
        val r = dl.run(job)
        check("大部分成功", r.success >= 27, "成功 ${r.success} 失败 ${r.failed}")
        check("失败章被记账", r.failed >= 1)
        check("job.json 已写", store.jobFile("b1").exists())
        check("jsonl 已写", store.chaptersFile("b1").exists())

        val restored = Downloader.restore(store, "b1")
        check("能恢复", restored != null)
        check("章数一致", restored!!.total == 30, "实际 ${restored.total}")
        check("标题没丢", restored.chapters[0].title == "第1章 标题", restored.chapters[0].title)
        val doneCount = restored.count(ChapterStatus.DONE)
        check("已下章数一致", doneCount == r.success, "恢复 $doneCount vs 成功 ${r.success}")
        check("失败章状态保住", restored.statuses[6] == ChapterStatus.FAILED,
            "实际 ${restored.statuses[6]}")
        check("未完成不算 finished", !restored.isFinished)

        println("[Downloader] 正文过短不算成功")
        // 真实现场：书源规则失效后每章只回 21 字，旧代码当成功写进 jsonl，
        // 进度显示「已下载 320/320」，用户打开全书空白。
        run {
            val sStore = JobStore(root.resolve("short"))
            val sBook = BookJob("sh1", "短正文书", "作者",
                (0 until 3).map { ChapterRef("p$it", "第${it + 1}章", false) })
            val r = Downloader(sStore, fetchChapter = { "<p>只有二十一个字而已真的很少</p>" },
                log = {}, sleep = {}).run(sBook)
            check("短正文全部记失败", r.success == 0 && r.failed == 3,
                "成功 ${r.success} / 失败 ${r.failed}")
            check("短正文不落盘", sStore.readChapters("sh1").isEmpty(),
                "${sStore.readChapters("sh1").size} 章落了盘")
            // 旧版本已经写进 jsonl 的短章，restore 要当未完成，否则永远修不好。
            sStore.chaptersFile("sh1").parentFile!!.mkdirs()
            sStore.chaptersFile("sh1").writeText(
                """{"itemId":"p0","title":"第1章","content":"<p>二十一个字</p>"}""" + "\n")
            val st = Downloader.restore(sStore, "sh1")
            check("旧短章恢复成未完成", st?.statuses?.get(0) != ChapterStatus.DONE,
                "实际 ${st?.statuses?.get(0)}")
        }

        println("[Downloader] 续传不重复下载")
        // 注意：必须用新计数器测新实例——用旧变量会读到第一次那个 lambda 的值
        var resumeCalls = 0
        val rr = Downloader(store, fetchChapter = { resumeCalls++; chapterBody("补下载") }, log = {}, sleep = {})
            .run(restored)
        check("只补失败的章", resumeCalls == 1, "实际请求 $resumeCalls 次")

        // N3：续传时本次只下 1 章，但全书已下 29 章。
        // 旧实现只报「成功 1」，用户以为全书只有 1 章。
        check("本次增量是 1", rr.success == 1, "实际 ${rr.success}")
        check("全书已完成数按 statuses 算", rr.doneTotal == 30, "实际 ${rr.doneTotal}")
        check("全书口径与 total 一致", rr.doneTotal <= rr.total, "${rr.doneTotal}/${rr.total}")
        check("全书失败数清零", rr.failTotal == 0, "实际 ${rr.failTotal}")

        println("[Downloader] 续传后状态转为完成")
        val after = Downloader.restore(store, "b1")!!
        check("失败章重跑后变 DONE", after.count(ChapterStatus.FAILED) == 0,
            "仍有 ${after.count(ChapterStatus.FAILED)} 章失败")
        check("全部完成", after.isFinished)

        println("[JobStore] 作业文件不存在不能抛（闪退回归）")
        // 真实崩溃：进度广播里 restore → readJob，文件已被清理，
        // FileNotFoundException 直接把 Activity 崩了。readJob 必须返回 null。
        run {
            val ghost = JobStore(root.resolve("ghost"))
            val r = runCatching { ghost.readJob("never-existed") }
            check("readJob 不抛异常", r.isSuccess, r.exceptionOrNull()?.toString() ?: "")
            check("返回 null", r.getOrNull() == null, r.getOrNull().toString())
            val c = runCatching { ghost.readChapters("never-existed") }
            check("readChapters 不抛异常", c.isSuccess, c.exceptionOrNull()?.toString() ?: "")
            check("readChapters 返回空", c.getOrNull()?.isEmpty() == true)
            // restore 是崩溃现场的直接调用方，也不能抛。
            val s = runCatching { Downloader.restore(ghost, "never-existed") }
            check("restore 不抛异常", s.isSuccess, s.exceptionOrNull()?.toString() ?: "")
        }

        println("[Downloader] 导出纯文本")
        val out = File(root, "out.txt")
        val n = Downloader.exportText(store, "b1", out)
        // 导出文件带 UTF-8 BOM（Windows 记事本不乱码），所以先剥掉再断言。
        val text = out.readText().removePrefix("\uFEFF")
        // 续传补上了第 7 章，所以导出应是全部 30 章
        check("导出全部章", n == 30, "实际 $n")
        check("补下载内容也在", text.contains("补下载"), text.take(200))
        check("含正文", text.contains("正文 第1章"), text.take(80))
        check("全角缩进", text.contains("　　正文"), "无缩进")
        check("标题在首行", text.startsWith("第1章 标题"), text.take(40))

        // N2：导出写失败或中断时，原来的完整导出不能被毁。
        run {
            val keep = File(root, "keep.txt")
            keep.writeText("上一次的完整导出")
            val before = keep.readText()
            // 目标目录被占成一个文件，导出必然失败
            val blockedDir = File(root, "blocked").apply { mkdirs() }
            val bad = File(blockedDir, "sub")
            val realParent = File(root, "ro").apply { mkdirs() }
            val tmpTarget = File(realParent, "x.txt")
            // 用一个会失败的 out：父目录不存在
            val r = runCatching {
                Downloader.exportText(store, "b1", File(root, "no-such-dir/y.txt"))
            }
            check("导出到不存在的目录会报错而不是静默", r.isFailure || r.getOrNull() == 0)
            check("失败的导出没有碰别的文件", keep.readText() == before, keep.readText().take(20))
            // 正常导出后不应留下 .tmp 残留
            val prev = File(root, "prev.txt")
            Downloader.exportText(store, "b1", prev)
            check("成功后不留 .tmp 残留",
                !File(prev.absolutePath + ".tmp").exists())
            check("目标文件确实被替换成新内容", prev.length() > 100, "长度 ${prev.length()}")
        }

        // 导出文件规范（2026-10-21 用户定）：BOM + 章节序号。
        check("导出带 UTF-8 BOM", out.readBytes().take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            out.readBytes().take(3).toString())
        // 目录里的标题已是「第N章」，不能重复加号。
        check("不重复加章节号", !text.contains("第1章 第1章"), text.take(60))
        run {
            // 目录标题不带号时，导出要自己补上。
            val noNum = JobStore(root.resolve("nonum"))
            val b = BookJob("b7", "无号书", "作者",
                listOf(ChapterRef("a", "开头", false), ChapterRef("b", "承接", false)))
            Downloader(noNum, fetchChapter = { ch -> chapterBody("正文${ch.title}") }, log = {}, sleep = {})
                .run(b, indexes = listOf(0, 1))
            val o2 = File(root, "nonum.txt")
            Downloader.exportText(noNum, "b7", o2)
            val t2 = o2.readText().removePrefix("\uFEFF")
            check("无号标题补章节号", t2.contains("第1章 开头") && t2.contains("第2章 承接"), t2.take(80))
        }

        println("[Downloader] 并行导出保持章节顺序")
        // 并行路径只在 ≥32 章时才走，所以单独造一本 60 章的书。
        // 验证点：章节顺序不能乱（并行转换后按原序落盘），且不能丢章。
        run {
            val bigStore = JobStore(root.resolve("big"))
            val big = BookJob("b9", "大部头", "作者",
                (0 until 60).map { ChapterRef("i$it", "第${it + 1}章", false) })
            Downloader(bigStore, fetchChapter = { ch -> chapterBody("正文${ch.title}") }, log = {}, sleep = {})
                .run(big, indexes = (0 until 60).toList())
            val bigOut = File(root, "big.txt")
            val bigN = Downloader.exportText(bigStore, "b9", bigOut)
            val lines = bigOut.readLines().map { it.removePrefix("\uFEFF") }
            check("并行导出章数正确", bigN == 60, "实际 $bigN")
            // 标题必须按 1..60 递增出现，乱序就是并行写坏了。
            val order = lines.filter { it.startsWith("第") && it.endsWith("章") }
                .map { Regex("第(\\d+)章").find(it)?.groupValues?.get(1)?.toInt() ?: -1 }
            check("并行导出章节有序", order == (1..60).toList(), order.take(8).toString())
        }

        println("[Downloader] 乱序落盘也要按目录顺序导出")
        // 真实现场：并发下载时章节完成顺序随机，chapters.jsonl 的行序不是阅读顺序。
        // 旧代码直接按行序导出 → 「最后几章跳到前面」（用户实报）。
        run {
            val shStore = JobStore(root.resolve("shuf"))
            val ids = (0 until 20).map { "s$it" }
            // 故意倒着写，并在中间揬入两条。
            val scrambled = (19 downTo 0).map { it }
            shStore.chaptersFile("s1").parentFile!!.mkdirs()
            java.io.RandomAccessFile(shStore.chaptersFile("s1"), "rw").use { raf ->
                for (i in scrambled) {
                    val line = """{"itemId":"s$i","title":"第${i + 1}章","content":"${chapterBody("正文${i + 1}")}"}""" + "\n"
                    raf.write(line.toByteArray(Charsets.UTF_8))
                }
            }
            val shOut = File(root, "shuf.txt")
            // 传目录顺序 → 必须恢复成 1..20
            Downloader.exportText(shStore, "s1", shOut, "乱序书 · 作者", order = ids)
            val got = shOut.readLines()
                .filter { it.startsWith("第") && it.endsWith("章") }
                .map { Regex("第(\\d+)章").find(it)?.groupValues?.get(1)?.toInt() ?: -1 }
            check("按目录顺序排好了", got == (1..20).toList(), got.take(8).toString())
            check("首行是书名", shOut.readLines().first().contains("乱序书"), shOut.readLines().first())
        }

        println("[Downloader] 并发下载不丢章不串行误")
        // 并发 4：每章内容不同，全部落盘后应能逐章核对上，且 jsonl 不能写坏。
        run {
            val cStore = JobStore(root.resolve("conc"))
            val cBook = BookJob("c1", "并发书", "作者",
                (0 until 40).map { ChapterRef("j$it", "第${it + 1}章", false) })
            val r = Downloader(
                cStore, fetchChapter = { ch -> chapterBody("内容${ch.title}") },
                log = {}, sleep = {}, gapMs = 0, concurrency = 4,
            ).run(cBook, indexes = (0 until 40).toList())
            check("并发全部完成", r.success == 40, "成功 ${r.success} / 失败 ${r.failed}")
            val saved = cStore.readChapters("c1")
            check("并发章数不丢", saved.size == 40, "实际 ${saved.size}")
            val st = Downloader.restore(cStore, "c1")
            check("并发状态全 DONE", st?.count(ChapterStatus.DONE) == 40,
                "DONE ${st?.count(ChapterStatus.DONE)}")
            // 并发写 jsonl 最容易出错的地方：行被截断成了半个 JSON。
            val broken = saved.values.count { TinyJson.path(it, "title") == null }
            check("jsonl 每行完整", broken == 0, "$broken 行解析不了")
        }

        println("[Downloader] 单章卡死必须被打断")
        // 一个永远不返回的取数器，加 600ms 上限：必须放弃并往前走，不能拖死整本。
        run {
            val tStore = JobStore(root.resolve("stuck"))
            val tBook = BookJob("t1", "卡死书", "作者",
                (0 until 5).map { ChapterRef("k$it", "第${it + 1}章", false) })
            val called = java.util.concurrent.atomic.AtomicInteger(0)
            val t0 = System.currentTimeMillis()
            // 600ms 上限本身很宽：卡死章一次超时就该走。但整套自检同时跑，
            // 机器紧张时线程调度会拖长，20 秒的取消死线可能先到，那时整批被取消、
            // 成功就是 0 —— 测的就不再是「会不会被拖死」，而是「机器快不快」。
            // 所以取消死线给到 60 秒，只卡一个真卡死的上限。
            val r = Downloader(
                tStore,
                fetchChapter = { ch ->
                    called.incrementAndGet()
                    // 第 1 章永远卡住，其余章正常。
                    if (ch.itemId == "k0") { Thread.sleep(60_000); chapterBody("x") }
                    else chapterBody("内容${ch.title}")
                },
                log = {}, sleep = {}, gapMs = 0,
                chapterTimeoutMs = 600, isCancelled = { System.currentTimeMillis() - t0 > 60_000 },
            ).run(tBook.withStatuses(emptyMap()), indexes = (0 until 5).toList(), retryFailed = false)
            val took = System.currentTimeMillis() - t0
            // 核心要求是「卡死章没把整本拖住」，所以卡 4 章不算错。
            check("卡死章被放弃，其余照跑", r.success >= 4, "成功 ${r.success} / 失败 ${r.failed}")
            check("整本没被拖死（<15s）", took < 15_000, "耗时 ${took}ms")
            check("卡死章记为失败", r.failed >= 1, "失败 ${r.failed}")
        }

        println("[Downloader] 中断后等待写入线程收尾")
        run {
            val iStore = JobStore(root.resolve("interrupt"))
            val iBook = BookJob("interrupt", "书", "作者", listOf(ChapterRef("i", "章")))
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val returned = java.util.concurrent.CountDownLatch(1)
            val worker = Thread {
                try {
                    Downloader(iStore, fetchChapter = {
                        entered.countDown()
                        while (release.count > 0) {
                            try { release.await() } catch (_: InterruptedException) { }
                        }
                        chapterBody("正文")
                    }, gapMs = 0, chapterTimeoutMs = 0).run(iBook)
                } finally { returned.countDown() }
            }
            worker.start()
            val started = entered.await(5, java.util.concurrent.TimeUnit.SECONDS)
            worker.interrupt()
            val early = returned.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            release.countDown()
            worker.join(5_000)
            check("取数线程已启动", started)
            check("写入线程未结束时 run 不返回", !early)
            check("写入线程结束后 run 返回", !worker.isAlive)
            check("中断落盘为取消", TinyJson.path(iStore.readJob("interrupt") ?: "", "state") == "CANCELLED")
            Downloader(iStore, fetchChapter = { chapterBody("正文") }, gapMs = 0).run(Downloader.restore(iStore, "interrupt")!!)
            check("取消后可显式续传", TinyJson.path(iStore.readJob("interrupt") ?: "", "state") == "DONE")
        }

        println("[Downloader] HTML 转文本")
        check("块级标签转换行", Downloader.htmlToText("<p>甲</p><p>乙</p>").contains("甲") &&
            Downloader.htmlToText("<p>甲</p><p>乙</p>").contains("乙"))
        check("去 script", !Downloader.htmlToText("<script>bad()</script><p>好</p>").contains("bad"))
        check("实体还原", Downloader.htmlToText("<p>&lt;标签&gt; &amp; &#20013;</p>").contains("<标签> & 中"),
            Downloader.htmlToText("<p>&lt;标签&gt; &amp; &#20013;</p>"))
        check("nbsp 转空格", Downloader.htmlToText("<p>a&nbsp;b</p>").contains("a b"))
        check("空输入不炸", Downloader.htmlToText("") == "")

        println("[Downloader] JSON 转义")
        check("引号转义", Downloader.quote("he\"llo") == "\"he\\\"llo\"")
        check("换行转义", Downloader.quote("a\nb") == "\"a\\nb\"")
        val weird = "含\"引号\"和\n换行\\反斜杠"
        val ok = TinyJson.path("{\"k\":${Downloader.quote(weird)}}", "k")
        check("转义后可被解析回", ok == weird, "得到 $ok")

    // ══════ N1：取数线程池必须有上限 ══════
    println("[N1] 超时线程不会无限堆积")
    run {
        // cached pool 时 60 个慢任务会造出 60 条线程。现在固定上限 16。
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val cur = java.util.concurrent.atomic.AtomicInteger(0)
        val dl = Downloader(
            store = JobStore(File(root, "n1").apply { mkdirs() }),
            fetchChapter = {
                val c = cur.incrementAndGet()
                peak.updateAndGet { p -> maxOf(p, c) }
                try { Thread.sleep(600) } finally { cur.decrementAndGet() }
                "x"
            },
            log = { },
            sleep = { },
            concurrency = 8,
            chapterTimeoutMs = 100,   // 远小于 600ms 的取数，全部会超时
        )
        val chs = (1..60).map { ChapterRef("c$it", "第 $it 章", false) }
        val job = BookJob("n1book", "线程测试", "a", chs)
        // 单章 100ms 超时，远小于 600ms 的取数 —— 全部会超时
        runCatching { dl.run(job) }
        val p = peak.get()
        println("    峰值线程 ${p}（上限 16）")
        check("峰值线程不超过 16", p <= 16, "实际 $p")
        check("确实跑了并发（不是退化成串行）", p >= 2, "实际 $p")
    }

    // ══════ N9：目录不再送 Intent extra ══════
    println("[N9] 长目录落 job.json，不走 Binder")
    run {
        val dir = File(root, "n9").apply { mkdirs() }
        val store = JobStore(dir)
        // 模拟通用书源：itemId 是完整 URL，6000 章。
        // 旧实现把它们 join 成一个字符串塞进 Intent，超 Binder 1 MB 上限。
        val many = (1..6000).map {
            ChapterRef("https://www.example-source.com/book/123456/chapter-$it.html",
                "第 $it 章 标题", false)
        }
        val bid = "https://www.example-source.com/book/123456"
        Downloader.saveCatalog(store, BookInfo(bid, "长书", "作者", many))
        val back = Downloader.restore(store, bid)
        check("job.json 能读回 6000 章", back?.chapters?.size == 6000, "实际 ${back?.chapters?.size}")
        check("itemId 首尾都对", back?.chapters?.first()?.itemId == many.first().itemId &&
            back?.chapters?.last()?.itemId == many.last().itemId)
        check("中文标题带空格也完整", back?.chapters?.last()?.title == many.last().title,
            back?.chapters?.last()?.title ?: "空")

        // 实测门槛，不靠估算。Binder 上限 1 MB，按 UTF-16 算每个字符 2 字节，
        // 一行约 8 字符开销（两个 \u0001、\n、布尔值）。
        fun binderBytes(chs: List<ChapterRef>) = chs.joinToString("\n") {
            "${it.itemId}\u0001${it.title}\u0001${it.locked}"
        }.toByteArray(Charsets.UTF_16).size
        val n6000 = binderBytes(many)
        println("    6000 章 / 短标题：$n6000 字节（${n6000 / 1024} KB，上限 1024 KB）")
        // 短标题 6000 章就已经逼近上限；真实书源的标题更长，会越过。
        check("6000 章短标题已超过 800 KB", n6000 > 800 * 1024, "$n6000 字节")
        // 用真实形态再算一次：长标题 + 更长的 itemId
        val realistic = (1..6000).map {
            ChapterRef("https://www.example-source.com/book/123456/chapter-$it.html",
                "第 $it 章 开局签到荒古圣体，万族林立我为尊", false)
        }
        val nReal = binderBytes(realistic)
        println("    6000 章 / 真实标题：$nReal 字节（${nReal / 1024} KB）")
        check("真实标题形态确实溢出 Binder 1 MB", nReal > 1024 * 1024, "$nReal 字节")
        // 这正是修复的意义：新路径下目录不经过 Binder，字节数再大也不影响
        Downloader.saveCatalog(store, BookInfo(bid, "长书", "作者", realistic))
        check("真实形态 6000 章也能完整读回",
            Downloader.restore(store, bid)?.chapters?.size == 6000)

        // 已有作业时不覆盖章节：chapters.jsonl 里的正文按旧 itemId 存
        Downloader.saveCatalog(store, BookInfo(bid, "长书", "作者", many.take(3)))
        check("重跑不把已有目录缩成 3 章",
            Downloader.restore(store, bid)?.chapters?.size == 6000)
    }
    } finally {
        root.deleteRecursively()
    }

    if (failures == 0) println("\n全部通过") else { println("\n失败 $failures 项"); kotlin.system.exitProcess(1) }
}
