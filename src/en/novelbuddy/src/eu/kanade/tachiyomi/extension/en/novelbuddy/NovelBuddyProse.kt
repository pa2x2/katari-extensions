package eu.kanade.tachiyomi.extension.en.novelbuddy

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest

internal data class NormalizedNovelBuddyProse(
    val html: String,
    val assets: List<NovelBuddyProseAsset>,
)

internal data class NovelBuddyProseAsset(
    val id: String,
    val url: String,
    val mediaType: String,
    val alternativeText: String?,
)

internal fun normalizeNovelBuddyProse(content: String, baseUrl: String): NormalizedNovelBuddyProse {
    val body = Jsoup.parseBodyFragment(content, baseUrl).body()
    body.select("script, noscript, form, input, button, select, textarea, .ads, .advertisement").remove()
    body.normalizeProseLinks()
    val assets = body.extractProseAssets()
    body.ownerDocument()?.outputSettings()?.prettyPrint(false)
    return NormalizedNovelBuddyProse(
        html = body.html().trim(),
        assets = assets,
    )
}

private fun Element.normalizeProseLinks() {
    select("a[href]").forEach { link ->
        val reference = link.attr("href").trim()
        when {
            reference.startsWith("#") -> link.attr("href", reference)
            else -> link.attr("abs:href")
                .takeIf(String::isSafeExternalLink)
                ?.let { link.attr("href", it) }
                ?: link.removeAttr("href")
        }
    }
}

private fun Element.extractProseAssets(): List<NovelBuddyProseAsset> {
    val assets = linkedMapOf<String, NovelBuddyProseAsset>()
    select("img[src]").forEach { image ->
        val url = image.attr("abs:src").takeIf(String::isSecureRemoteAsset)
        val mediaType = url?.inferredProseMediaType()?.takeIf { it in IMAGE_MEDIA_TYPES }
        if (url == null || mediaType == null) {
            image.removeAttr("src")
            return@forEach
        }
        val asset = NovelBuddyProseAsset(
            id = url.toProseAssetId("image"),
            url = url,
            mediaType = mediaType,
            alternativeText = image.attr("alt").trim().ifBlank { null },
        )
        image.attr("src", asset.id)
        assets.putIfAbsent(asset.id, asset)
    }
    select("style").forEach { style ->
        style.text(style.data().rewriteProseFontFaces(style.baseUri(), assets))
    }
    return assets.values.toList()
}

private fun String.rewriteProseFontFaces(
    baseUrl: String,
    assets: MutableMap<String, NovelBuddyProseAsset>,
): String = FONT_FACE_REGEX.replace(this) { fontFace ->
    val declarations = fontFace.groupValues[1]
    val source = FONT_SOURCE_REGEX.find(declarations) ?: return@replace ""
    val asset = URL_FUNCTION_REGEX.findAll(source.groupValues[2])
        .mapNotNull { urlFunction ->
            val url = resolveSecureAssetUrl(baseUrl, urlFunction.groupValues[2]) ?: return@mapNotNull null
            val mediaType = url.inferredProseMediaType()
                ?.takeIf { it in FONT_MEDIA_TYPES }
                ?: return@mapNotNull null
            NovelBuddyProseAsset(
                id = url.toProseAssetId("font"),
                url = url,
                mediaType = mediaType,
                alternativeText = null,
            )
        }
        .firstOrNull()
        ?: return@replace ""
    assets.putIfAbsent(asset.id, asset)
    val rewritten = declarations.replaceRange(
        source.range,
        "${source.groupValues[1]}url(\"${asset.id}\")",
    )
    "@font-face {$rewritten}"
}

private fun resolveSecureAssetUrl(baseUrl: String, reference: String): String? = runCatching {
    URI(baseUrl).resolve(reference).toString().takeIf(String::isSecureRemoteAsset)
}.getOrNull()

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

private fun String.toProseAssetId(kind: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(substringBefore('#').encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
    return "prose-$kind-${digest.take(24)}"
}

private fun String.inferredProseMediaType(): String? = runCatching {
    URI(this).path.substringAfterLast('.', "").lowercase()
}.getOrNull()?.let {
    when (it) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        else -> null
    }
}

private val FONT_FACE_REGEX = Regex("""(?is)@font-face\s*\{([^}]*)\}""")
private val FONT_SOURCE_REGEX = Regex("""(?is)(\bsrc\s*:\s*)([^;}]*)""")
private val URL_FUNCTION_REGEX = Regex("""(?is)url\(\s*(['"]?)([^'")]+)\1\s*\)""")
private val IMAGE_MEDIA_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
private val FONT_MEDIA_TYPES = setOf("font/ttf", "font/otf")
