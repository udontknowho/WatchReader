package com.watchdl.core

/**
 * 取书源的统一接口。两个实现：
 * - [com.watchdl.FanqieSource] —— 网页端，无需签名，每章只给 185 字试读片段
 * - [FanqieApp] —— APP 接口，需签名，给完整正文
 *
 * 界面与下载层只依赖这个接口，换源不改调用点。
 */
interface BookSource {

    /** 搜索，返回候选书。 */
    fun search(keyword: String, page: Int = 1): List<BookHit>

    /** 取目录。`name`/`author` 由搜索页带入（接口本身不返回）。 */
    fun toc(bookId: String, name: String = "", author: String = ""): BookInfo

    /** 完整取书。 */
    fun open(hit: BookHit): BookInfo

    /**
     * 取正文。返回原文片段（可能是 XHTML），落盘存原文，
     * 导出时用 [Downloader.htmlToText] 转纯文本。
     */
    fun content(itemId: String, bookId: String): String
}
