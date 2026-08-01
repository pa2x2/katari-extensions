package eu.kanade.tachiyomi.extension.en.novelfull

import eu.kanade.tachiyomi.source.entry.BookResourceCatalog
import eu.kanade.tachiyomi.source.entry.BookResourceLocation
import eu.kanade.tachiyomi.source.entry.BookSourceResource
import eu.kanade.tachiyomi.source.entry.EntryMedia
import mihon.book.api.BookCatalogCoverage
import mihon.book.api.BookContentDescriptor
import mihon.book.api.BookResourceAvailability

internal fun NovelFullChapterContent.toBookMedia(
    resourceId: String,
    fallbackTitle: String,
    requestHeaders: Map<String, String>,
): EntryMedia.Book {
    require(html.length <= BookResourceLocation.MAX_INLINE_TEXT_LENGTH) {
        "NovelFull chapter $resourceId exceeds the supported inline content limit"
    }
    val location = BookResourceLocation.InlineText(html, PROSE_CHAPTER_MEDIA_TYPE)
    val resource = BookSourceResource(
        id = resourceId,
        title = title ?: fallbackTitle,
        order = 0,
        mediaType = PROSE_CHAPTER_MEDIA_TYPE,
        size = html.encodeToByteArray().size.toLong(),
        availability = BookResourceAvailability.AVAILABLE,
        location = location,
    )
    val subordinateResources = assets.mapIndexed { index, asset ->
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
        descriptor = PROSE_CHAPTER_DESCRIPTOR,
        catalog = BookResourceCatalog(
            resources = listOf(resource) + subordinateResources,
            coverage = BookCatalogCoverage.PARTIAL,
        ),
        initialResourceId = resourceId,
        initialResourceLocation = location,
    )
}

internal val PROSE_CHAPTER_DESCRIPTOR = BookContentDescriptor(
    format = PROSE_CHAPTER_MEDIA_TYPE,
    profile = "prose-chapter",
)
internal const val PROSE_CHAPTER_MEDIA_TYPE = "text/html"
