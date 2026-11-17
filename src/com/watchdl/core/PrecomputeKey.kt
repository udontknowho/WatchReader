package com.watchdl.core

/**
 * 预排件的参数指纹。
 *
 * 命中必须「一模一样」。`android.text.PrecomputedText.Params` 里只有 paint、方向、
 * 断行策略三类，**段距与两端对齐不在里面**：只比 Params 就命中，改段距或关对齐后
 * 仍会把旧排法的件喂回控件 —— 段距悄悄失效，读者只会觉得「设置不生效」。
 * 所以八项排版档位单独进指纹，任一项变了就让整批失效。
 *
 * 正文内容不进指纹：那是 [ReaderPrecompute] 用长度 + 哈希单独比的一层，
 * 两者都不能省 —— 换源补章会换掉正文而档位不变。
 *
 * 纯算术，不碰 Android 类，JVM 自检里直接跑。
 */
object PrecomputeKey {

    /** 八项排版档位合成一个可比较的串。顺序固定，字段带前缀便于看日志。 */
    fun of(
        sizeSp: Float,
        spacingMult: Float,
        letterEm: Float,
        weightLevel: Int,
        italic: Boolean,
        indentLevel: Int,
        paraLevel: Int,
        justify: Boolean,
    ): String = "s=$sizeSp h=$spacingMult l=$letterEm w=$weightLevel " +
        "i=${if (italic) 1 else 0} n=$indentLevel p=$paraLevel j=${if (justify) 1 else 0}"
}
