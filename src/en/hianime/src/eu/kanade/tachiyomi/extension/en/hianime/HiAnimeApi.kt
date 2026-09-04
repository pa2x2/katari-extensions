package eu.kanade.tachiyomi.extension.en.hianime

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal class HiAnimeApi(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ajaxHeaders = headers.newBuilder()
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    suspend fun document(pathOrUrl: String): Document {
        val url = absoluteUrl(pathOrUrl)
        return text(url, headers).let { Jsoup.parse(it, url) }
    }

    suspend fun episodeDocument(animeId: String): Document = ajaxDocument("/api/theme/episode/list/$animeId")

    suspend fun serverDocument(episodeId: String): Document = ajaxDocument(
        "/api/theme/episode/servers?episodeId=$episodeId",
    )

    suspend fun text(url: String, requestHeaders: Headers): String =
        client.newCall(GET(url, requestHeaders)).awaitSuccess().use { it.body.string() }

    private suspend fun ajaxDocument(path: String): Document {
        val url = absoluteUrl(path)
        val body = text(url, ajaxHeaders)
        val payload = json.parseToJsonElement(body).jsonObject
        check(payload["status"]?.jsonPrimitive?.contentOrNull != "false") {
            "HiAnime rejected the API request"
        }
        val html = payload["html"]?.jsonPrimitive?.contentOrNull
            ?: error("HiAnime returned no HTML payload")
        return Jsoup.parse(html, BASE_URL)
    }

    private fun absoluteUrl(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("https://") || pathOrUrl.startsWith("http://") -> pathOrUrl
        else -> "$BASE_URL/${pathOrUrl.removePrefix("/")}"
    }

    private companion object {
        const val BASE_URL = "https://hianime.at"
    }
}
