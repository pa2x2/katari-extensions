package eu.kanade.tachiyomi.extension.en.readnovelfull

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal class ReadNovelFullApi(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    suspend fun document(path: String): Document = client.newCall(GET(url(path), headers)).awaitSuccess().use {
        Jsoup.parse(it.body.string(), it.request.url.toString())
    }

    suspend fun chapterArchive(novelId: String): Document = document(
        "$BASE_URL/ajax/chapter-archive".toHttpUrl().newBuilder()
            .addQueryParameter("novelId", novelId)
            .build()
            .toString(),
    )

    fun pagePath(path: String, page: Int): String {
        require(page >= 1) { "page must be positive" }
        if (page == 1) return path
        return url(path).toHttpUrl().newBuilder()
            .setQueryParameter("page", page.toString())
            .build()
            .toString()
    }

    fun searchPath(query: String): String = "$BASE_URL/novel-list/search".toHttpUrl().newBuilder()
        .addQueryParameter("keyword", query.trim())
        .build()
        .toString()

    private fun url(path: String): String = when {
        path.startsWith("https://") || path.startsWith("http://") -> path
        else -> "$BASE_URL/${path.removePrefix("/")}"
    }

    private companion object {
        const val BASE_URL = "https://readnovelfull.com"
    }
}
