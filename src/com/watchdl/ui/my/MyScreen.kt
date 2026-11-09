package com.watchdl.ui.my

import com.watchdl.MainActivity
import com.watchdl.ui.download.showSettingsRoot

/** 兼容旧调用：所有次级页面返回四栏设置根页，不再生成旧三栏“我的”。 */
internal fun MainActivity.showMy() = showSettingsRoot()
