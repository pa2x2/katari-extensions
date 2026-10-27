package eu.kanade.tachiyomi.extension.en.hianime

import eu.kanade.tachiyomi.source.entry.PlaybackMarker
import eu.kanade.tachiyomi.source.entry.PlaybackMarkerKind
import eu.kanade.tachiyomi.source.entry.VideoRequest
import eu.kanade.tachiyomi.source.entry.VideoSubtitle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class HiAnimeExtractedStream(
    val streamUrl: String,
    val headers: Map<String, String>,
    val subtitles: List<VideoSubtitle>,
    val markers: List<PlaybackMarker>,
)

/** Extracts the HLS playlist, subtitles and skip times from the embedded players HiAnime lists as servers. */
internal class HiAnimeStreamExtractor(
    private val api: HiAnimeApi,
    private val sourceHeaders: Headers,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun supports(server: HiAnimeServer): Boolean = server.host().let { host ->
        SUPPORTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    suspend fun extract(server: HiAnimeServer, watchUrl: String): HiAnimeExtractedStream {
        val host = server.host()
        return when {
            host == ZOKO_HOST || host.endsWith(".$ZOKO_HOST") -> extractZoko(server, watchUrl)
            host == MEGAPLAY_HOST || host.endsWith(".$MEGAPLAY_HOST") -> extractMegaPlay(server, watchUrl)
            else -> error("Unsupported HiAnime server: $host")
        }
    }

    private suspend fun extractZoko(server: HiAnimeServer, watchUrl: String): HiAnimeExtractedStream {
        val html = api.text(server.url, embedHeaders(watchUrl))
        val encoded = ZOKO_PAYLOAD_REGEX.find(html)?.groupValues?.get(1)
            ?: error("ZokoAnime returned no playback payload")
        val decoded = Base64.getDecoder().decode(encoded)
        val key = ZOKO_KEY.toByteArray(StandardCharsets.UTF_8)
        decoded.indices.forEach { index ->
            decoded[index] = (decoded[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
        val payload = json.parseToJsonElement(String(decoded, StandardCharsets.UTF_8)) as? JsonObject
            ?: error("ZokoAnime returned an invalid playback payload")
        val streamUrl = payload.string("src") ?: error("ZokoAnime returned no stream")
        val headers = mediaHeaders("https://$ZOKO_HOST/")
        val subtitles = (payload["subtitles"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            (element as? JsonObject)?.toSubtitle(index, server.key, headers)
        }
        val skip = payload["skip"] as? JsonObject
        val markers = listOfNotNull(
            skip?.get("intro").toMarker(PlaybackMarkerKind.INTRO),
            skip?.get("outro").toMarker(PlaybackMarkerKind.OUTRO),
        )
        return HiAnimeExtractedStream(streamUrl, headers, subtitles, markers)
    }

    private suspend fun extractMegaPlay(server: HiAnimeServer, watchUrl: String): HiAnimeExtractedStream {
        val embedHtml = api.text(server.url, embedHeaders(watchUrl))
        val dataId = Jsoup.parse(embedHtml, server.url).selectFirst("#megaplay-player[data-id], [data-id]")
            ?.attr("data-id")
            ?.trim()
            ?.ifBlank { null }
            ?: error("MegaPlay returned no playback ID")
        // The embed page forwards its own `s` (CDN choice) to getSources, so do the same.
        val cdn = server.url.toHttpUrl().queryParameter("s")
        val apiUrl = "https://$MEGAPLAY_HOST/stream/getSources".toHttpUrl().newBuilder()
            .addQueryParameter("id", dataId)
            .apply { cdn?.let { addQueryParameter("s", it) } }
            .build()
            .toString()
        val response = api.text(
            apiUrl,
            sourceHeaders.newBuilder()
                .set("Referer", server.url)
                .set("X-Requested-With", "XMLHttpRequest")
                .build(),
        )
        val payload = json.parseToJsonElement(response) as? JsonObject
            ?: error("MegaPlay returned an invalid playback payload")
        val streamUrl = payload["sources"].findStreamUrl()
            ?: payload.string("file")
            ?: payload.string("enc")?.let { json.parseToJsonElement(decryptMegaPlay(it)).findStreamUrl() }
            ?: error("MegaPlay returned no stream")
        val headers = mediaHeaders("https://$MEGAPLAY_HOST/")
        val subtitles = (payload["tracks"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val track = element as? JsonObject ?: return@mapIndexedNotNull null
            val kind = track.string("kind")
            if (kind != null && kind !in SUBTITLE_KINDS) return@mapIndexedNotNull null
            track.toSubtitle(index, server.key, headers)
        }
        val markers = listOfNotNull(
            payload["intro"].toMarker(PlaybackMarkerKind.INTRO),
            payload["outro"].toMarker(PlaybackMarkerKind.OUTRO),
        )
        return HiAnimeExtractedStream(streamUrl, headers, subtitles, markers)
    }

    /** MegaPlay's `enc` is URL-safe Base64 of AES-CBC ciphertext, with the key and IV its player script uses. */
    private fun decryptMegaPlay(encoded: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(MEGAPLAY_AES_KEY.paddedBytes(32), "AES"),
            IvParameterSpec(MEGAPLAY_AES_IV.paddedBytes(16)),
        )
        return String(cipher.doFinal(Base64.getUrlDecoder().decode(encoded.trimEnd('='))), StandardCharsets.UTF_8)
    }

    /** A `{start, end}` span in seconds; providers send zeros or nulls when they don't know it. */
    private fun JsonElement?.toMarker(kind: PlaybackMarkerKind): PlaybackMarker? {
        val span = this as? JsonObject ?: return null
        val start = (span["start"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val end = (span["end"] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (start < 0 || end <= start) return null
        return PlaybackMarker(startMs = (start * 1_000).toLong(), endMs = (end * 1_000).toLong(), kind = kind)
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

    private fun String.paddedBytes(size: Int): ByteArray = toByteArray(StandardCharsets.UTF_8).copyOf(size)

    private fun HiAnimeServer.host(): String = url.toHttpUrl().host

    private companion object {
        const val ZOKO_HOST = "zokoanime.video"

        /**
         * XOR key for the embed's `window.__P` payload. If ZokoAnime fails with "invalid playback payload", get an embed
         * URL by Base64-decoding a ZokoAnime `data-hash` from `/api/theme/episode/servers?episodeId=…` (e.g.
         * `https://zokoanime.video/stream/mal/20/1/sub`). The embed page loads `/player.js`, which imports
         * `/core/obfuscate.js`. Copy `OBF_KEY` from there. If `deobfuscate` changed beyond the key (it is now
         * `JSON.parse(xor(atob(blob)))`), update [extractZoko] to match.
         */
        const val ZOKO_KEY = "otaku-embed-v1"
        const val MEGAPLAY_HOST = "megaplay.buzz"

        /**
         * AES-CBC key and IV for `getSources`' `enc` field. If HD-1 fails while decrypting, open an HD-1 embed URL (the
         * Base64-decoded `data-hash` of an HD-1 server, e.g. `https://megaplay.buzz/stream/s-2/12352/sub?s=tcdn`) and
         * fetch the `lib/newclient.min.js` it loads. The key and IV are the defaults there:
         * `pick(["trustAesKey","TRUST_AES_KEY"], "…")` and `pick(["trustAesIv","TRUST_AES_IV"], "…")`. If those names are
         * gone, search the script for `AES`/`CBC` near `enc`. Note that [paddedBytes] zero-pads them to 32 and 16 bytes,
         * so check that the script still pads that way.
         */
        const val MEGAPLAY_AES_KEY = "i?LMTAx0Q6,:}50U"
        const val MEGAPLAY_AES_IV = "W0;27ToaUpl_P%'c"
        val SUPPORTED_HOSTS = listOf(ZOKO_HOST, MEGAPLAY_HOST)
        val SUBTITLE_KINDS = setOf("captions", "subtitles")
        val ZOKO_PAYLOAD_REGEX = Regex("window\\.__P\\s*=\\s*[\"']([^\"']+)")
    }
}
