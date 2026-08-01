package eu.kanade.tachiyomi.extension.en.novelbuddy

import eu.kanade.tachiyomi.source.entry.BookResourceCatalog
import eu.kanade.tachiyomi.source.entry.BookResourceLocation
import eu.kanade.tachiyomi.source.entry.BookSourceResource
import eu.kanade.tachiyomi.source.entry.EntryMedia
import mihon.book.api.BookCatalogCoverage
import mihon.book.api.BookContentDescriptor
import mihon.book.api.BookResourceAvailability

internal fun NovelBuddyChapterContent.toBookMedia(
    chapterUrl: String,
    requestHeaders: Map<String, String>,
): EntryMedia.Book {
    val normalized = normalizeNovelBuddyProse(
        content = content ?: error("NovelBuddy chapter $id has no prose content"),
        baseUrl = chapterUrl,
    )
    require(normalized.html.length <= BookResourceLocation.MAX_INLINE_TEXT_LENGTH) {
        "NovelBuddy chapter $id exceeds the supported inline content limit"
    }
    val location = BookResourceLocation.InlineText(normalized.html, PROSE_MEDIA_TYPE)
    val resource = BookSourceResource(
        id = id,
        title = name,
        order = 0,
        mediaType = PROSE_MEDIA_TYPE,
        size = normalized.html.encodeToByteArray().size.toLong(),
        revision = cv?.toString(),
        availability = BookResourceAvailability.AVAILABLE,
        location = location,
    )
    val subordinateResources = normalized.assets.mapIndexed { index, asset ->
        BookSourceResource(
            id = asset.id,
            title = asset.alternativeText,
            order = (index + 1).toLong(),
            mediaType = asset.mediaType,
            availability = BookResourceAvailability.AVAILABLE,
            location = BookResourceLocation.RemoteRequest(
                url = asset.url,
                headers = requestHeaders,
            ),
        )
    }
    return EntryMedia.Book(
        descriptor = PROSE_DESCRIPTOR,
        catalog = BookResourceCatalog(
            resources = listOf(resource) + subordinateResources,
            coverage = BookCatalogCoverage.PARTIAL,
        ),
        initialResourceId = id,
        initialResourceLocation = location,
    )
}

private val PROSE_DESCRIPTOR = BookContentDescriptor(
    format = PROSE_MEDIA_TYPE,
    profile = "prose-chapter",
)
private const val PROSE_MEDIA_TYPE = "text/html"
