import com.watchdl.core.ChapterTextCache
import com.watchdl.core.ChapterTextKey

fun main() {
    val source = Any()
    fun key(id: String, src: Any? = source, name: String = "书", title: String = "章") =
        ChapterTextKey(src, "book", name, "作者", id, title)
    val cache = ChapterTextCache(maxEntries = 2, maxChars = 8)
    cache.put(key("1"), "abc")
    cache.put(key("2"), "def")
    check(cache.get(key("1")) == "abc")
    cache.put(key("3"), "gh")
    check(cache.get(key("2")) == null) // 读取刷新最近使用顺序。
    check(cache.get(key("1")) == "abc")
    check(cache.get(key("3")) == "gh")
    check(cache.get(key("1", Any())) == null) // 相同地址不同来源。
    check(cache.get(key("1", name = "另一本")) == null)
    check(cache.get(key("1", title = "另一个章")) == null)
    cache.put(key("1"), "1234567") // 字符上限先于条数淘汰。
    check(cache.get(key("3")) == null)
    check(cache.get(key("1")) == "1234567")
    cache.put(key("1"), "")
    check(cache.get(key("1")) == null) // 空正文不缓存。
    cache.put(key("big"), "123456789")
    check(cache.get(key("big")) == null)
    cache.put(key("ok"), "12345678")
    check(cache.get(key("ok")) == "12345678")
    check(runCatching { ChapterTextCache(0, 8) }.isFailure)
    check(runCatching { ChapterTextCache(2, 0) }.isFailure)
    val workers = (0 until 4).map { worker ->
        Thread {
            repeat(100) {
                cache.put(key("$worker"), "text")
                cache.get(key("$worker"))
            }
        }.apply { start() }
    }
    workers.forEach { it.join() }
    println("ChapterTextCacheTest: bounds, LRU, identity and concurrent access checked")
}
