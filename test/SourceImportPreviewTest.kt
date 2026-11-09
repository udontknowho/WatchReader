package com.watchdl.core

/** 预览纯逻辑回归：不触碰文件系统或网络。 */
fun main() {
    val valid = """{"bookSourceName":"alpha","bookSourceUrl":"https://example.com","searchUrl":"/s","ruleSearch":{"bookList":".b","name":"a@text","bookUrl":"a@href"},"ruleToc":{"chapterList":".c","chapterName":"a@text","chapterUrl":"a@href"},"ruleContent":{"content":".body"}}"""
    val preview = SourceImportPreview.parse("[$valid, $valid]")
    check(preview.ready == 1 && preview.duplicates == 1 && preview.failed == 0)
    check(preview.payload.startsWith("[") && preview.payload.contains("alpha"))
    check(SourceImportPreview.parse("[$valid]", setOf("alpha")).duplicates == 1)
    check(SourceImportPreview.parse("{broken").failed == 1)
    check(SourceImportPreview.parse("[{\"bookSourceName\":\"cut\"").failed == 1)
    check(SourceImportPreview.parse("[]").failed == 1)
    val disabled = valid.replace("\"searchUrl\":\"/s\"", "\"searchUrl\":\"/s\",\"enabled\":false")
    check(SourceImportPreview.parse("[$disabled]").failed == 1)
    val warning = valid.replace("\"content\":\".body\"", "\"content\":\"\"")
    val warned = SourceImportPreview.parse(warning)
    check(warned.ready == 1 && warned.entries.single().issue != null)
    val broken = """{"name":"broken","url":"","search":{},"toc":{}}"""
    check(SourceImportPreview.parse(broken).failed == 1)
    val script = valid.replace("\"searchUrl\":\"/s\"", "\"searchUrl\":\"@js: 'https://example.com/s'\"")
    val scriptPreview = SourceImportPreview.parse(script)
    check(scriptPreview.ready == 1)
    check((JsonParser.parse(scriptPreview.payload) as List<*>).size == 1)
    println("SourceImportPreviewTest passed")
}
