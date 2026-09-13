package eu.kanade.tachiyomi.extension.en.brightnovels

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
import okhttp3.OkHttpClient

internal class BrightNovelsSource : EntryHttpSource(), ChapterWebViewSource, SourceMetadata {
    override val id: Long = BuildConfig.SOURCE_ID_BRIGHTNOVELS
    override val name: String = "Bright Novels"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val supportedEntryTypes: Set<EntryType> = setOf(EntryType.BOOK)
    override val baseUrl: String = BRIGHTNOVELS_URL

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(BrightNovelsThrottleInterceptor())
        .build()

    private val api by lazy { BrightNovelsApi(client, headers) }

    override fun getContentUrl(entry: SEntry): String = baseUrl + entry.url

    override fun getChapterUrl(chapter: SEntryChapter): String = baseUrl + chapter.url

    override fun getFilterList(): EntryFilterList = brightNovelsFilterList()

    override suspend fun getPopularContent(page: Int): EntryPageResult<SEntry> {
        require(page >= 1) { "page must be positive" }
        // The default catalogue order is recency, so popular must request the trending sort explicitly.
        val result = api.catalogue(page, mapOf("sort" to "trending", "order" to "desc"))
        return EntryPageResult(
            items = result.data.map(BrightNovelsSeriesItem::toSEntry),
            hasNextPage = result.currentPage < result.lastPage,
        )
    }

    override suspend fun getLatestUpdates(page: Int): EntryPageResult<SEntry> {
        require(page >= 1) { "page must be positive" }
        val result = api.catalogue(page, mapOf("sort" to "updated_at", "order" to "desc"))
        return EntryPageResult(
            items = result.data.map(BrightNovelsSeriesItem::toSEntry),
            hasNextPage = result.currentPage < result.lastPage,
        )
    }

    override suspend fun getSearchContent(
        page: Int,
        query: String,
        filters: EntryFilterList,
    ): EntryPageResult<SEntry> {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            if (page > 1) return EntryPageResult(emptyList(), false)
            return EntryPageResult(api.search(trimmed).map(BrightNovelsSeriesItem::toSEntry), false)
        }
        require(page >= 1) { "page must be positive" }
        val result = api.catalogue(page, filters.toBrightNovelsParams())
        return EntryPageResult(
            items = result.data.map(BrightNovelsSeriesItem::toSEntry),
            hasNextPage = result.currentPage < result.lastPage,
        )
    }

    override suspend fun getContentDetails(entry: SEntry): SEntry {
        val key = entry.url.toBrightNovelsEntryKey()
            ?: error("Invalid Bright Novels entry: ${entry.url}")
        return api.seriesDetail(key.slug).applyTo(entry)
    }

    override suspend fun getChapterList(entry: SEntry): List<SEntryChapter> {
        val key = entry.url.toBrightNovelsEntryKey()
            ?: error("Invalid Bright Novels entry: ${entry.url}")
        return api.allChapters(key.slug).chapters
            .map { it.toSEntryChapter(key.slug) }
            .sortedBy(SEntryChapter::chapterNumber)
    }

    override suspend fun getMedia(chapter: SEntryChapter, selection: PlaybackSelection): EntryMedia {
        val key = chapter.url.toBrightNovelsChapterKey()
            ?: error("Invalid Bright Novels chapter: ${chapter.url}")
        val show = api.chapterShow(key.seriesSlug, key.chapterSlug)
        if (!show.unlocked) {
            val price = show.chapter.price.takeIf { it > 0 }?.let { " ($it points)" }.orEmpty()
            error("This chapter is premium-locked$price. Unlock it in WebView to read.")
        }
        return show.chapter.toBookMedia(
            chapterUrl = getChapterUrl(chapter),
            referer = "$baseUrl/",
            fallbackTitle = chapter.name.removePrefix(LOCKED_CHAPTER_PREFIX),
        )
    }
}
