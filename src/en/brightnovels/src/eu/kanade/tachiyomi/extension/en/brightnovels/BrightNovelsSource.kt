package eu.kanade.tachiyomi.extension.en.brightnovels

import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.source.entry.ChapterWebViewSource
import eu.kanade.tachiyomi.source.entry.EmptyChapterListSource
import eu.kanade.tachiyomi.source.entry.EntryFilterList
import eu.kanade.tachiyomi.source.entry.EntryHttpSource
import eu.kanade.tachiyomi.source.entry.EntryMedia
import eu.kanade.tachiyomi.source.entry.EntryPageResult
import eu.kanade.tachiyomi.source.entry.EntryRefresh
import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.PlaybackSelection
import eu.kanade.tachiyomi.source.entry.SEntry
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import eu.kanade.tachiyomi.source.entry.SourceMetadata
import okhttp3.OkHttpClient

// Some listed series have no chapters yet; the site answers those with an empty chapter list.
internal class BrightNovelsSource : EntryHttpSource(), ChapterWebViewSource, SourceMetadata, EmptyChapterListSource {
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
        require(page >= 1) { "page must be positive" }
        val trimmed = query.trim()
        val params = filters.toBrightNovelsParams()
        // A plain query keeps the quick search's title matches; with filters, the catalogue searches and pages.
        if (trimmed.isNotEmpty() && !params.hasBrightNovelsConstraints()) {
            if (page > 1) return EntryPageResult(emptyList(), false)
            return EntryPageResult(api.search(trimmed).map(BrightNovelsSeriesItem::toSEntry), false)
        }
        val result = api.catalogue(page, if (trimmed.isEmpty()) params else params + ("search" to trimmed))
        return EntryPageResult(
            items = result.data.map(BrightNovelsSeriesItem::toSEntry),
            hasNextPage = result.currentPage < result.lastPage,
        )
    }

    override suspend fun refresh(
        entry: SEntry,
        existingChapters: List<SEntryChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): EntryRefresh {
        val key = entry.url.toBrightNovelsEntryKey()
            ?: error("Invalid Bright Novels entry: ${entry.url}")
        // Details and chapters are separate requests, so only the requested part is fetched.
        return EntryRefresh(
            entry = if (fetchDetails) api.seriesDetail(key.slug).applyTo(entry) else entry,
            chapters = if (fetchChapters) api.allChapters(key.slug).toSEntryChapters(key.slug) else existingChapters,
        )
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
