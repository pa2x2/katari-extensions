package eu.kanade.tachiyomi.extension.en.hianime

import eu.kanade.tachiyomi.source.entry.PlaybackDescriptor
import eu.kanade.tachiyomi.source.entry.PlaybackSelection
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import eu.kanade.tachiyomi.source.entry.VideoPlaybackOption
import eu.kanade.tachiyomi.source.entry.VideoRequest
import eu.kanade.tachiyomi.source.entry.VideoStream
import eu.kanade.tachiyomi.source.entry.VideoStreamType
import eu.kanade.tachiyomi.source.entry.VideoSubtitle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

internal data class HiAnimeResolvedPlayback(
    val descriptor: PlaybackDescriptor,
    val subtitles: List<VideoSubtitle>,
)

internal class HiAnimePlaybackResolver(
    private val api: HiAnimeApi,
    private val sourceHeaders: Headers,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = linkedMapOf<CacheKey, CacheEntry>()

    suspend fun resolve(chapter: SEntryChapter, selection: PlaybackSelection): HiAnimeResolvedPlayback {
        val requestKey = CacheKey(chapter.url, selection.dubKey, selection.sourceQualityKey)
        getCached(requestKey)?.let { return it }

        val chapterUrl = chapter.url.toHttpUrl(baseUrl)
        val episodeId = chapterUrl.queryParameter("ep")
            ?: error("HiAnime chapter has no episode ID")
        val servers = HiAnimeParser.parseServers(api.serverDocument(episodeId))
        check(servers.isNotEmpty()) { "HiAnime returned no playback servers" }

        val selectedType = selection.dubKey
            ?.let { key -> servers.firstOrNull { it.type == key }?.type }
            ?: servers.firstOrNull { it.type == "sub" }?.type
            ?: servers.first().type
        val typedServers = servers.filter { it.type == selectedType }
        val selectedServer = selection.sourceQualityKey
            ?.let { key -> typedServers.firstOrNull { it.key == key } }
            ?: typedServers.first()
        val candidates = listOf(selectedServer) + typedServers.filterNot { it == selectedServer }

        var failure: Exception? = null
        var provider: ProviderMedia? = null
        var resolvedServer: HiAnimeServer? = null
        for (candidate in candidates) {
            try {
                provider = resolveProvider(candidate, chapterUrl.toString())
                resolvedServer = candidate
                break
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = error
            }
        }
        val media = provider ?: throw failure ?: error("HiAnime could not resolve playback")
        val server = resolvedServer ?: error("HiAnime could not select a playback server")
        val dubs = servers.distinctBy(HiAnimeServer::type).map {
            VideoPlaybackOption(key = it.type, label = it.type.uppercase())
        }
        val qualities = typedServers.distinctBy(HiAnimeServer::key).map {
            VideoPlaybackOption(key = it.key, label = it.name, description = it.url.toHttpUrl().host)
        }
        val resolvedSelection = PlaybackSelection(
            dubKey = server.type,
            sourceQualityKey = server.key,
        )
        val result = HiAnimeResolvedPlayback(
            descriptor = PlaybackDescriptor(
                selection = resolvedSelection,
                dubs = dubs,
                sourceQualities = qualities,
                streams = listOf(
                    VideoStream(
                        request = VideoRequest(media.streamUrl, media.headers),
                        label = server.name,
                        type = VideoStreamType.HLS,
                        key = "${server.type}:${server.key}",
                    ),
                ),
            ),
            subtitles = media.subtitles,
        )
        putCached(requestKey, result)
        putCached(CacheKey(chapter.url, server.type, server.key), result)
        return result
    }

    private suspend fun resolveProvider(server: HiAnimeServer, watchUrl: String): ProviderMedia = when {
        server.url.toHttpUrl().host.endsWith("zokoanime.video") -> resolveZoko(server, watchUrl)
        server.url.toHttpUrl().host.endsWith("megaplay.buzz") -> resolveMegaPlay(server, watchUrl)
        else -> error("Unsupported HiAnime server: ${server.url.toHttpUrl().host}")
    }

    private suspend fun resolveZoko(server: HiAnimeServer, watchUrl: String): ProviderMedia {
        val html = api.text(server.url, embedHeaders(watchUrl))
        val encoded = ZOKO_PAYLOAD_REGEX.find(html)?.groupValues?.get(1)
            ?: error("ZokoAnime returned no playback payload")
        val decoded = Base64.getDecoder().decode(encoded)
        val key = ZOKO_KEY.toByteArray(StandardCharsets.UTF_8)
        decoded.indices.forEach { index -> decoded[index] = (decoded[index].toInt() xor key[index % key.size].toInt()).toByte() }
        val payload = json.parseToJsonElement(String(decoded, StandardCharsets.UTF_8)) as? JsonObject
            ?: error("ZokoAnime returned an invalid playback payload")
        val streamUrl = payload.string("src") ?: error("ZokoAnime returned no stream")
        val headers = mediaHeaders(ZOKO_REFERER)
        val subtitles = (payload["subtitles"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val track = element as? JsonObject ?: return@mapIndexedNotNull null
            track.toSubtitle(index, server.key, headers)
        }
        return ProviderMedia(streamUrl, headers, subtitles)
    }

    private suspend fun resolveMegaPlay(server: HiAnimeServer, watchUrl: String): ProviderMedia {
        val embedHtml = api.text(server.url, embedHeaders(watchUrl))
        val dataId = Jsoup.parse(embedHtml, server.url).selectFirst("#megaplay-player[data-id], [data-id]")
            ?.attr("data-id")
            ?.trim()
            ?.ifBlank { null }
            ?: error("MegaPlay returned no playback ID")
        val apiUrl = "$MEGAPLAY_BASE/stream/getSources".toHttpUrl().newBuilder()
            .addQueryParameter("id", dataId)
            .build()
            .toString()
        val response = api.text(
            apiUrl,
            sourceHeaders.newBuilder()
                .set("Referer", "$MEGAPLAY_BASE/")
                .set("X-Requested-With", "XMLHttpRequest")
                .build(),
        )
        val payload = json.parseToJsonElement(response) as? JsonObject
            ?: error("MegaPlay returned an invalid playback payload")
        val streamUrl = payload["sources"].findStreamUrl()
            ?: payload.string("file")
            ?: error("MegaPlay returned no stream")
        val headers = mediaHeaders("$MEGAPLAY_BASE/")
        val subtitles = (payload["tracks"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val track = element as? JsonObject ?: return@mapIndexedNotNull null
            val kind = track.string("kind")
            if (kind != null && kind !in setOf("captions", "subtitles")) return@mapIndexedNotNull null
            track.toSubtitle(index, server.key, headers)
        }
        return ProviderMedia(streamUrl, headers, subtitles)
    }

    private fun JsonObject.toSubtitle(
        index: Int,
        providerKey: String,
        headers: Map<String, String>,
    ): VideoSubtitle? {
        val url = string("src") ?: string("file") ?: return null
        val label = string("label") ?: string("lang") ?: "Subtitle ${index + 1}"
        val language = string("lang") ?: string("srclang") ?: string("language")
        return VideoSubtitle(
            request = VideoRequest(url, headers),
            label = label,
            language = language,
            mimeType = url.subtitleMimeType(),
            key = "$providerKey:subtitle:$index:${language.orEmpty()}",
            isDefault = (this["default"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun JsonElement?.findStreamUrl(): String? = when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf { it.startsWith("http") }
        is JsonObject -> string("file") ?: string("src") ?: values.firstNotNullOfOrNull { it.findStreamUrl() }
        is JsonArray -> firstNotNullOfOrNull { it.findStreamUrl() }
        else -> null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.ifBlank { null }

    private fun embedHeaders(referer: String): Headers = sourceHeaders.newBuilder()
        .set("Referer", referer)
        .build()

    private fun mediaHeaders(referer: String): Map<String, String> = buildMap {
        put("Referer", referer)
        sourceHeaders["User-Agent"]?.let { put("User-Agent", it) }
    }

    private fun String.subtitleMimeType(): String? = when (substringBefore('?').substringAfterLast('.').lowercase()) {
        "vtt" -> "text/vtt"
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        else -> null
    }

    private fun String.toHttpUrl(baseUrl: String) = when {
        startsWith("http://") || startsWith("https://") -> toHttpUrl()
        else -> "$baseUrl/${removePrefix("/")}".toHttpUrl()
    }

    @Synchronized
    private fun getCached(key: CacheKey): HiAnimeResolvedPlayback? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.createdAt > CACHE_TTL_MILLIS) {
            cache.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    private fun putCached(key: CacheKey, value: HiAnimeResolvedPlayback) {
        if (cache.size >= MAX_CACHE_ENTRIES && key !in cache) cache.remove(cache.keys.first())
        cache[key] = CacheEntry(System.currentTimeMillis(), value)
    }

    private data class ProviderMedia(
        val streamUrl: String,
        val headers: Map<String, String>,
        val subtitles: List<VideoSubtitle>,
    )

    private data class CacheKey(val chapterUrl: String, val dubKey: String?, val sourceKey: String?)
    private data class CacheEntry(val createdAt: Long, val value: HiAnimeResolvedPlayback)

    private companion object {
        const val ZOKO_KEY = "otaku-embed-v1"
        const val ZOKO_REFERER = "https://zokoanime.video/"
        const val MEGAPLAY_BASE = "https://megaplay.buzz"
        const val CACHE_TTL_MILLIS = 5 * 60 * 1_000L
        const val MAX_CACHE_ENTRIES = 8
        val ZOKO_PAYLOAD_REGEX = Regex("window\\.__P\\s*=\\s*[\"']([^\"']+)")
    }
}
