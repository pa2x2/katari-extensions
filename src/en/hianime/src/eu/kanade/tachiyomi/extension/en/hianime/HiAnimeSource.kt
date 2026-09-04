package eu.kanade.tachiyomi.extension.en.hianime

import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.source.entry.ChapterWebViewSource
import eu.kanade.tachiyomi.source.entry.EmptyChapterListSource
import eu.kanade.tachiyomi.source.entry.EntryFilterList
import eu.kanade.tachiyomi.source.entry.EntryHttpSource
import eu.kanade.tachiyomi.source.entry.EntryMedia
import eu.kanade.tachiyomi.source.entry.EntryPageResult
import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.EntryUriType
import eu.kanade.tachiyomi.source.entry.PlaybackSelection
import eu.kanade.tachiyomi.source.entry.RelatedEntriesSource
import eu.kanade.tachiyomi.source.entry.ResolvableSource
import eu.kanade.tachiyomi.source.entry.SEntry
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import eu.kanade.tachiyomi.source.entry.SourceMetadata
import eu.kanade.tachiyomi.source.entry.SubtitleSource
import eu.kanade.tachiyomi.source.entry.VideoSubtitle
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

internal class HiAnimeSource :
    EntryHttpSource(),
    ChapterWebViewSource,
    SourceMetadata,
    SubtitleSource,
    RelatedEntriesSource,
    EmptyChapterListSource,
    ResolvableSource {

    override val id: Long = BuildConfig.SOURCE_ID_HIANIME
    override val name: String = "HiAnime"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val supportedEntryTypes: Set<EntryType> = setOf(EntryType.ANIME)
    override val baseUrl: String = BASE_URL

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(HiAnimeThrottleInterceptor())
        .build()

    private val api by lazy { HiAnimeApi(client, headers) }
    private val playbackResolver by lazy { HiAnimePlaybackResolver(api, headers, baseUrl) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", BROWSER_USER_AGENT)
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Referer", "$baseUrl/")

    override fun getFilterList(): EntryFilterList = hiAnimeFilterList()

    override fun getChapterUrl(chapter: SEntryChapter): String = absoluteUrl(chapter.url)

    override suspend fun getPopularContent(page: Int): EntryPageResult<SEntry> = catalogue("/most-popular", page)

    override suspend fun getLatestUpdates(page: Int): EntryPageResult<SEntry> = catalogue("/recently-updated", page)

    override suspend fun getSearchContent(
        page: Int,
        query: String,
        filters: EntryFilterList,
    ): EntryPageResult<SEntry> {
        require(page >= 1) { "page must be positive" }
        val url = "$baseUrl/filter".toHttpUrl().newBuilder().apply {
            filters.toHiAnimeSearchSelection(query).parameters.forEach { (name, value) ->
                addQueryParameter(name, value)
            }
            if (page > 1) addQueryParameter("page", page.toString())
        }.build().toString()
        return listing(url, page)
    }

    override suspend fun getContentDetails(entry: SEntry): SEntry =
        HiAnimeParser.parseDetails(api.document(entry.url)).applyTo(entry)

    override suspend fun getChapterList(entry: SEntry): List<SEntryChapter> {
        val animeId = entry.hiAnimeId() ?: error("HiAnime entry has no anime ID")
        return HiAnimeParser.parseEpisodes(api.episodeDocument(animeId)).map(HiAnimeChapter::toSEntryChapter)
    }

    override suspend fun getMedia(chapter: SEntryChapter, selection: PlaybackSelection): EntryMedia =
        EntryMedia.Playback(playbackResolver.resolve(chapter, selection).descriptor)

    override suspend fun getSubtitles(
        chapter: SEntryChapter,
        selection: PlaybackSelection,
    ): List<VideoSubtitle> = playbackResolver.resolve(chapter, selection).subtitles

    override suspend fun getRelatedEntries(entry: SEntry): List<SEntry> =
        HiAnimeParser.parseRelated(api.document(entry.url)).map(HiAnimeEntry::toSEntry)

    override fun getUriType(uri: String): EntryUriType {
        val url = uri.toHiAnimeUrl() ?: return EntryUriType.Unknown
        if (url.host !in HIANIME_HOSTS) return EntryUriType.Unknown
        val isDetailPath = DETAIL_PATH_REGEX.matches(url.encodedPath.removePrefix("/watch"))
        return when {
            url.encodedPath.startsWith("/watch/") && !url.queryParameter("ep").isNullOrBlank() -> EntryUriType.Chapter
            isDetailPath -> EntryUriType.Entry
            else -> EntryUriType.Unknown
        }
    }

    override suspend fun getEntry(uri: String): SEntry {
        val url = uri.toHiAnimeUrl() ?: error("Invalid HiAnime URL")
        require(url.host in HIANIME_HOSTS) { "URL does not belong to HiAnime" }
        val path = url.encodedPath.removePrefix("/watch")
        require(DETAIL_PATH_REGEX.matches(path)) { "URL does not identify a HiAnime entry" }
        val entry = SEntry.create().apply {
            this.url = path
            title = path.substringAfterLast('/').substringBeforeLast('-').replace('-', ' ')
            type = EntryType.ANIME
        }
        return getContentDetails(entry)
    }

    override suspend fun getChapter(uri: String): SEntryChapter {
        val url = uri.toHiAnimeUrl() ?: error("Invalid HiAnime URL")
        require(url.host in HIANIME_HOSTS) { "URL does not belong to HiAnime" }
        val animeId = url.encodedPath.hiAnimeId() ?: error("HiAnime URL has no anime ID")
        val episodeId = url.queryParameter("ep") ?: error("HiAnime URL has no episode ID")
        val chapter = HiAnimeParser.parseEpisodes(api.episodeDocument(animeId))
            .firstOrNull { it.id == episodeId }
            ?: error("HiAnime episode was not found")
        return chapter.toSEntryChapter()
    }

    private suspend fun catalogue(path: String, page: Int): EntryPageResult<SEntry> {
        require(page >= 1) { "page must be positive" }
        val url = "$baseUrl$path".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build().toString()
        return listing(url, page)
    }

    private suspend fun listing(url: String, page: Int): EntryPageResult<SEntry> {
        val (entries, hasNextPage) = HiAnimeParser.parseListing(api.document(url), page)
        return EntryPageResult(entries.map(HiAnimeEntry::toSEntry), hasNextPage)
    }

    private fun absoluteUrl(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
        else -> "$baseUrl/${pathOrUrl.removePrefix("/")}"
    }

    private fun String.toHiAnimeUrl(): HttpUrl? = when {
        startsWith("http://") || startsWith("https://") -> toHttpUrlOrNull()
        startsWith('/') -> "$baseUrl$this".toHttpUrlOrNull()
        else -> null
    }

    private companion object {
        const val BASE_URL = "https://hianime.at"
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
        val HIANIME_HOSTS = setOf("hianime.at", "www.hianime.at")
        val DETAIL_PATH_REGEX = Regex("/[^/?]+-\\d+")
    }
}
