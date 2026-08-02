package eu.kanade.tachiyomi.extension.en.novelfull

import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.source.entry.ChapterWebViewSource
import eu.kanade.tachiyomi.source.entry.EntryFilterList
import eu.kanade.tachiyomi.source.entry.EntryHttpSource
import eu.kanade.tachiyomi.source.entry.EntryMedia
import eu.kanade.tachiyomi.source.entry.EntryPageResult
import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.PlaybackSelection
import eu.kanade.tachiyomi.source.entry.SEntry
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import eu.kanade.tachiyomi.source.entry.SourceMetadata
import okhttp3.Headers
import okhttp3.OkHttpClient

internal class NovelFullSource : EntryHttpSource(), ChapterWebViewSource, SourceMetadata {
    override val id: Long = BuildConfig.SOURCE_ID_NOVELFULL
    override val name: String = "NovelFull"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val supportedEntryTypes: Set<EntryType> = setOf(EntryType.BOOK)
    override val baseUrl: String = BASE_URL

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(NovelFullThrottleInterceptor())
        .build()

    private val api by lazy { NovelFullApi(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", BROWSER_USER_AGENT)
        .set("Accept-Language", "en-US,en;q=0.9")

    override fun getChapterUrl(chapter: SEntryChapter): String = baseUrl + chapter.url

    override fun getFilterList(): EntryFilterList = novelFullFilterList()

    override suspend fun getPopularContent(page: Int): EntryPageResult<SEntry> = catalogue(page, "/most-popular")

    override suspend fun getLatestUpdates(page: Int): EntryPageResult<SEntry> = catalogue(page, "/latest-release-novel")

    override suspend fun getSearchContent(
        page: Int,
        query: String,
        filters: EntryFilterList,
    ): EntryPageResult<SEntry> {
        val selection = filters.toNovelFullSearchSelection(query)
        return if (selection.query != null) {
            catalogue(page, api.searchPath(selection.query))
        } else {
            catalogue(page, selection.browsePath)
        }
    }

    override suspend fun getContentDetails(entry: SEntry): SEntry =
        NovelFullParser.parseDetails(api.document(entry.url)).applyTo(entry)

    override suspend fun getChapterList(entry: SEntry): List<SEntryChapter> {
        val novelId = entry.novelFullNovelId() ?: NovelFullParser
            .parseDetails(api.document(entry.url))
            .novelId
            ?: error("NovelFull returned no novel ID")
        return NovelFullParser.parseChapterArchive(api.chapterArchive(novelId))
            .map(NovelFullChapter::toSEntryChapter)
    }

    override suspend fun getMedia(chapter: SEntryChapter, selection: PlaybackSelection): EntryMedia {
        val content = NovelFullParser.parseChapter(api.document(chapter.url))
        val resourceHeaders = buildMap {
            headers["User-Agent"]?.let { put("User-Agent", it) }
            put("Referer", getChapterUrl(chapter))
        }
        return content.toBookMedia(chapter.url, chapter.name, resourceHeaders)
    }

    private suspend fun catalogue(page: Int, path: String): EntryPageResult<SEntry> {
        require(page >= 1) { "page must be positive" }
        val result = NovelFullParser.parseListing(api.document(api.pagePath(path, page)))
        return EntryPageResult(result.entries.map(NovelFullEntry::toSEntry), result.hasNextPage)
    }

    private companion object {
        const val BASE_URL = "https://novelfull.net"
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
    }
}
