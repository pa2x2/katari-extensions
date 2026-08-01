package eu.kanade.tachiyomi.extension.en.readnovelfull

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

internal class ReadNovelFullSource : EntryHttpSource(), ChapterWebViewSource, SourceMetadata {
    override val id: Long = SOURCE_ID
    override val name: String = "ReadNovelFull"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val supportedEntryTypes: Set<EntryType> = setOf(EntryType.BOOK)
    override val baseUrl: String = BASE_URL

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(ReadNovelFullThrottleInterceptor())
        .build()

    private val api by lazy { ReadNovelFullApi(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", BROWSER_USER_AGENT)
        .set("Accept-Language", "en-US,en;q=0.9")

    override fun getChapterUrl(chapter: SEntryChapter): String = baseUrl + chapter.url

    override suspend fun getPopularContent(page: Int): EntryPageResult<SEntry> = catalogue(
        page = page,
        path = "/novel-list/most-popular-novel",
    )

    override suspend fun getLatestUpdates(page: Int): EntryPageResult<SEntry> = catalogue(
        page = page,
        path = "/novel-list/latest-release-novel",
    )

    override suspend fun getSearchContent(
        page: Int,
        query: String,
        filters: EntryFilterList,
    ): EntryPageResult<SEntry> {
        require(page == 1) { "ReadNovelFull search does not paginate" }
        return ReadNovelFullParser.parseListing(api.document(api.searchPath(query))).let {
            EntryPageResult(it.entries.map(ReadNovelFullEntry::toSEntry), hasNextPage = false)
        }
    }

    override suspend fun getContentDetails(entry: SEntry): SEntry = ReadNovelFullParser
        .parseDetails(api.document(entry.url))
        .applyTo(entry)

    override suspend fun getChapterList(entry: SEntry): List<SEntryChapter> {
        val novelId = entry.readNovelFullNovelId() ?: ReadNovelFullParser
            .parseDetails(api.document(entry.url))
            .novelId
            ?: error("ReadNovelFull returned no novel ID")
        return ReadNovelFullParser.parseChapterArchive(api.chapterArchive(novelId))
            .mapIndexed { index, chapter -> chapter.toSEntryChapter(index) }
    }

    override suspend fun getMedia(chapter: SEntryChapter, selection: PlaybackSelection): EntryMedia {
        val resourceHeaders = buildMap {
            headers["User-Agent"]?.let { put("User-Agent", it) }
            put("Referer", getChapterUrl(chapter))
        }
        return ReadNovelFullParser.parseChapter(api.document(chapter.url))
            .toBookMedia(chapter.url, chapter.name, resourceHeaders)
    }

    private suspend fun catalogue(page: Int, path: String): EntryPageResult<SEntry> {
        require(page >= 1) { "page must be positive" }
        return ReadNovelFullParser.parseListing(api.document(api.pagePath(path, page))).let {
            EntryPageResult(it.entries.map(ReadNovelFullEntry::toSEntry), it.hasNextPage)
        }
    }

    private companion object {
        const val SOURCE_ID = 500000009L
        const val BASE_URL = "https://readnovelfull.com"
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
    }
}
