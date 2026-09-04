package eu.kanade.tachiyomi.extension.en.hianime

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object HiAnimeParser {
    fun parseListing(document: Document, page: Int): Pair<List<HiAnimeEntry>, Boolean> {
        val entries = document.select(
            "#main-content .film_list-wrap .flw-item, .page-search-wrap .film_list-wrap .flw-item",
        )
            .mapNotNull(::listingEntry)
        val hasNextPage = document.selectFirst(".pagination a[rel=next][href]") != null ||
            document.select(".pagination a[href]").any {
                it.attr("abs:href").toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull() == page + 1
            }
        return entries to hasNextPage
    }

    fun parseDetails(document: Document): HiAnimeDetails {
        val metadata = document.select(".anisc-info .item").associateBy {
            it.selectFirst(".item-head")?.text()?.trim()?.removeSuffix(":")?.lowercase().orEmpty()
        }
        val overview = metadata["overview"]?.selectFirst(".text")?.cleanText()
            ?: document.selectFirst(".anisc-detail .film-description .text")?.cleanText()

        return HiAnimeDetails(
            animeId = document.location().hiAnimeId(),
            title = document.selectFirst(".anisc-detail .film-name")?.text()?.trim()?.ifBlank { null },
            alternateNames = listOf("Japanese", "Synonyms").mapNotNull { label ->
                metadata[label.lowercase()]?.metadataText()?.let { label to it }
            },
            description = overview,
            genres = metadata["genres"]?.select("a")?.eachText().orEmpty()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct(),
            status = metadata["status"]?.metadataText(),
            aired = metadata["aired"]?.metadataText(),
            duration = metadata["duration"]?.metadataText(),
            score = metadata["mal score"]?.metadataText(),
            studios = metadata["studios"]?.select("a")?.eachText().orEmpty().cleanValues(),
            producers = metadata["producers"]?.select("a")?.eachText().orEmpty().cleanValues(),
            thumbnailUrl = document.selectFirst(".anis-content .film-poster img, .anisc-poster img")
                ?.imageUrl(),
        )
    }

    fun parseEpisodes(document: Document): List<HiAnimeChapter> = document
        .select(".ep-item[data-id]")
        .mapNotNull { item ->
            val id = item.attr("data-id").trim().ifBlank { return@mapNotNull null }
            val number = item.attr("data-number").trim().toDoubleOrNull() ?: return@mapNotNull null
            val url = item.attr("abs:href").toRelativeUrl()
                ?: item.attr("href").toRelativeUrl()
                ?: return@mapNotNull null
            HiAnimeChapter(
                id = id,
                url = url,
                name = item.selectFirst(".ep-name")?.text()?.trim().orEmpty().ifBlank { "Episode $number" },
                number = number,
            )
        }
        .sortedByDescending(HiAnimeChapter::number)

    fun parseServers(document: Document): List<HiAnimeServer> = document
        .select(".server-item[data-type][data-server-name][data-hash]")
        .mapNotNull { item ->
            val type = item.attr("data-type").trim().lowercase().ifBlank { return@mapNotNull null }
            val name = item.attr("data-server-name").trim().ifBlank { return@mapNotNull null }
            val url = runCatching {
                String(Base64.getDecoder().decode(item.attr("data-hash")), StandardCharsets.UTF_8)
            }.getOrNull()?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            HiAnimeServer(type, name, name.toOptionKey(), url)
        }

    fun parseRelated(document: Document): List<HiAnimeEntry> {
        val section = document.select("section").firstOrNull {
            it.selectFirst(".cat-heading")?.text()?.contains("Recommended", ignoreCase = true) == true
        } ?: return emptyList()
        return section.select(".film_list-wrap .flw-item").mapNotNull(::listingEntry)
    }

    private fun listingEntry(item: Element): HiAnimeEntry? {
        val link = item.selectFirst(".film-name a[href]") ?: return null
        val url = link.attr("abs:href").toEntryPath()
            ?: link.attr("href").toEntryPath()
            ?: return null
        return HiAnimeEntry(
            url = url,
            title = link.text().trim().ifBlank { return null },
            thumbnailUrl = item.selectFirst(".film-poster img")?.imageUrl(),
        )
    }

    private fun Element.metadataText(): String? {
        selectFirst(".name")?.text()?.trim()?.ifBlank { null }?.let { return it }
        val copy = clone()
        copy.select(".item-head").remove()
        return copy.text().trim().ifBlank { null }
    }

    private fun Element.cleanText(): String? {
        val copy = clone()
        copy.select(".btn-more-desc").remove()
        return copy.text().trim().ifBlank { null }
    }

    private fun Element.imageUrl(): String? = attr("abs:src").ifBlank { attr("abs:data-src") }
        .ifBlank { attr("src") }
        .ifBlank { attr("data-src") }
        .ifBlank { null }

    private fun List<String>.cleanValues(): List<String> = map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    private fun String.toEntryPath(): String? {
        val url = toHttpUrlOrNull()
        val path = when {
            url != null && (url.host == HIANIME_HOST || url.host == "www.$HIANIME_HOST") -> url.encodedPath
            startsWith('/') -> substringBefore('?')
            else -> return null
        }.removePrefix("/watch")
        return path.takeIf(DETAIL_PATH_REGEX::matches)
    }

    private fun String.toRelativeUrl(): String? {
        val url = toHttpUrlOrNull()
        if (url != null) {
            if (url.host != HIANIME_HOST && url.host != "www.$HIANIME_HOST") return null
            return buildString {
                append(url.encodedPath)
                url.encodedQuery?.let { append('?').append(it) }
            }
        }
        return takeIf { startsWith('/') }
    }

    private fun String.toOptionKey(): String = lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private const val HIANIME_HOST = "hianime.at"
    private val DETAIL_PATH_REGEX = Regex("/[^/?]+-\\d+")
}
