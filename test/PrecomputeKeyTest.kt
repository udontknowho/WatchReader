import com.watchdl.core.PrecomputeKey

fun main() {
    var checks = 0
    fun eq(actual: Any, expected: Any) {
        check(actual == expected) { "expected=$expected actual=$actual" }
        checks++
    }
    fun ne(a: Any, b: Any) {
        check(a != b) { "必须不同，但两边都是 $a" }
        checks++
    }

    // 同参同串：缓存键靠它，同样的设置必须每次都算出同一把钥匙。
    val base = PrecomputeKey.of(18f, 1.5f, 0f, 0, false, 0, 0, true)
    eq(PrecomputeKey.of(18f, 1.5f, 0f, 0, false, 0, 0, true), base)

    // 八项逐项改一个：任何一项变了整批预排件都要失效，漏一项就是错件喂回控件。
    ne(PrecomputeKey.of(19f, 1.5f, 0f, 0, false, 0, 0, true), base)      // 字号
    ne(PrecomputeKey.of(18f, 1.8f, 0f, 0, false, 0, 0, true), base)      // 行距倍数
    ne(PrecomputeKey.of(18f, 1.5f, 0.05f, 0, false, 0, 0, true), base)   // 字距
    ne(PrecomputeKey.of(18f, 1.5f, 0f, 2, false, 0, 0, true), base)      // 字重
    ne(PrecomputeKey.of(18f, 1.5f, 0f, 0, true, 0, 0, true), base)       // 斜体
    ne(PrecomputeKey.of(18f, 1.5f, 0f, 0, false, 2, 0, true), base)      // 缩进
    ne(PrecomputeKey.of(18f, 1.5f, 0f, 0, false, 0, 1, true), base)      // 段距
    ne(PrecomputeKey.of(18f, 1.5f, 0f, 0, false, 0, 0, false), base)     // 对齐

    // 段距 / 对齐不在 PrecomputedText.Params 里，只能靠指纹挡住：这里正是它们的地盘。
    ne(PrecomputeKey.of(18f, 1.5f, 0f, 0, false, 0, 2, true),
        PrecomputeKey.of(18f, 1.5f, 0f, 0, false, 0, 1, true))
    ne(PrecomputeKey.of(18f, 1.5f, 0f, 0, false, 0, 0, true),
        PrecomputeKey.of(18f, 1.5f, 0f, 0, false, 0, 0, false))

    // 布尔档位不能与数字档位串味：i=1 与 n=1 必须落在不同字段上。
    ne(PrecomputeKey.of(18f, 1.5f, 0f, 0, true, 0, 0, true),
        PrecomputeKey.of(18f, 1.5f, 0f, 0, false, 1, 0, true))

    println("PrecomputeKeyTest: $checks checks passed")
}
