package eu.kanade.tachiyomi.extension.all.gutenberg

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal const val GUTENBERG_URL = "https://www.gutenberg.org"

internal fun gutenbergUrl(path: String): String {
    val url = GUTENBERG_URL.toHttpUrl().resolve(path) ?: error("Invalid Project Gutenberg URL")
    require(url.host in setOf("www.gutenberg.org", "gutenberg.org") && url.username.isEmpty() && url.password.isEmpty()) {
        "This link does not belong to Project Gutenberg"
    }
    return url.newBuilder().scheme("https").host("www.gutenberg.org").build().toString()
}

/** Bounded, short-lived reuse of pages already requested by the user. */
internal class GutenbergHttp(private val client: OkHttpClient, private val headers: Headers) {
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Pair<Long, Document>>()

    suspend fun plainTextDocument(path: String): Document {
        val url = gutenbergUrl(path)
        return client.newCall(GET(url, headers)).awaitSuccess().use { response ->
            Jsoup.parse("<html><body></body></html>", response.request.url.toString()).apply {
                body().appendElement("pre").text(response.body.string())
            }
        }
    }

    suspend fun document(path: String): Document = mutex.withLock {
        val url = gutenbergUrl(path)
        val now = System.currentTimeMillis()
        cache[url]?.takeIf { now - it.first < 60_000 }?.let { return@withLock it.second.clone() }
        val document = client.newCall(GET(url, headers)).awaitSuccess().use { response ->
            // Never admit an error/challenge page into the metadata cache.
            Jsoup.parse(response.body.string(), response.request.url.toString())
        }
        if (cache.size >= 8) cache.remove(cache.keys.first())
        cache[url] = System.currentTimeMillis() to document
        document.clone()
    }
}

/** Coordinates requests and server-requested cooldowns without automatic retry storms. */
internal class GutenbergThrottle : Interceptor {
    private val lock = Any()
    private var nextRequest = 0L

    override fun intercept(chain: Interceptor.Chain): Response = synchronized(lock) {
        val wait = nextRequest - System.currentTimeMillis()
        if (wait > 60_000) throw IOException("Project Gutenberg requested a cooldown. Please try again later.")
        if (wait > 0) {
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Project Gutenberg request interrupted", e)
            }
        }
        nextRequest = System.currentTimeMillis() + 2_000
        val response = chain.proceed(chain.request())
        if (response.code == 429 || response.code == 503) {
            val now = System.currentTimeMillis()
            val value = response.header("Retry-After")
            val retryAt = value?.toLongOrNull()?.takeIf { it >= 0 }?.let {
                now + it.coerceAtMost((Long.MAX_VALUE - now) / 1000) * 1000
            } ?: runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrDefault(now + 60_000)
            nextRequest = maxOf(nextRequest, retryAt)
        }
        response
    }
}
