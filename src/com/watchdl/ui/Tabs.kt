package com.watchdl.ui

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.watchdl.MainActivity
import com.watchdl.ui.my.showMy
import com.watchdl.ui.shelf.showShelf
import com.watchdl.ui.source.showDiscover
import com.watchdl.ui.theme.Widgets

/**
 * 底部三栏，照 legado `ui/main/MainDestination`：书架 / 发现 / 我的。
 *
 * 2026-11-05 改版（用户判词）：底栏 64 dp 太大、三栏要能滑动切。现在底栏只剩 8 dp 的
 * 三个指示点，主入口是左右滑动；顶栏同时接上自动收起（25 dp 下滚消失、上滚拉回）。
 *
 * 参数是当前栏下标：0 书架、1 发现、2 我的。
 */
internal fun MainActivity.attachTabs(active: Int) {
    val rootTab = isRootTabScreen()
    // 顶栏自动收起只挂三根屏：子页（书源市场、榜单内容）的顶栏带返回箭头，
    // 收起来会把唯一出口一起收走。
    if (rootTab) Widgets.autoHideHeader(this, root)

    root.addView(Widgets.navDots(this, listOf("书架", "发现", "我的"), active) { i ->
        when (i) {
            // 重复点当前栏：书架回到顶部，发现 / 我的再点无动作（legado 同款）。
            0 -> if (active == 0) scrollToTop(root) else showShelf()
            1 -> if (active != 1) showDiscover()
            else -> if (active != 2) showMy()
        }
    })
    // 横滑只在三根屏上生效；子页返回 -1，手势直接放行给子控件。
    tabActive = if (rootTab) active else -1
}

/** 把屏里第一个纵向滚动区拉回顶部。找不到就不动 —— 空屏也是合法状态。 */
private fun scrollToTop(from: View) {
    Widgets.firstScroll(from)?.smoothScrollTo(0, 0)
}

// ══════════════════ 左右滑动切栏 ══════════════════

/**
 * 跟手横向滑动：拖动时整屏跟着手指走，松手按位移决定切栏还是弹回。
 *
 * 为什么不改成常驻三页的 ViewPager：三屏各自 `root.removeAllViews()` 现搭，
 * 改成常驻要重写每屏的搭建时机与返回栈。这里用「拖动位移 + 换屏后从另一侧滑入」
 * 近似同一手感，改动只在手势与收尾动画两处。
 *
 * 判定分两档（2026-11-06 改，用户报「滑到发现要很大力气」）：
 * 1. 位移：够屏宽 1/4 就换栏，原来取 1/3，手表小屏上要拖 60 dp 太费力。
 * 2. 速度：横速过屏宽 3 倍每秒也算换栏，「轻轻一划」得能切。
 * 方向比也从 1.6 松到 1.2，斜着划不再白滑。
 */
private var swiping = false
private var claimed = false
private var gaveUp = false
private var startX = 0f
private var startY = 0f
private var tracker: VelocityTracker? = null

/** 每秒划过屏宽的这个倍数，就算「甩」了一下，不看位移也换栏。 */
private const val FLING_SCREEN_PER_SEC = 3f

/** 位移到屏宽的几分之一就提前认领手势，不必等方向比攒够。 */
private const val EARLY_CLAIM_DIVISOR = 8f

internal fun MainActivity.handleTabSwipe(ev: MotionEvent): Boolean {
    val active = tabActive
    if (active !in 0..2) return false
    val slop = 12f
    when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            swiping = true
            claimed = false
            gaveUp = false
            startX = ev.rawX
            startY = ev.rawY
            tracker?.recycle()
            tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        }

        MotionEvent.ACTION_MOVE -> {
            if (!swiping || gaveUp) return false
            tracker?.addMovement(ev)
            val dx = ev.rawX - startX
            val dy = ev.rawY - startY
            if (!claimed) {
                val adx = Math.abs(dx)
                val ady = Math.abs(dy)
                if (adx <= slop) return false
                val w = resources.displayMetrics.widthPixels.toFloat()
                val fast = Math.abs(xVelocity()) > w * FLING_SCREEN_PER_SEC
                // 三条任一成立就认领：横向明显占优 / 位移已过屏宽 1/8 且不低于竖向 /
                // 甩得够快。原来是死守 1.6 方向比，斜着划永远等不到。
                val horizontal = adx > ady * 1.2f ||
                    (adx > w / EARLY_CLAIM_DIVISOR && adx > ady) ||
                    (fast && adx > ady)
                if (horizontal) {
                    claimed = true
                } else {
                    // 竖向明显占优才彻底让开，别跟列表抢纵向滚动。
                    if (ady > slop && ady > adx * 1.2f) gaveUp = true
                    return false
                }
            }
            // 到头了给阻尼，拉不动太多 —— 否则第一栏往右滑会露出整片空白。
            var d = dx
            if ((active == 0 && d > 0) || (active == 2 && d < 0)) d *= 0.3f
            root.translationX = d
            return true
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            if (!swiping) return false
            swiping = false
            tracker?.addMovement(ev)
            val vx = xVelocity()
            tracker?.recycle()
            tracker = null
            if (!claimed) return false
            settle(active, ev.actionMasked == MotionEvent.ACTION_CANCEL, vx)
            return true
        }
    }
    return false
}

/** 当前横扫速度，单位 px 每秒。没有轨迹时给 0。 */
private fun xVelocity(): Float {
    val t = tracker ?: return 0f
    t.computeCurrentVelocity(1000)
    return t.xVelocity
}

/** 松手后的收尾：甩得够快或拖得够远就换屏，否则弹回。 */
private fun MainActivity.settle(active: Int, cancelled: Boolean, vx: Float) {
    val w = resources.displayMetrics.widthPixels.toFloat()
    val dx = root.translationX
    val fast = Math.abs(vx) > w * FLING_SCREEN_PER_SEC
    val target = when {
        cancelled -> -1
        // 速度优先：甩一下就不看位移了，这是「轻轻一划」该有的事。
        fast && vx < 0f -> active + 1
        fast && vx > 0f -> active - 1
        dx <= -w * 0.25f -> active + 1
        dx >= w * 0.25f -> active - 1
        else -> -1
    }
    if (target !in 0..2) {
        root.animate().translationX(0f).setDuration(160).start()
        return
    }
    val dir = if (target > active) -1f else 1f
    root.animate().translationX(dir * w).setDuration(150).withEndAction {
        when (target) {
            0 -> showShelf()
            1 -> showDiscover()
            else -> showMy()
        }
        // 新屏从另一侧滑进来，两段动画接上才像一页推一页。
        root.translationX = -dir * w
        root.animate().translationX(0f).setDuration(200).start()
    }.start()
}
