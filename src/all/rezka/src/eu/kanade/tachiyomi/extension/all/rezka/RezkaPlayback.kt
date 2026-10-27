package eu.kanade.tachiyomi.extension.all.rezka

import eu.kanade.tachiyomi.source.entry.PlaybackDescriptor
import eu.kanade.tachiyomi.source.entry.PlaybackSelection
import eu.kanade.tachiyomi.source.entry.VideoPlaybackOption
import eu.kanade.tachiyomi.source.entry.VideoRequest
import eu.kanade.tachiyomi.source.entry.VideoStream
import eu.kanade.tachiyomi.source.entry.VideoStreamType
import eu.kanade.tachiyomi.source.entry.VideoSubtitle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document
import java.io.IOException

internal enum class PlayerKind {
    SERIES,
    MOVIE,
}

/**
 * The player the title page starts with: `initCDNSeriesEvents(post, translator, season, episode, …, {payload})` or
 * `initCDNMoviesEvents(post, translator, camrip, ads, director, …, {payload})`.
 */
internal data class PlayerInit(
    val kind: PlayerKind,
    val postId: Int,
    val translatorId: Int,
    val seasonId: Int?,
    val episodeId: Int?,
    val payload: JsonObject?,
)

/** A voiceover, with the flags the site sends back when asking for a film in it. */
internal data class RezkaTranslator(
    val id: Int,
    val label: String,
    val camrip: String,
    val ads: String,
    val director: String,
)

/**
 * The site lists every quality as `[label]hls-link or mp4-link`. Each kind of link is a server, so Katari can fall
 * back from one to the other.
 */
internal enum class RezkaServer(val key: String, val label: String, val type: VideoStreamType) {
    HLS("hls", "HLS", VideoStreamType.HLS),
    MP4("mp4", "MP4", VideoStreamType.PROGRESSIVE),
    ;

    companion object {
        fun of(link: String): RezkaServer = if (".m3u8" in link) HLS else MP4
    }
}

private data class RezkaQuality(
    val label: String,
    val height: Int?,
    val links: List<String>,
    val premium: Boolean,
)

private val PLAYER_INIT_REGEX = Regex("""initCDN(Series|Movies)Events\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""")
private val QUALITY_ENTRY_REGEX = Regex("""\[(.*?)](.*?)(?=,\[|$)""", RegexOption.DOT_MATCHES_ALL)
private val QUALITY_HEIGHT_REGEX = Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE)
private val QUALITY_K_REGEX = Regex("""\b([248])\s*K\b""", RegexOption.IGNORE_CASE)
private val HTML_TAG_REGEX = Regex("<[^>]*>")

// Marks the qualities only Premium accounts get; for everyone else they all play the same short advert.
private const val PREMIUM_QUALITY_MARKER = "pjs-prem-quality"

internal fun hasPlayer(html: String): Boolean = PLAYER_INIT_REGEX.containsMatchIn(html)

internal fun parsePlayerInit(html: String, json: Json): PlayerInit? {
    val match = PLAYER_INIT_REGEX.find(html) ?: return null
    val kind = if (match.groupValues[1] == "Series") PlayerKind.SERIES else PlayerKind.MOVIE
    val payload = jsonObjectAfter(html, match.range.last + 1)
        ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    return PlayerInit(
        kind = kind,
        postId = match.groupValues[2].toInt(),
        translatorId = match.groupValues[3].toInt(),
        seasonId = match.groupValues[4].toInt().takeIf { kind == PlayerKind.SERIES },
        episodeId = match.groupValues[5].toInt().takeIf { kind == PlayerKind.SERIES },
        payload = payload,
    )
}

/** The JSON object argument of the call starting at [start], read up to its matching brace. */
private fun jsonObjectAfter(text: String, start: Int): String? {
    val open = text.indexOf('{', start).takeIf { it >= 0 } ?: return null
    if (')' in text.substring(start, open)) return null

    var depth = 0
    var inString = false
    var escaped = false
    for (index in open until text.length) {
        val char = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(open, index + 1)
            }
        }
    }
    return null
}

internal fun parseTranslators(document: Document): List<RezkaTranslator> {
    return document.select(".b-translator__item[data-translator_id], .b-translator__items[data-translator_id]")
        .mapNotNull { item ->
            val id = item.attr("data-translator_id").trim().toIntOrNull() ?: return@mapNotNull null
            val label = item.text().trim()
                .ifBlank { item.attr("title").trim() }
                .ifBlank { return@mapNotNull null }
            RezkaTranslator(
                id = id,
                label = label,
                camrip = item.attr("data-camrip").trim().ifBlank { "0" },
                ads = item.attr("data-ads").trim().ifBlank { "0" },
                director = item.attr("data-director").trim().ifBlank { "0" },
            )
        }
        .distinctBy(RezkaTranslator::id)
}

internal fun translatorOptions(translators: List<RezkaTranslator>, init: PlayerInit): List<VideoPlaybackOption> {
    if (translators.isEmpty()) {
        return listOf(VideoPlaybackOption(key = init.translatorId.toString(), label = "Default"))
    }
    return translators.map { VideoPlaybackOption(key = it.id.toString(), label = it.label) }
}

/** Reads the answer of `/ajax/get_cdn_series/`, which reports refusals such as Premium-only voiceovers in `message`. */
internal fun parseAjaxPayload(body: String, json: Json): JsonObject {
    val payload = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw IOException("Rezka returned an unexpected player response")
    val success = payload["success"]?.jsonPrimitive?.contentOrNull
    if (success == "false") {
        val message = payload.stringFieldOrNull("message")
        throw IOException(message ?: "Rezka could not load this voiceover")
    }
    return payload
}

internal fun buildPlaybackDescriptor(
    payload: JsonObject,
    dubs: List<VideoPlaybackOption>,
    dubKey: String,
    requestedServerKey: String?,
    mediaHeaders: Map<String, String>,
): PlaybackDescriptor {
    val allQualities = (payload.stringFieldOrNull("streams") ?: payload.stringFieldOrNull("url"))
        ?.let(::parseQualities)
        .orEmpty()
    val qualities = allQualities.filterNot(RezkaQuality::premium)
    if (qualities.isEmpty()) {
        if (allQualities.isNotEmpty()) {
            throw IOException("Rezka offers this voiceover only to Premium accounts")
        }
        throw IOException("Rezka returned no video for this voiceover")
    }

    val offeredServers = qualities
        .flatMap { quality -> quality.links.map(RezkaServer::of) }
        .distinct()
    val server = offeredServers.firstOrNull { it.key == requestedServerKey } ?: offeredServers.first()
    val subtitles = parseSubtitles(payload, mediaHeaders)

    val streams = qualities.mapNotNull { quality ->
        val link = quality.links.firstOrNull { RezkaServer.of(it) == server } ?: return@mapNotNull null
        VideoStream(
            request = VideoRequest(url = link, headers = mediaHeaders),
            label = quality.label,
            type = server.type,
            key = quality.label,
            height = quality.height,
            subtitles = subtitles,
        )
    }

    val listsServers = offeredServers.size > 1
    return PlaybackDescriptor(
        selection = PlaybackSelection(
            dubKey = dubKey,
            serverKey = server.key.takeIf { listsServers },
        ),
        dubs = dubs,
        servers = if (listsServers) {
            offeredServers.map { VideoPlaybackOption(key = it.key, label = it.label) }
        } else {
            emptyList()
        },
        streams = streams,
    )
}

private fun parseQualities(streams: String): List<RezkaQuality> {
    return QUALITY_ENTRY_REGEX.findAll(streams)
        .mapNotNull { match ->
            val rawLabel = match.groupValues[1]
            val links = match.groupValues[2].split(" or ")
                .map(String::trim)
                .filter { it.startsWith("http") }
                .takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val label = rawLabel.replace(HTML_TAG_REGEX, "").trim().ifBlank { return@mapNotNull null }
            RezkaQuality(
                label = label,
                height = qualityHeight(label),
                links = links,
                premium = PREMIUM_QUALITY_MARKER in rawLabel,
            )
        }
        .toList()
}

private fun qualityHeight(label: String): Int? {
    QUALITY_HEIGHT_REGEX.find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return when (QUALITY_K_REGEX.find(label)?.groupValues?.get(1)) {
        "2" -> 1440
        "4" -> 2160
        "8" -> 4320
        else -> null
    }
}

private fun parseSubtitles(payload: JsonObject, headers: Map<String, String>): List<VideoSubtitle> {
    val tracks = payload.stringFieldOrNull("subtitle") ?: return emptyList()
    val languages = payload.jsonObjectFieldOrNull("subtitle_lns")
        ?.mapNotNull { (label, value) ->
            val language = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()?.trim()
            label.trim().takeIf(String::isNotEmpty)?.let { it to language.orEmpty() }
        }
        ?.toMap()
        .orEmpty()
    val defaultLanguage = payload.stringFieldOrNull("subtitle_def")

    return QUALITY_ENTRY_REGEX.findAll(tracks)
        .mapNotNull { match ->
            val label = match.groupValues[1].trim().ifBlank { return@mapNotNull null }
            val url = match.groupValues[2].trim().ifBlank { return@mapNotNull null }
            val language = languages[label]?.ifBlank { null }
            VideoSubtitle(
                request = VideoRequest(url = url, headers = headers),
                label = label,
                language = language,
                mimeType = when {
                    url.endsWith(".vtt", ignoreCase = true) -> "text/vtt"
                    url.endsWith(".srt", ignoreCase = true) -> "application/x-subrip"
                    else -> null
                },
                key = listOf(label, language).joinToString("|"),
                isDefault = defaultLanguage != null && language == defaultLanguage,
            )
        }
        .toList()
}
