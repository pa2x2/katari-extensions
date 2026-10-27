package eu.kanade.tachiyomi.extension.all.gutenberg

import eu.kanade.tachiyomi.source.entry.EntryPageResult
import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.SEntry
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.ZoneOffset

internal fun gutenbergId(url: String): String? = runCatching {
    val path = gutenbergUrl(url).toHttpUrl().encodedPath
    Regex("/ebooks/([1-9][0-9]*)(?:/|\\.[^/]+)?").matchEntire(path)?.groupValues?.get(1)
}.getOrNull()

internal fun Document.gutenbergListing(): EntryPageResult<SEntry> {
    val items = select("li.booklink a.link[href]").mapNotNull { link ->
        val id = gutenbergId(link.absUrl("href")) ?: return@mapNotNull null
        SEntry.create().apply {
            url = "/ebooks/$id"
            title = link.select(".title").text()
            author = link.select(".subtitle").text().ifBlank { null }
            thumbnailUrl = link.selectFirst("img[src]")?.absUrl("src")
            type = EntryType.BOOK
        }
    }.distinctBy { it.url }
    require(items.isNotEmpty() || text().contains("No records found", ignoreCase = true)) {
        "Project Gutenberg did not return a catalogue page. Open the source in WebView and try again."
    }
    // The head's rel=next drops the query/sort. The visible Next link preserves them.
    return EntryPageResult(items, select("a[accesskey=+]").any { it.text().trim() == "Next" })
}

/** The page heading of a listing, such as "Readers also downloaded" or "Books by Carroll, Lewis". */
internal fun Document.gutenbergHeading(): String? =
    (selectFirst(".page_content h1") ?: selectFirst("h1"))?.text()?.trim()?.ifBlank { null }

/**
 * @property relatedPaths listings the book page links as related, in the site's order: "Readers also downloaded",
 * then the authors' pages, then the bookshelves.
 */
internal data class GutenbergBook(
    val entry: SEntry,
    val formats: List<GutenbergFormat>,
    val uploaded: Long,
    val relatedPaths: List<String>,
)
internal data class GutenbergFormat(val url: String, val mime: String, val label: String)

internal fun Document.gutenbergBook(id: String): GutenbergBook {
    require(selectFirst("#about_book_table, table.bibrec") != null) {
        "Project Gutenberg did not return book details. Open the entry in WebView and try again."
    }
    fun field(label: String): String = select("table.bibrec tr").filter {
        it.selectFirst("th")?.text() == label
    }.joinToString("; ") { it.select("td").text() }
    val entry = SEntry.create().apply {
        url = "/ebooks/$id"
        title = field("Title").ifBlank { select("#book_title").text() }
        author = field("Author").ifBlank { null }
        artist = field("Illustrator").ifBlank { null }
        description = listOf(
            select(".summary-text-container").text(),
            field("Language").takeIf { it.isNotBlank() }?.let { "Language: $it" }.orEmpty(),
            field("Category").takeIf { it.isNotBlank() }?.let { "Category: $it" }.orEmpty(),
            field("Copyright"),
        ).filter(String::isNotBlank).joinToString("\n\n")
        genre = select("td[property=dcterms:subject] a, .similar-books-tags a[href*=/bookshelf/]")
            .map { it.text() }.distinct()
        thumbnailUrl = "$GUTENBERG_URL/cache/epub/$id/pg$id.cover.medium.jpg"
        type = EntryType.BOOK
        status = SEntry.COMPLETED
        initialized = true
    }
    val formats = select("a.read-online-button, a.featured-format-link, a.other-format-link, a.link[type]")
        .mapNotNull { link ->
            val url = runCatching { gutenbergUrl(link.absUrl("href")) }.getOrNull() ?: return@mapNotNull null
            val mime = link.attr("type").substringBefore(';').ifBlank {
                when {
                    ".epub" in url -> "application/epub+zip"
                    url.substringBefore('?').endsWith(".html") -> "text/html"
                    else -> return@mapNotNull null
                }
            }
            GutenbergFormat(url, mime, link.text())
        }.distinctBy { it.url }
    val date = selectFirst("[itemprop=dateModified]")?.text().orEmpty().ifBlank {
        selectFirst("[itemprop=datePublished]")?.text().orEmpty()
    }
    val uploaded = runCatching {
        LocalDate.parse(date, java.time.format.DateTimeFormatter.ofPattern("MMM d, uuuu", java.util.Locale.ENGLISH))
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrDefault(0)
    return GutenbergBook(entry, formats, uploaded, relatedPaths(id))
}

/** The whole book is one chapter; its identity stays `/ebooks/<id>#read`. */
internal fun GutenbergBook.readChapter(): SEntryChapter = SEntryChapter.create().apply {
    url = "${entry.url}#read"
    name = "Read book"
    chapterNumber = 1.0
    dateUpload = uploaded
}

private fun Document.relatedPaths(id: String): List<String> {
    fun paths(selector: String, pattern: Regex): List<String> = select(selector).mapNotNull { link ->
        runCatching { gutenbergUrl(link.absUrl("href")).toHttpUrl().encodedPath }.getOrNull()
            ?.let(pattern::matchEntire)?.value
    }.distinct()
    val also = paths(".similar-books-tags a[href]", Regex("/ebooks/$id/also/?")).ifEmpty { listOf("/ebooks/$id/also/") }
    val authors = paths("table.bibrec a[rel=marcrel:aut][href]", Regex("/ebooks/author/[0-9]+"))
    val shelves = paths(".similar-books-tags a[href]", Regex("/ebooks/bookshelf/[0-9]+"))
    return also.take(1) + authors.take(MAX_RELATED_AUTHORS) + shelves.take(MAX_RELATED_SHELVES)
}

// Each listing is one throttled request, so a book with many authors or shelves shows only the first few.
private const val MAX_RELATED_AUTHORS = 2
private const val MAX_RELATED_SHELVES = 3
