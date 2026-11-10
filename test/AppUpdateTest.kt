package com.watchdl.test

import com.watchdl.core.AppUpdate

/** 离线测试入口：com.watchdl.test.AppUpdateTestKt。依赖 AppUpdate.kt 与 TinyJson.kt。 */
fun main() {
    var count = 0
    fun verify(ok: Boolean) { check(ok) { "case ${count + 1} failed" }; count++ }
    fun invalid(json: String) { verify(runCatching { AppUpdate.parseRelease(json) }.isFailure) }
    val page = "${AppUpdate.PROJECT_URL}/releases/tag/v1.6.1"
    val apk = "${AppUpdate.PROJECT_URL}/releases/download/v1.6.1/WatchReader.apk"
    fun release(tag: String = "v1.6.1", assets: String = "[]") =
        """{"tag_name":"$tag","draft":false,"prerelease":false,"html_url":"$page","assets":$assets}"""
    verify(AppUpdate.compareVersions("1.6.10", "v1.6.9") > 0)
    verify(AppUpdate.compareVersions("1.6.1", "1.6.0") > 0)
    verify(AppUpdate.compareVersions("1.7.0", "1.6.99") > 0)
    verify(AppUpdate.compareVersions("2.0.0", "1.99.99") > 0)
    verify(AppUpdate.compareVersions("v1.6.1+build.3", "1.6.1+other") == 0)
    verify(AppUpdate.compareVersions("1.6.1", "1.6.1-rc.9") > 0)
    verify(AppUpdate.compareVersions("1.6.1-rc.10", "1.6.1-rc.2") > 0)
    verify(AppUpdate.compareVersions("1.6.1-alpha", "1.6.1-alpha.1") < 0)
    verify(AppUpdate.compareVersions("1.6.1-1", "1.6.1-alpha") < 0)
    verify(AppUpdate.compareVersions("999999999999999999999.0.0", "2.0.0") > 0)
    for (bad in listOf("1.6", "01.6.1", "1.6.1-01", "", "1.6.1.2")) {
        verify(runCatching { AppUpdate.compareVersions(bad, "1.6.1") }.isFailure)
    }
    verify(AppUpdate.parseRelease(release()).downloadUrl == page)
    val assets = """[{"name":"notes.txt","browser_download_url":"https://example.com"},{"name":"WatchReader.apk","browser_download_url":"$apk"}]"""
    val parsed = AppUpdate.parseRelease(release(assets = assets))
    verify(parsed.version == "v1.6.1" && parsed.pageUrl == page && parsed.downloadUrl == apk)
    verify(AppUpdate.parseRelease(release().replace("https://", "https:\\/\\/")).pageUrl == page)
    verify(AppUpdate.parseRelease(release().replace("\"assets\":[]", "\"assets\":[],\"body\":\"quote \\\"tag_name\\\": fake\" ")).version == "v1.6.1")
    invalid(release().replace("\"draft\":false", "\"draft\":true"))
    invalid(release().replace("\"prerelease\":false", "\"prerelease\":true"))
    invalid(release("v1.6.1-rc.1"))
    invalid(release("banana"))
    invalid(release().replace("\"draft\":false,", ""))
    invalid(release().replace("\"draft\":false", "\"draft\":\"false\""))
    invalid(release().replace("\"tag_name\":\"v1.6.1\"", "\"tag_name\":null"))
    invalid(release().replace(page, "https://evil.example/releases/tag/v1.6.1"))
    invalid(release().replace(page, "$page?redirect=evil"))
    invalid(release(assets = "{}"))
    invalid(release(assets = "[null]"))
    invalid(release(assets = assets.replace(apk, "javascript:evil")))
    invalid(release().dropLast(1))
    invalid(release() + " garbage")
    invalid(release().replace("\"draft\":false", "\"draft\":false,\"draft\":true"))
    invalid(release().replace("\"draft\":false", "\"draft\":false,"))
    invalid("[]")
    invalid("""{"message":"rate limit exceeded"}""")
    invalid(release().replace("\"assets\":[]", "\"assets\":[],\"body\":\"bad\\q\""))
    invalid(" ".repeat(1024 * 1024 + 1))
    println("AppUpdateTest: $count passed")
}
