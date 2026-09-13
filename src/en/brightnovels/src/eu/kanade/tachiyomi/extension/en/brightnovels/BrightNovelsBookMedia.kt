package eu.kanade.tachiyomi.extension.en.brightnovels

import eu.kanade.tachiyomi.source.entry.BookResourceCatalog
import eu.kanade.tachiyomi.source.entry.BookResourceLocation
import eu.kanade.tachiyomi.source.entry.BookSourceResource
import eu.kanade.tachiyomi.source.entry.EntryMedia
import mihon.book.api.BookCatalogCoverage
import mihon.book.api.BookContentDescriptor
import mihon.book.api.BookResourceAvailability
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest

internal fun BrightNovelsChapterContent.toBookMedia(
    chapterUrl: String,
    referer: String,
    fallbackTitle: String,
): EntryMedia.Book {
    val normalized = normalizeBrightNovelsProse(
        content = content ?: error("Bright Novels chapter $id has no readable content"),
        baseUrl = chapterUrl,
    )
    require(normalized.html.isNotBlank()) { "Bright Novels chapter $id has no readable content" }
    require(normalized.html.length <= BookResourceLocation.MAX_INLINE_TEXT_LENGTH) {
        "Bright Novels chapter $id exceeds the supported inline content limit"
    }
    val location = BookResourceLocation.InlineText(normalized.html, PROSE_MEDIA_TYPE)
    val resource = BookSourceResource(
        id = id.toString(),
        title = title?.trim()?.ifBlank { null } ?: fallbackTitle,
        order = 0,
        mediaType = PROSE_MEDIA_TYPE,
        size = normalized.html.encodeToByteArray().size.toLong(),
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
                headers = mapOf("Referer" to referer),
            ),
        )
    }
    return EntryMedia.Book(
        descriptor = PROSE_DESCRIPTOR,
        catalog = BookResourceCatalog(
            resources = listOf(resource) + subordinateResources,
            coverage = BookCatalogCoverage.PARTIAL,
        ),
        initialResourceId = id.toString(),
        initialResourceLocation = location,
    )
}

private data class NormalizedBrightNovelsProse(
    val html: String,
    val assets: List<BrightNovelsProseAsset>,
)

private data class BrightNovelsProseAsset(
    val id: String,
    val url: String,
    val mediaType: String,
    val alternativeText: String?,
)

private fun normalizeBrightNovelsProse(content: String, baseUrl: String): NormalizedBrightNovelsProse {
    val body = Jsoup.parseBodyFragment(content, baseUrl).body()
    body.select("script, noscript, form, input, button, select, textarea, iframe").remove()
    body.select("a[href]").forEach { link ->
        val reference = link.attr("href").trim()
        when {
            reference.startsWith("#") -> link.attr("href", reference)
            else -> link.attr("abs:href")
                .takeIf(String::isSafeExternalLink)
                ?.let { link.attr("href", it) }
                ?: link.removeAttr("href")
        }
    }
    val assets = linkedMapOf<String, BrightNovelsProseAsset>()
    body.select("img[src]").forEach { image ->
        val url = image.attr("abs:src").takeIf(String::isSecureRemoteAsset)
        val mediaType = url?.inferredProseMediaType()?.takeIf { it in IMAGE_MEDIA_TYPES }
        if (url == null || mediaType == null) {
            image.removeAttr("src")
            return@forEach
        }
        val asset = BrightNovelsProseAsset(
            id = url.toProseAssetId(),
            url = url,
            mediaType = mediaType,
            alternativeText = image.attr("alt").trim().ifBlank { null },
        )
        image.attr("src", asset.id)
        assets.putIfAbsent(asset.id, asset)
    }
    body.ownerDocument()?.outputSettings()?.prettyPrint(false)
    return NormalizedBrightNovelsProse(html = body.html().trim(), assets = assets.values.toList())
}

private fun String.isSafeExternalLink(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}.getOrDefault(false)

private fun String.isSecureRemoteAsset(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}.getOrDefault(false)

private fun String.toProseAssetId(): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(substringBefore('#').encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
    return "brightnovels-image-${digest.take(24)}"
}

private fun String.inferredProseMediaType(): String? = runCatching {
    URI(this).path.substringAfterLast('.', "").lowercase()
}.getOrNull()?.let {
    when (it) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }
}

private val PROSE_DESCRIPTOR = BookContentDescriptor(
    format = PROSE_MEDIA_TYPE,
    profile = "prose-chapter",
)

private const val PROSE_MEDIA_TYPE = "text/html"

private val IMAGE_MEDIA_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
