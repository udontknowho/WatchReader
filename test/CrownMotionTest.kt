package com.watchdl.test

import com.watchdl.core.CrownMotion

fun main() {
    var failures = 0
    fun check(name: String, condition: Boolean) {
        if (condition) println("PASS $name") else { failures++; println("FAIL $name") }
    }

    val motion = CrownMotion(50)
    motion.add(100, 1000, 60f, 0)
    motion.add(100, 1000, 60f, 10)
    check("fast events accumulate target", motion.target == 220f)
    check("new input preserves current interpolated position", motion.frame(1000, 10) in 100..220)
    check("frame reaches accumulated target", motion.frame(1000, 60) == 220 && !motion.running)

    motion.add(220, 1000, 80f, 100)
    motion.add(220, 1000, -30f, 110)
    check("reverse input subtracts pending distance", motion.target == 270f)
    check("reverse glide reaches revised target", motion.frame(1000, 160) == 270)

    motion.add(10, 100, -50f, 200)
    check("top boundary clamps", motion.target == 0f)
    check("zero duration completes immediately", CrownMotion(0).also { it.add(0, 10, 5f, 0) }.let { it.frame(10, 0) == 5 && !it.running })

    check("rotary source selects SCROLL", CrownMotion.delta(true, 9f, 2f, 60f) == -120f)
    check("rotary zero SCROLL falls back to VSCROLL", CrownMotion.delta(true, 2f, 0f, 60f) == -120f)
    check("non-rotary uses VSCROLL", CrownMotion.delta(false, 2f, 9f, 60f) == -120f)
    check("non-finite input is ignored", CrownMotion.delta(false, Float.NaN, 0f, 60f) == 0f)

    if (failures == 0) println("All checks passed") else kotlin.system.exitProcess(1)
}
