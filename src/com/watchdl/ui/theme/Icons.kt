package com.watchdl.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 图标：Material Symbols 的 24dp 填充路径。
 *
 * 上一版是手画的描边图形（齿轮成圆圈加辐条、书架成三根竖条），小尺寸下认不出原形。
 * 现在照搬 Material 官方路径数据，与 legado 用的 `androidx.compose.material.icons`
 * 同一套图形。只存 Path 数据，不引入 Compose，也不加 XML 资源。
 */
internal object Icons {

    /**
     * 图标名 → 24×24 网格的 SVG 路径数据。
     *
     * 取值来自 Material Icons（filled 档），坐标已经按 24dp 画布写死，不做缩放。
     * 不支持弧线指令 `A`：这批图标都用不上，真遇上 [IconPath.parse] 会抛异常，
     * 自检 `IconDataTest` 会在编译期之前就把它拦下。
     */
    val DATA = mapOf(
        "back" to "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
        // 行尾箭头：legado `ClickableSettingItem` 的 ChevronRight，设置各页的可跳行都用它。
        "chevron" to "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
        "menu" to "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z",
        "search" to "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3" +
            "S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5z" +
            "m-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
        "more" to "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2" +
            " 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
        "settings" to "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58" +
            "c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96" +
            "c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84" +
            "c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96" +
            "c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58" +
            "c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32" +
            "c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84" +
            "c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22" +
            "l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6" +
            "s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
        "refresh" to "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-8 8s3.57 8 8 8" +
            "c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6" +
            "c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
        "download" to "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
        "list" to "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z",
        // 文件管理页的行首图标，照 legado `ItemFileBinding` 的 folder / file 两枚。
        "folder" to "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8" +
            "c0-1.1-.9-2-2-2h-8l-2-2z",
        "file" to "M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6H6z" +
            "m7 7V3.5L18.5 9H13z",
        "grid" to "M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4z" +
            "m-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z",
        "plus" to "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
        "close" to "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
        "delete" to "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
        "play" to "M8 5v14l11-7z",
        "stop" to "M6 6h12v12H6z",
        "bookmark" to "M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z",
        // 阅读底栏的亮度行：Material `light_mode`（太阳 + 八道光线），与 legado
        // `Icons.Default.BrightnessMedium` 同一形状族。
        "brightness" to "M12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6z" +
            "m0-3c1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3 1.34 3 3 3z" +
            "M11 1h2v4h-2zM11 19h2v4h-2zM1 11h4v2H1zM19 11h4v2h-4z" +
            "M4.22 5.64l1.42-1.42 2.12 2.12-1.42 1.42zM16.24 17.66l1.42-1.42 2.12 2.12-1.42 1.42z" +
            "M4.22 18.36l2.12-2.12 1.42 1.42-2.12 2.12zM16.24 6.34l2.12-2.12 1.42 1.42-2.12 2.12z",
        "swap" to "M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z",
        "history" to "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7" +
            "-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21" +
            "c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
        // 底部三栏：legado 的 `AppIcons.mainDestination` 就是这三枚。
        "shelf" to "M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12" +
            "c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-1 9H9V9h10v2zm-4 4H9v-2h6v2zm4-8H9V5h10v2z",
        "explore" to "M12 10.9c-.61 0-1.1.49-1.1 1.1s.49 1.1 1.1 1.1c.61 0 1.1-.49 1.1-1.1" +
            "s-.49-1.1-1.1-1.1zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z" +
            "m2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19z",
        "mine" to "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2" +
            "c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
        "warning" to "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
        "check" to "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
        "cloud" to "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14" +
            "c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z",
        // 下面 6 个照 Material Symbols 24dp 抄，补书架 ⋮ 菜单要用的图标。
        "wifi" to "M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3" +
            "c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z",
        "save" to "M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zm-5 16" +
            "c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm3-10H5V5h10v4z",
        "link" to "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7" +
            "c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1" +
            "s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z",
        "edit" to "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41" +
            "l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
        "upload" to "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6z" +
            "m4 18H6V4h7v5h5v11zm-8-3.01l1.41 1.41L11 14.84V19h2v-4.16l1.59 1.59L16 15.01L12.01 11 8 15z" +
            "M13 9V3.5L18.5 9H13z",
        "carousel" to "M4 6h4v12H4zm6-2h4v16h-4zm6 2h4v12h-4z",
        // 书架选择模式的全选键，照 legado `Icons.Default.SelectAll` 取同一枚图形。
        "select_all" to "M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2z" +
            "m10-6h-2v2h2V3zm6 0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2z" +
            "M9 3H7v2h2V3zm2 18h2v-2h-2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2z" +
            "m0 8h2v-2h-2v2zm-4 4h2v-2h-2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z",
    )

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

    private val cache = HashMap<String, Path?>()

    /** 字符或图标名 → 图标名；不是图标返回 null（照旧当文字渲染）。 */
    fun name(glyph: String): String? {
        val n = ALIAS[glyph] ?: glyph
        return n.takeIf { DATA.containsKey(it) }
    }

    fun path(name: String): Path? = cache.getOrPut(name) { build(name) }

    /** 造一个图标 View。[sizeDp] 是图形本身的边长，容器可以更大（热区）。 */
    fun view(ctx: Context, name: String, tint: Int, sizeDp: Number = 24): IconView =
        IconView(ctx, name, tint, sizeDp)

    private fun build(name: String): Path? {
        val ops = DATA[name]?.let { runCatching { IconPath.parse(it) }.getOrNull() } ?: return null
        val p = Path()
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
        return p
    }
}

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
        val path = Icons.path(name) ?: return
        val px = Md3.dp(context, sizeDp).toFloat()
        val s = px / 24f
        canvas.save()
        canvas.translate((width - px) / 2f, (height - px) / 2f)
        canvas.scale(s, s)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
