package com.watchdl.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 图标：reicon（MIT，24×24 网格，Outline 权重）。
 *
 * 早先两版分别是手画描边和 Material Symbols 填充。前者小尺寸下认不出原形，
 * 后者是实心块，在手表这块小屏上比线稿重。现在换成 reicon：它的 Outline
 * 多数已经展开成闭合填充轮廓（`fill` 直接画就是 1.5px 线宽），少数仍是
 * `stroke` 元素，两者在这里统一处理。
 *
 * 几何数据在 [REICON]（生成文件），这里只管解析、缓存、绘制。
 */
internal object Icons {

    /**
     * 老调用点传的还是字符（`"⚙"`、`"←"`），在这里翻译成图标名。
     * 这样换图形只要改这一张表，不必逐个改调用点。
     */
    private val ALIAS = mapOf(
        "←" to "back", "☰" to "menu", "≡" to "menu", "⚙" to "settings",
        "🔍" to "search", "⋮" to "more", "⟳" to "refresh", "↻" to "refresh",
        "↓" to "download", "⤓" to "download", "▤" to "list", "▦" to "grid",
        "＋" to "plus", "+" to "plus", "🗑" to "delete", "▶" to "play", "■" to "stop",
        "🔖" to "bookmark", "✕" to "close", "✗" to "close", "⇄" to "swap",
        "◷" to "history", "⚠" to "warning", "✓" to "check", "☁" to "cloud",
    )

    private val cache = HashMap<String, List<Stroke>>()

    /** 字符或图标名 → 图标名；不是图标返回 null（照旧当文字渲染）。 */
    fun name(glyph: String): String? {
        val n = ALIAS[glyph] ?: glyph
        return n.takeIf { REICON.containsKey(it) }
    }

    fun has(name: String): Boolean = REICON.containsKey(name)

    /** 解析好的笔画集合。缓存起来，同一图标反复画不重复解析。 */
    fun strokes(name: String): List<Stroke>? {
        cache[name]?.let { return it }
        val built = build(name) ?: return null
        cache[name] = built
        return built
    }

    /** 造一个图标 View。[sizeDp] 是图形本身的边长，容器可以更大（热区）。 */
    fun view(ctx: Context, name: String, tint: Int, sizeDp: Number = 24): IconView =
        IconView(ctx, name, tint, sizeDp)

    private fun build(name: String): List<Stroke>? {
        val parts = REICON[name] ?: return null
        val out = ArrayList<Stroke>(parts.size)
        for (part in parts) {
            val ops = runCatching { IconPath.parse(part.d) }.getOrNull() ?: continue
            val p = Path()
            p.fillType = if (part.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            var x = 0f; var y = 0f          // 当前点
            var sx = 0f; var sy = 0f        // 子路径起点（Z 之后回到这里）
            var kx = 0f; var ky = 0f        // 上一段曲线的末端控制点（S/T 反射用）
            var cubic = false
            var quad = false
            for (op in ops) {
                val a = op.v
                val rel = op.type.isLowerCase()
                when (op.type.uppercaseChar()) {
                    'M' -> {
                        x = if (rel) x + a[0] else a[0]
                        y = if (rel) y + a[1] else a[1]
                        p.moveTo(x, y); sx = x; sy = y
                        cubic = false; quad = false
                    }
                    'L' -> {
                        x = if (rel) x + a[0] else a[0]
                        y = if (rel) y + a[1] else a[1]
                        p.lineTo(x, y); cubic = false; quad = false
                    }
                    'H' -> { x = if (rel) x + a[0] else a[0]; p.lineTo(x, y); cubic = false; quad = false }
                    'V' -> { y = if (rel) y + a[0] else a[0]; p.lineTo(x, y); cubic = false; quad = false }
                    'C' -> {
                        val c1x = if (rel) x + a[0] else a[0]; val c1y = if (rel) y + a[1] else a[1]
                        val c2x = if (rel) x + a[2] else a[2]; val c2y = if (rel) y + a[3] else a[3]
                        x = if (rel) x + a[4] else a[4]; y = if (rel) y + a[5] else a[5]
                        p.cubicTo(c1x, c1y, c2x, c2y, x, y)
                        kx = c2x; ky = c2y; cubic = true; quad = false
                    }
                    'S' -> {
                        val c1x = if (cubic) 2 * x - kx else x
                        val c1y = if (cubic) 2 * y - ky else y
                        val c2x = if (rel) x + a[0] else a[0]; val c2y = if (rel) y + a[1] else a[1]
                        x = if (rel) x + a[2] else a[2]; y = if (rel) y + a[3] else a[3]
                        p.cubicTo(c1x, c1y, c2x, c2y, x, y)
                        kx = c2x; ky = c2y; cubic = true; quad = false
                    }
                    'Q' -> {
                        val cx = if (rel) x + a[0] else a[0]; val cy = if (rel) y + a[1] else a[1]
                        x = if (rel) x + a[2] else a[2]; y = if (rel) y + a[3] else a[3]
                        p.quadTo(cx, cy, x, y)
                        kx = cx; ky = cy; quad = true; cubic = false
                    }
                    'T' -> {
                        val cx = if (quad) 2 * x - kx else x
                        val cy = if (quad) 2 * y - ky else y
                        x = if (rel) x + a[0] else a[0]; y = if (rel) y + a[1] else a[1]
                        p.quadTo(cx, cy, x, y)
                        kx = cx; ky = cy; quad = true; cubic = false
                    }
                    'Z' -> { p.close(); x = sx; y = sy; cubic = false; quad = false }
                }
            }
            out.add(Stroke(p, part.stroke))
        }
        return out.ifEmpty { null }
    }
}

/** 一段已解析的笔画。[stroke] 为真表示原数据是描边（`stroke="currentColor"`）。 */
internal class Stroke(val path: Path, val stroke: Boolean)

/** 一条路径指令。[v] 是参数，个数由指令决定（Z 没有参数）。 */
internal class Cmd(val type: Char, val v: FloatArray)

/**
 * SVG 路径数据解析器。只做拆分与展开，不碰 Android 图形类 —— 所以自检能在纯 JVM 上跑。
 *
 * 支持 M L H V C S Q T Z（大小写皆可）。隐式重复（`M3 18h18v-2` 里 h 后跟两个数）按 SVG 规则展开。
 */
internal object IconPath {

    private val TOKEN = Regex("[MmLlHhVvCcSsQqTtZz]|-?(?:[0-9]*\\.)?[0-9]+")

    fun parse(d: String): List<Cmd> {
        val toks = TOKEN.findAll(d).map { it.value }.toList()
        val out = ArrayList<Cmd>()
        var i = 0
        var cmd = 'M'
        // 上一个已展开的指令是不是 M。是的话，再跟一串数字要当 L 处理（SVG 的隐式 lineto）。
        var moved = false
        while (i < toks.size) {
            val t = toks[i]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]; i++; moved = false
                if (cmd.uppercaseChar() == 'Z') { out.add(Cmd('Z', FloatArray(0))); continue }
            }
            var c = cmd
            if (c.uppercaseChar() == 'M' && moved) c = if (c == 'm') 'l' else 'L'
            val n = when (c.uppercaseChar()) {
                'M', 'L', 'T' -> 2
                'H', 'V' -> 1
                'C' -> 6
                'S', 'Q' -> 4
                'Z' -> 0
                else -> throw IllegalArgumentException("不支持的路径指令 $c")
            }
            if (i + n > toks.size) throw IllegalArgumentException("路径参数不足")
            out.add(Cmd(c, FloatArray(n) { toks[i + it].toFloat() }))
            i += n
            moved = c.uppercaseChar() == 'M'
        }
        return out
    }
}

/** 一个图标。[sizeDp] 是图形边长，容器多大都居中画。 */
internal class IconView(
    ctx: Context,
    private val name: String,
    tint: Int,
    private val sizeDp: Number = 24,
) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tint
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setWillNotDraw(false)
    }

    /**
     * 必须自己量。
     *
     * 不重写的话走 `View.onMeasure` 的默认实现，而 `getDefaultSize`
     * 在 **AT_MOST 模式下直接返回 spec 尺寸** —— 也就是父容器给的“剩余宽度”。
     * 后果：任何用 WRAP_CONTENT 放的图标都会撑满整行，把旁边 weight=1 的标题
     * 挤成 0 宽（实测表现：只剩图标、文字全不见）。
     * 同一原因下图标还会被画在超大盒子的正中（不在行首）。
     *
     * 尺寸取 sizeDp 与 minimumWidth/Height 的较大值：后者是调用方补的
     * 触控热区（如列表行尾的 ⋮ 要 HIT=48dp），不能被图标本身尺寸盖掉。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val base = Md3.dp(context, sizeDp)
        setMeasuredDimension(
            resolveSize(maxOf(base, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(maxOf(base, suggestedMinimumHeight), heightMeasureSpec),
        )
    }

    var tint: Int
        get() = paint.color
        set(v) { paint.color = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val strokes = Icons.strokes(name) ?: return
        val px = Md3.dp(context, sizeDp).toFloat()
        val s = px / 24f
        canvas.save()
        canvas.translate((width - px) / 2f, (height - px) / 2f)
        canvas.scale(s, s)
        for (stroke in strokes) {
            if (stroke.stroke) {
                // 原数据是描边：线宽写在 24 网格坐标里，缩放后自动跟着缩。
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
            } else {
                paint.style = Paint.Style.FILL
            }
            canvas.drawPath(stroke.path, paint)
        }
        canvas.restore()
    }
}
