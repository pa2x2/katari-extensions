package eu.kanade.tachiyomi.extension.en.hianime

import eu.kanade.tachiyomi.source.entry.PlaybackDescriptor
import eu.kanade.tachiyomi.source.entry.PlaybackSelection
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import eu.kanade.tachiyomi.source.entry.VideoPlaybackOption
import eu.kanade.tachiyomi.source.entry.VideoRequest
import eu.kanade.tachiyomi.source.entry.VideoStream
import eu.kanade.tachiyomi.source.entry.VideoStreamType
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Lists an episode's dubs and servers cheaply, and extracts a server's playlist only when Katari is about to play or
 * download it. Each server carries one HLS stream, so a stream's key names its dub and server.
 */
internal class HiAnimePlaybackResolver(
    private val api: HiAnimeApi,
    private val extractor: HiAnimeStreamExtractor,
    private val baseUrl: String,
) {
    private val serverCache = linkedMapOf<String, CachedServers>()

    suspend fun describe(chapter: SEntryChapter, selection: PlaybackSelection): PlaybackDescriptor {
        val servers = servers(chapter.watchUrl())
        val dub = selection.dubKey?.takeIf { key -> servers.any { it.type == key } }
            ?: servers.firstOrNull { it.type == DEFAULT_DUB }?.type
            ?: servers.first().type
        val dubServers = servers.filter { it.type == dub }
        // 2.x saved the server as the source quality, so honor that until a server key is saved.
        val requestedServer = selection.serverKey ?: selection.sourceQualityKey
        val server = dubServers.firstOrNull { it.key == requestedServer } ?: dubServers.first()
        return PlaybackDescriptor(
            selection = PlaybackSelection(dubKey = dub, serverKey = server.key),
            dubs = servers.distinctBy(HiAnimeServer::type).map {
                VideoPlaybackOption(key = it.type, label = it.type.uppercase())
            },
            servers = dubServers.map {
                VideoPlaybackOption(key = it.key, label = it.name, description = it.url.toHttpUrl().host)
            },
            streams = listOf(
                VideoStream(
                    request = null,
                    label = server.name,
                    type = VideoStreamType.HLS,
                    key = server.streamKey(),
                ),
            ),
        )
    }

    suspend fun resolve(chapter: SEntryChapter, stream: VideoStream): VideoStream {
        val watchUrl = chapter.watchUrl()
        val server = servers(watchUrl).firstOrNull { it.streamKey() == stream.key }
            ?: error("HiAnime no longer lists this server")
        val extracted = extractor.extract(server, watchUrl.toString())
        return stream.copy(
            request = VideoRequest(extracted.streamUrl, extracted.headers),
            subtitles = extracted.subtitles,
            markers = extracted.markers,
        )
    }

    private suspend fun servers(watchUrl: HttpUrl): List<HiAnimeServer> {
        val episodeId = watchUrl.queryParameter("ep") ?: error("HiAnime chapter has no episode ID")
        cachedServers(episodeId)?.let { return it }
        val listed = HiAnimeParser.parseServers(api.serverDocument(episodeId))
        check(listed.isNotEmpty()) { "HiAnime returned no playback servers" }
        val playable = listed.filter(extractor::supports).distinctBy { it.type to it.key }
        check(playable.isNotEmpty()) {
            "HiAnime lists only unsupported servers: ${listed.joinToString { it.name }}"
        }
        putCachedServers(episodeId, playable)
        return playable
    }

    private fun SEntryChapter.watchUrl(): HttpUrl = when {
        url.startsWith("http://") || url.startsWith("https://") -> url.toHttpUrl()
        else -> "$baseUrl/${url.removePrefix("/")}".toHttpUrl()
    }

    private fun HiAnimeServer.streamKey(): String = "$type:$key"

    @Synchronized
    private fun cachedServers(episodeId: String): List<HiAnimeServer>? {
        val entry = serverCache[episodeId] ?: return null
        if (System.currentTimeMillis() - entry.createdAt > CACHE_TTL_MILLIS) {
            serverCache.remove(episodeId)
            return null
        }
        return entry.servers
    }

    @Synchronized
    private fun putCachedServers(episodeId: String, servers: List<HiAnimeServer>) {
        if (serverCache.size >= MAX_CACHE_ENTRIES && episodeId !in serverCache) {
            serverCache.remove(serverCache.keys.first())
        }
        serverCache[episodeId] = CachedServers(System.currentTimeMillis(), servers)
    }

    private class CachedServers(val createdAt: Long, val servers: List<HiAnimeServer>)

    private companion object {
        const val DEFAULT_DUB = "sub"
        const val CACHE_TTL_MILLIS = 5 * 60 * 1_000L
        const val MAX_CACHE_ENTRIES = 16
    }
}
