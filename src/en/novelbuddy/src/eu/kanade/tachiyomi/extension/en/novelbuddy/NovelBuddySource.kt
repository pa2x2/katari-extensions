package eu.kanade.tachiyomi.extension.en.novelbuddy

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

internal class NovelBuddySource : EntryHttpSource(), ChapterWebViewSource, SourceMetadata {
    override val id: Long = SOURCE_ID
    override val name: String = "NovelBuddy"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val supportedEntryTypes: Set<EntryType> = setOf(EntryType.BOOK)
    override val baseUrl: String = "https://novelbuddy.me"

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(NovelBuddyThrottleInterceptor())
        .build()

    private val api by lazy { NovelBuddyApi(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", "$baseUrl/")

    override fun getContentUrl(entry: SEntry): String = entry.url.toNovelBuddyContentWebUrl(baseUrl)

    override fun getChapterUrl(chapter: SEntryChapter): String = chapter.url.toNovelBuddyChildWebUrl(baseUrl)

    override fun getFilterList(): EntryFilterList = novelBuddyFilterList()

    override suspend fun getPopularContent(page: Int): EntryPageResult<SEntry> = catalogue(
        page = page,
        selection = NovelBuddySearchSelection(sort = POPULAR_SORT),
    )

    override suspend fun getLatestUpdates(page: Int): EntryPageResult<SEntry> = catalogue(
        page = page,
        selection = NovelBuddySearchSelection(sort = LATEST_SORT),
    )

    override suspend fun getSearchContent(
        page: Int,
        query: String,
        filters: EntryFilterList,
    ): EntryPageResult<SEntry> = catalogue(page, filters.toNovelBuddySearchSelection(query))

    override suspend fun getContentDetails(entry: SEntry): SEntry {
        val key = entry.url.toNovelBuddyTitleKey()
            ?: error("Invalid NovelBuddy BOOK identity: ${entry.url}")
        return api.title(key.slug).toSEntry().also { it.initialized = true }
    }

    override suspend fun getChapterList(entry: SEntry): List<SEntryChapter> {
        val key = entry.url.toNovelBuddyTitleKey()
            ?: error("Invalid NovelBuddy BOOK identity: ${entry.url}")
        return api.chapters(key.id).map { it.toSEntryChapter(key.id, key.slug) }
    }

    override suspend fun getMedia(chapter: SEntryChapter, selection: PlaybackSelection): EntryMedia {
        val key = chapter.url.toNovelBuddyChapterKey()
            ?: error("Invalid NovelBuddy chapter identity: ${chapter.url}")
        val content = api.chapter(key.titleSlug, key.chapterSlug)
        require(content.id == key.chapterId) { "NovelBuddy returned a different chapter" }
        return content.toBookMedia(
            chapterUrl = getChapterUrl(chapter),
            requestHeaders = mapOf("Referer" to "$baseUrl/"),
        )
    }

    private suspend fun catalogue(
        page: Int,
        selection: NovelBuddySearchSelection,
    ): EntryPageResult<SEntry> {
        require(page >= 1) { "page must be positive" }
        val result = api.search(page, selection)
        return EntryPageResult(
            items = result.items.map(NovelBuddyTitle::toSEntry),
            hasNextPage = result.pagination.hasNext,
        )
    }

    private companion object {
        const val SOURCE_ID = 500000007L
    }
}
