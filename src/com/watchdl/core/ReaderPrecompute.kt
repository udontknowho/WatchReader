package com.watchdl.core

import android.text.Layout
import android.text.PrecomputedText
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.widget.TextView

/**
 * 正文的后台预排。
 *
 * 换章卡顿的主因是正文 shaping（字形测量、样式分段）全落在首帧的主线程上：
 * 一章几千字重新排一遍，那一帧就慢了。这里在预取下一章正文的同一个后台线程里
 * 顺手把 [PrecomputedText] 排好，换章命中时直接喂回控件，主线程只剩断行与布局。
 *
 * [PrecomputedText.Params] 只含 paint、文字方向、断行策略、连字符频率 ——
 * 不含宽度，也不含行距。测量本身与宽度无关（每段一份 MeasuredParagraph，
 * 断行在布局期才做），所以同一件预排件可以喂给任何宽度的控件。
 *
 * 三条硬约束：
 *
 * 1. **参数必须与控件全等**。Params 取自主线程上已经套好样式的 [TextView]
 *    （[TextView.getTextMetricsParams]），后台不自己拼 paint —— 字号、字距、
 *    字重、斜体差一点，测出来的字形就是另一套。
 * 2. **断行必须全等**。首次启用某套参数时在后台用同一套参数排两遍
 *    （普通文本 / 预排件），`getLineCount()` 与每行 `getLineStart()` 全等才算通过。
 *    不一致就整批不用，退回原来的排版路径 —— 断行一变，[ReaderPaging]
 *    的偏移恢复会整套错位。
 * 3. **命中要比正文内容**。长度 + 哈希，换源补章换掉正文时档位没变也不能命中。
 */
object ReaderPrecompute {

    /** 一次预排的全部输入。主线程取一次，后台照着排。 */
    class Spec(
        val params: PrecomputedText.Params,
        val width: Int,
        /** `setLineSpacing` 的额外行距（px）。 */
        val lineGapPx: Int,
        /** `setLineSpacing` 的行距倍数。 */
        val lineMult: Float,
        /** 段距额外行高（px）；0 表示不加 span。 */
        val paraPx: Int,
        val justify: Boolean,
    )

    /** 一件排好的正文。 */
    private class Item(
        val spec: Spec,
        val len: Int,
        val hash: Int,
        val text: PrecomputedText,
    )

    /** 待用件上限：一件几万字大约几十到几百 KB，手表上不宜多留。 */
    private const val CAP = 4

    /** 已经验过「断行一致」的参数指纹。同一套参数只验一次。 */
    private val verified = HashSet<String>()

    /**
     * 正在排的键。
     *
     * 换章处与追加章处各推一次同一章的预排（预取链与 appendNext 会重叠），
     * 不去重就是同一章整章重排两遍 —— 手表上一章几万字的 shaping 是百毫秒级。
     */
    private val building = HashSet<String>()

    /** LRU，超过 [CAP] 丢最久没用的那件。访问序由 accessOrder 维护。 */
    private val items = object : java.util.LinkedHashMap<String, Item>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Item>?): Boolean =
            size > CAP
    }

    /**
     * 从已经套好样式的正文控件取预排输入（主线程）。
     *
     * 宽度必须与正文可用宽度一致：控件宽度减去左右内边距。控件还没布局时宽度是 0，
     * 此时 [build] 会直接拒排 —— 排出来的行宽与真控件不符，断行就白验了。
     */
    fun capture(
        tv: TextView,
        lineGapPx: Int,
        lineMult: Float,
        paraPx: Int,
        justify: Boolean,
    ): Spec {
        return Spec(
            params = tv.textMetricsParams,
            width = (tv.width - tv.paddingLeft - tv.paddingRight).coerceAtLeast(0),
            lineGapPx = lineGapPx,
            lineMult = lineMult,
            paraPx = paraPx,
            justify = justify,
        )
    }

    /**
     * 后台排一件（[shown] 已带首行缩进）。
     *
     * 返回是否可用。失败、断行不一致都返回 false，调用方什么都不用做 ——
     * 不进缓存就是退回原来的排版路径。
     */
    fun build(key: String, fp: String, shown: String, spec: Spec): Boolean {
        if (spec.width <= 0 || shown.isEmpty()) return false
        // 已经有一件同内容的件在手，不用再排。
        synchronized(items) {
            val have = items[key]
            if (have != null && have.len == shown.length && have.hash == shown.hashCode() &&
                sameSpec(have.spec, spec)) return true
        }
        // 同一章已经在别处排上了就让它排完，别并行重排。
        synchronized(building) { if (!building.add(key)) return false }
        try {
            return buildInner(key, fp, shown, spec)
        } finally {
            synchronized(building) { building.remove(key) }
        }
    }

    private fun buildInner(key: String, fp: String, shown: String, spec: Spec): Boolean {
        val t0 = System.nanoTime()
        var pre: PrecomputedText? = null
        var verdict = "ok"
        var lines = -1
        try {
            val needCheck = synchronized(verified) {
                // 改设置会一直加新指纹，攒多了先清一遍：验过的东西丢了最多再验一次。
                if (verified.size > 32) verified.clear()
                !verified.contains(fp)
            }
            if (needCheck) {
                // 两套排法都要各自新建 span：ParaSpan 每行只加一次高（done 标记），
                // 同一批 span 复用给两次排版，第二遍就不加了，比出来的行高是假的。
                //
                // 探测用的这一件只用于验断行，**验完就扔**：layout 会把里面的 ParaSpan
                // 逐一标成 done，再把这一件存进缓存，真用时段距就再也不加了（行高变短，
                // 段落挤在一起）。以前就犯过这个错。
                val plain = layout(paraSpanned(shown, spec.paraPx), spec)
                val probe = PrecomputedText.create(paraSpanned(shown, spec.paraPx), spec.params)
                val fast = layout(probe, spec)
                lines = fast.lineCount
                if (sameBreaks(plain, fast)) {
                    synchronized(verified) { verified.add(fp) }
                } else {
                    verdict = "split ${plain.lineCount}/${fast.lineCount}"
                }
            }
            // 要进缓存的那一件在验完之后才建，span 全是新的（done = false）。
            pre = PrecomputedText.create(paraSpanned(shown, spec.paraPx), spec.params)
            // needCheck 为假时不再多排一遍：本套参数已经验过，断行一致是已知的。
        } catch (t: Throwable) {
            verdict = "err ${t.javaClass.simpleName}"
        }
        val ok = verdict == "ok" && pre != null
        if (ok) synchronized(items) {
            items[key] = Item(spec, shown.length, shown.hashCode(), pre!!)
        } else {
            synchronized(items) { items.remove(key) }
        }
        DevLog.log(
            "Reader",
            "pre build chars=${shown.length} w=${spec.width} para=${spec.paraPx} " +
                "ok=$ok lines=$lines $verdict ms=${(System.nanoTime() - t0) / 1_000_000}",
        )
        return ok
    }

    /**
     * 取一件能用的预排件（主线程）。拿不到就返回 null，调用方走原来的排版。
     *
     * 三条命中条件一条不满足就丢掉这一件：参数、段距、对齐、正文内容。
     * 丢掉比留着安全 —— 留着只会在下一屏再一次命中错的东西。
     */
    fun take(key: String, shown: String, cur: Spec): PrecomputedText? {
        val item = synchronized(items) { items[key] } ?: return null
        val same = item.len == shown.length &&
            item.hash == shown.hashCode() &&
            sameSpec(item.spec, cur)
        if (!same) {
            synchronized(items) { items.remove(key) }
            return null
        }
        DevLog.log("Reader", "pre hit chars=${shown.length} w=${item.spec.width}")
        return item.text
    }

    /**
     * 排版输入是否一致。宽度不比：测量与宽度无关，断行由布局期按当时的宽度做。
     */
    private fun sameSpec(a: Spec, b: Spec): Boolean =
        a.params == b.params &&
            a.lineGapPx == b.lineGapPx &&
            a.lineMult == b.lineMult &&
            a.paraPx == b.paraPx &&
            a.justify == b.justify

    /** 行数 + 每行起始全等，才算断行没变。 */
    private fun sameBreaks(a: Layout, b: Layout): Boolean {
        if (a.lineCount != b.lineCount) return false
        for (i in 0 until a.lineCount) if (a.getLineStart(i) != b.getLineStart(i)) return false
        return true
    }

    /** 与控件同参数的一次普通排版，只用来验断行。 */
    private fun layout(cs: CharSequence, spec: Spec): StaticLayout =
        StaticLayout.Builder.obtain(cs, 0, cs.length, spec.params.textPaint, spec.width)
            .setTextDirection(spec.params.textDirection)
            .setBreakStrategy(spec.params.breakStrategy)
            .setHyphenationFrequency(spec.params.hyphenationFrequency)
            .setLineSpacing(spec.lineGapPx.toFloat(), spec.lineMult)
            .setJustificationMode(
                if (spec.justify) Layout.JUSTIFICATION_MODE_INTER_WORD
                else Layout.JUSTIFICATION_MODE_NONE)
            .build()

    /**
     * 给段末换行符挂行高 span。
     *
     * 正文是一个 TextView，没有「段落」这个对象，段距只能这么实现；
     * 预排件与普通排版必须走同一条路，否则两边行高不同，读起来段距像是丢了。
     */
    fun paraSpanned(text: CharSequence, extraPx: Int): CharSequence {
        if (extraPx <= 0 || text.isEmpty()) return text
        val sp = SpannableString(text)
        for (i in ReaderStyle.paraEnds(sp)) {
            sp.setSpan(ParaSpan(extraPx), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }
}

/**
 * 段末行高。`chooseHeight` 每行会被调两次，加一次就够。
 *
 * 放在这里而不是阅读页：后台预排要自己造一遍带段距的文本，
 * 两边必须用同一个 span 类。
 */
internal class ParaSpan(private val extraPx: Int) : android.text.style.LineHeightSpan {
    private var done = false

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanStartV: Int,
        lineHeight: Int,
        fm: android.graphics.Paint.FontMetricsInt,
    ) {
        if (done) return
        fm.bottom += extraPx
        fm.descent += extraPx
        done = true
    }
}
