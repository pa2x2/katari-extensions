package eu.kanade.tachiyomi.extension.all.gutenberg

import androidx.preference.ListPreference
import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.source.entry.*
import okhttp3.OkHttpClient

internal class GutenbergSource : EntryHttpSource(), SourceMetadata, ConfigurableSource,
    ChapterWebViewSource, RelatedEntriesSource, ResolvableSource {
    override val id: Long = BuildConfig.SOURCE_ID_GUTENBERG
    override val name: String = "Project Gutenberg"
    override val lang: String = "all"
    override val baseUrl: String = GUTENBERG_URL
    override val supportsLatest: Boolean = true
    override val supportedEntryTypes: Set<EntryType> = setOf(EntryType.BOOK)
    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addInterceptor(GutenbergThrottle()).build()
    }
    private val http by lazy { GutenbergHttp(client, headers) }

    override fun getFilterList(): EntryFilterList = gutenbergFilters(http)

    override suspend fun getPopularContent(page: Int): EntryPageResult<SEntry> =
        getSearchContent(page, "", EntryFilterList())

    override suspend fun getLatestUpdates(page: Int): EntryPageResult<SEntry> =
        getSearchContent(page, "", EntryFilterList(GutenbergSort().apply { state = 1 }))

    override suspend fun getSearchContent(page: Int, query: String, filters: EntryFilterList): EntryPageResult<SEntry> =
        http.document(gutenbergSearchUrl(page, query, filters)).gutenbergListing()

    private suspend fun book(url: String): GutenbergBook {
        val id = gutenbergId(url) ?: error("Invalid Project Gutenberg entry")
        return http.document("/ebooks/$id").gutenbergBook(id)
    }

    override suspend fun getContentDetails(entry: SEntry): SEntry = book(entry.url).entry

    override suspend fun getChapterList(entry: SEntry): List<SEntryChapter> {
        val book = book(entry.url)
        return listOf(SEntryChapter.create().apply {
            url = "${book.entry.url}#read"
            name = "Read book"
            chapterNumber = 1.0
            dateUpload = book.uploaded
        })
    }

    override suspend fun getMedia(chapter: SEntryChapter, selection: PlaybackSelection): EntryMedia {
        val book = book(chapter.url)
        val format = book.readableFormat(getSourcePreferences().getString("reading_format", "epub") ?: "epub")
        return when (format.mime) {
            "application/epub+zip" -> book.epubMedia(format)
            "text/plain" -> book.htmlMedia(http.plainTextDocument(format.url))
            else -> book.htmlMedia(http.document(format.url))
        }
    }

    override suspend fun getRelatedEntries(entry: SEntry): List<SEntry> =
        http.document("/ebooks/${gutenbergId(entry.url) ?: error("Invalid Project Gutenberg entry")}/also/")
            .gutenbergListing().items

    override fun getChapterUrl(chapter: SEntryChapter): String =
        "$baseUrl/ebooks/${gutenbergId(chapter.url) ?: error("Invalid Project Gutenberg entry") }"

    override fun getUriType(uri: String): EntryUriType = when {
        gutenbergId(uri) == null -> EntryUriType.Unknown
        uri.endsWith("#read") -> EntryUriType.Chapter
        else -> EntryUriType.Entry
    }

    override suspend fun getEntry(uri: String): SEntry? = gutenbergId(uri)?.let { book(uri).entry }
    override suspend fun getChapter(uri: String): SEntryChapter? =
        getEntry(uri)?.let { getChapterList(it).single() }

    override fun setupPreferenceScreen(screen: EntryPreferenceScreen) {
        screen.addPreference(ListPreference(screen.context).apply {
            key = "reading_format"
            title = "Reading format"
            entries = arrayOf("EPUB (requires a host with EPUB support)", "HTML", "Plain text")
            entryValues = arrayOf("epub", "html", "text")
            setDefaultValue("epub")
            summary = "Preferred edition; falls back to another readable format when unavailable."
        })
    }
}
