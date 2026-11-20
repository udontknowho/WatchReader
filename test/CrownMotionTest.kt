package com.watchdl.test

import com.watchdl.core.CrownMotion

fun main() {
    var failures = 0
    fun check(name: String, condition: Boolean) {
        if (condition) println("PASS $name") else { failures++; println("FAIL $name") }
    }

    // 累加语义：余量攒起来，反转相减。
    CrownMotion(50).let { m ->
        m.add(100f)
        m.add(100f)
        check("events accumulate pending", m.pending == 200f && m.running)
        m.add(-30f)
        check("reverse input subtracts pending", m.pending == 170f)
        m.cancel()
        check("cancel clears pending and running", m.pending == 0f && !m.running)
    }

    // 每帧消费：按 dt/50 折算，但至少 1 px。
    CrownMotion(50).let { m ->
        m.add(100f)
        val a = m.step(16)
        check("one frame consumes a slice", a in 1..100 && m.pending < 100f && m.running)
        var guard = 0
        while (m.running && guard++ < 50) m.step(16)
        check("drains to zero and stops", m.pending == 0f && !m.running && guard < 50)
    }

    // 小于 1 像素的余量必须当帧走掉，否则 running 永远不退。
    CrownMotion(50).let { m ->
        m.add(0.4f)
        check("sub-pixel pending moves 1 px and stops", m.step(16) == 1 && !m.running)
    }

    // durationMs <= 0：一次走完。
    CrownMotion(0).let { m ->
        m.add(7f)
        check("zero duration completes immediately", m.step(16) == 7 && !m.running)
    }

    // 非法输入忽略。
    CrownMotion(50).let { m ->
        m.add(Float.NaN)
        m.add(Float.POSITIVE_INFINITY)
        m.add(0f)
        check("non-finite and zero are ignored", m.pending == 0f && !m.running)
    }

    // 大步长不越过余量。
    CrownMotion(50).let { m ->
        m.add(5f)
        check("single step never overshoots", m.step(1000) in 1..5)
    }

    // 方向：负余量必须返回负数。
    CrownMotion(50).let { m ->
        m.add(-80f)
        check("negative pending steps negative", m.step(16) < 0)
    }

    check("rotary source selects SCROLL", CrownMotion.delta(true, 9f, 2f, 5f) == -10f)
    check("rotary zero SCROLL falls back to VSCROLL", CrownMotion.delta(true, 2f, 0f, 5f) == -10f)
    check("non-rotary uses VSCROLL", CrownMotion.delta(false, 2f, 9f, 5f) == -10f)
    check("step at upper boundary is unchanged", CrownMotion.delta(true, 9f, 2f, 10f) == -20f)
    check("legacy step 60 clamps to 10", CrownMotion.delta(true, 9f, 2f, 60f) == -20f)
    check("zero step is ignored", CrownMotion.delta(true, 9f, 2f, 0f) == 0f)
    check("negative step is ignored", CrownMotion.delta(true, 9f, 2f, -5f) == 0f)
    check("NaN step is ignored", CrownMotion.delta(true, 9f, 2f, Float.NaN) == 0f)
    check("non-finite input is ignored", CrownMotion.delta(false, Float.NaN, 0f, 5f) == 0f)

    if (failures == 0) println("All checks passed") else kotlin.system.exitProcess(1)
}
