package eu.kanade.tachiyomi.extension.en.novelbuddy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

internal class NovelBuddyApi(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(page: Int, selection: NovelBuddySearchSelection): NovelBuddySearchData {
        val url = "$API_URL/titles/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .apply {
                selection.sort?.let { addQueryParameter("sort", it) }
                selection.query?.let { addQueryParameter("q", it) }
                selection.status?.let { addQueryParameter("status", it) }
                selection.contentRating?.let { addQueryParameter("content_rating", it) }
                selection.genre?.let { addQueryParameter("genres", it) }
                selection.author?.let { addQueryParameter("author", it) }
                selection.minimumChapters?.let { addQueryParameter("min_ch", it.toString()) }
                selection.maximumChapters?.let { addQueryParameter("max_ch", it.toString()) }
            }
            .build()
        return get<NovelBuddySearchResponse>(url.toString()).data
    }

    suspend fun title(slug: String): NovelBuddyTitle = get<NovelBuddyTitleResponse>(
        "$API_URL/titles/by-slug/$slug?include=details",
    ).data.title

    suspend fun chapters(titleId: String): List<NovelBuddyChapter> {
        val result = get<NovelBuddyChaptersResponse>("$API_URL/titles/$titleId/chapters").data.chapters
        require(result.size <= MAX_CHAPTERS) { "NovelBuddy returned too many chapters" }
        require(result.distinctBy(NovelBuddyChapter::id).size == result.size) {
            "NovelBuddy returned duplicate chapter identities"
        }
        return result
    }

    suspend fun chapter(titleSlug: String, chapterSlug: String): NovelBuddyChapterContent =
        get<NovelBuddyChapterResponse>(
            "$API_URL/titles/by-slug/$titleSlug/chapters/$chapterSlug?include=details",
        ).data.chapter

    private suspend inline fun <reified T> get(url: String): T = client.newCall(GET(url, headers)).awaitSuccess().use {
        json.decodeFromString(it.body.string())
    }

    private companion object {
        const val API_URL = "https://api.novelbuddy.me"
        const val PAGE_SIZE = 24
        const val MAX_CHAPTERS = 10_000
    }
}
