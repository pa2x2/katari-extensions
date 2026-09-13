package eu.kanade.tachiyomi.extension.en.brightnovels

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

internal const val BRIGHTNOVELS_URL = "https://brightnovels.com"

internal class BrightNovelsApi(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        // The site emits explicit nulls for absent optional values; fall back to defaults for those.
        explicitNulls = false
    }

    suspend fun catalogue(page: Int, params: Map<String, String>): BrightNovelsSeriesListPage {
        val url = "$BRIGHTNOVELS_URL/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val props = inertiaProps(url.toString(), "Series/Index")
        return props.required("seriesList")
    }

    suspend fun search(query: String): List<BrightNovelsSeriesItem> {
        val url = "$BRIGHTNOVELS_URL/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .build()
        return get<BrightNovelsSearchResponse>(url.toString()).data.series
    }

    suspend fun seriesDetail(slug: String): BrightNovelsSeriesDetail {
        val url = "$BRIGHTNOVELS_URL/series".toHttpUrl().newBuilder()
            .addPathSegment(slug)
            .build()
        return inertiaProps(url.toString(), "Series/Show").required("series")
    }

    suspend fun allChapters(slug: String): BrightNovelsChaptersResponse {
        val url = "$BRIGHTNOVELS_URL/series".toHttpUrl().newBuilder()
            .addPathSegment(slug)
            .addPathSegment("chapters")
            .build()
        val result = get<BrightNovelsChaptersResponse>(url.toString())
        require(result.chapters.size <= MAX_CHAPTERS) { "Bright Novels returned too many chapters" }
        require(result.chapters.distinctBy(BrightNovelsChapterEntry::id).size == result.chapters.size) {
            "Bright Novels returned duplicate chapter identities"
        }
        return result
    }

    suspend fun chapterShow(seriesSlug: String, chapterSlug: String): BrightNovelsChapterShow {
        val url = "$BRIGHTNOVELS_URL/series".toHttpUrl().newBuilder()
            .addPathSegment(seriesSlug)
            .addPathSegment(chapterSlug)
            .build()
        val props = inertiaProps(url.toString(), "Chapter/Show")
        return BrightNovelsChapterShow(
            chapter = props.required("chapter"),
            unlocked = props["isUnlocked"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private suspend fun inertiaProps(url: String, component: String): JsonObject {
        val body = client.newCall(GET(url, headers)).awaitSuccess().use { it.body.string() }
        // Jsoup decodes the HTML-escaped JSON payload carried by the data-page attribute.
        val raw = Jsoup.parse(body, url).selectFirst("[data-page]")?.attr("data-page")
            .takeUnless { it.isNullOrBlank() }
            ?: error("Bright Novels did not return page data. Open the source in WebView and try again.")
        val page = runCatching { json.decodeFromString<BrightNovelsInertiaPage>(raw) }.getOrNull()
            ?: error("Bright Novels returned an unreadable page. Open the source in WebView and try again.")
        require(page.component == component) {
            "Bright Novels returned an unexpected page. Open the source in WebView and try again."
        }
        return page.props
    }

    private suspend inline fun <reified T> get(url: String): T =
        client.newCall(GET(url, headers)).awaitSuccess().use {
            json.decodeFromString(it.body.string())
        }

    private inline fun <reified T> JsonObject.required(name: String): T =
        get(name)?.let { json.decodeFromJsonElement(it) }
            ?: error("Bright Novels omitted $name. Open the source in WebView and try again.")

    private companion object {
        const val MAX_CHAPTERS = 10_000
    }
}
