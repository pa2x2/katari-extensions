package eu.kanade.tachiyomi.extension.all.gutenberg

import eu.kanade.tachiyomi.source.entry.EntryPageResult
import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.SEntry
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

internal data class GutenbergBook(val entry: SEntry, val formats: List<GutenbergFormat>, val uploaded: Long)
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
    return GutenbergBook(entry, formats, uploaded)
}
