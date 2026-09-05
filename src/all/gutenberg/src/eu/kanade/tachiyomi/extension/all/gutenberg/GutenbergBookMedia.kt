package eu.kanade.tachiyomi.extension.all.gutenberg

import eu.kanade.tachiyomi.source.entry.*
import mihon.book.api.BookCatalogCoverage
import mihon.book.api.BookContentDescriptor
import mihon.book.api.BookResourceAvailability
import org.jsoup.nodes.Document
import java.security.MessageDigest

internal fun GutenbergBook.readableFormat(preference: String): GutenbergFormat {
    val order = when (preference) {
        "html" -> listOf("text/html", "text/plain", "application/epub+zip")
        "text" -> listOf("text/plain", "text/html", "application/epub+zip")
        else -> listOf("application/epub+zip", "text/html", "text/plain")
    }
    return order.firstNotNullOfOrNull { mime -> formats.firstOrNull { it.mime == mime } }
        ?: error("This entry has no EPUB, HTML or plain-text edition. Open it in WebView to access its original files.")
}

internal fun GutenbergBook.epubMedia(format: GutenbergFormat): EntryMedia.Book {
    val resourceId = "gutenberg-${gutenbergId(entry.url)}-publication"
    val location = BookResourceLocation.RemoteRequest(format.url)
    return EntryMedia.Book(
        descriptor = BookContentDescriptor("application/epub+zip"),
        catalog = BookResourceCatalog(
            resources = listOf(BookSourceResource(
                id = resourceId,
                title = entry.title,
                order = 0,
                mediaType = format.mime,
                location = location,
                availability = BookResourceAvailability.AVAILABLE,
            )),
            coverage = BookCatalogCoverage.COMPLETE,
        ),
        initialResourceId = resourceId,
        initialResourceLocation = location,
    )
}

/** Converts HTML assets to catalogue resource identities understood by the canonical BOOK reader. */
internal fun GutenbergBook.htmlMedia(document: Document): EntryMedia.Book {
    document.select("script, noscript, form, input, button, iframe").remove()
    val assets = linkedMapOf<String, BookSourceResource>()
    document.select("img[src]").forEach { image ->
        val url = image.absUrl("src").takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return@forEach image.removeAttr("src").let { }
        val resourceId = "image-" + MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        val mime = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            else -> null
        }
        assets[resourceId] = BookSourceResource(
            id = resourceId,
            title = image.attr("alt").ifBlank { null },
            mediaType = mime,
            availability = BookResourceAvailability.AVAILABLE,
            location = BookResourceLocation.RemoteRequest(url),
        )
        image.attr("src", resourceId)
    }
    document.select("a[href]").filterNot { it.attr("href").startsWith('#') }.forEach {
        val url = it.absUrl("href")
        if (url.startsWith("https://") || url.startsWith("http://")) it.attr("href", url) else it.removeAttr("href")
    }
    document.outputSettings().prettyPrint(false)
    val html = document.outerHtml()
    require(html.length <= BookResourceLocation.MAX_INLINE_TEXT_LENGTH) {
        "This book is too large for the HTML reading option. Select EPUB in the source settings."
    }
    val resourceId = "gutenberg-${gutenbergId(entry.url)}-publication"
    val location = BookResourceLocation.InlineText(html, "text/html")
    return EntryMedia.Book(
        descriptor = BookContentDescriptor("text/html", "prose-chapter"),
        catalog = BookResourceCatalog(
            resources = listOf(BookSourceResource(
                id = resourceId,
                title = entry.title,
                order = 0,
                mediaType = "text/html",
                availability = BookResourceAvailability.AVAILABLE,
                location = location,
            )) + assets.values,
            coverage = BookCatalogCoverage.COMPLETE,
        ),
        initialResourceId = resourceId,
        initialResourceLocation = location,
    )
}
