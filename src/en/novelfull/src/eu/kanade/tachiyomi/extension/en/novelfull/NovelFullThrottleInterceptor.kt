package eu.kanade.tachiyomi.extension.en.novelfull

import okhttp3.Interceptor
import okhttp3.Response
import java.time.ZonedDateTime

/**
 * Serializes page requests to the HTML-only service at the conservative discovered interval. Covers under
 * `/uploads/`, which Katari loads through the source's client, pass straight through like a browser loads them.
 */
internal class NovelFullThrottleInterceptor : Interceptor {
    private val lock = Any()
    private var nextRequestAtMillis = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.encodedPath.startsWith(UPLOADS_PATH)) return chain.proceed(chain.request())
        return throttled(chain)
    }

    private fun throttled(chain: Interceptor.Chain): Response = synchronized(lock) {
        waitUntilAllowed()
        var response = chain.proceed(chain.request())
        if (response.code == TOO_MANY_REQUESTS) {
            val retryAt = retryAt(response.header(RETRY_AFTER_HEADER))
            response.close()
            nextRequestAtMillis = maxOf(nextRequestAtMillis, retryAt)
            waitUntilAllowed()
            response = chain.proceed(chain.request())
        }
        nextRequestAtMillis = System.currentTimeMillis() + REQUEST_INTERVAL_MILLIS
        response
    }

    private fun waitUntilAllowed() {
        val waitMillis = nextRequestAtMillis - System.currentTimeMillis()
        if (waitMillis > 0) Thread.sleep(waitMillis)
    }

    private fun retryAt(value: String?): Long {
        val now = System.currentTimeMillis()
        val seconds = value?.trim()?.toLongOrNull()
        if (seconds != null) return now + seconds.coerceAtLeast(0) * 1_000L
        return runCatching { ZonedDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(now + REQUEST_INTERVAL_MILLIS)
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val RETRY_AFTER_HEADER = "Retry-After"
        const val REQUEST_INTERVAL_MILLIS = 1_000L
        const val UPLOADS_PATH = "/uploads/"
    }
}
