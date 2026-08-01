package eu.kanade.tachiyomi.extension.en.readnovelfull

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object ReadNovelFullParser {
    fun parseListing(document: Document): ReadNovelFullListing = ReadNovelFullListing(
        entries = document.select("#list-page .archive > .list.list-novel > .row")
            .mapNotNull(::listingEntry),
        hasNextPage = document.selectFirst(".pagination li.next:not(.disabled) a[href]") != null,
    )

    fun parseDetails(document: Document): ReadNovelFullDetails = ReadNovelFullDetails(
        novelId = document.selectFirst("#rating[data-novel-id]")?.attr("data-novel-id")?.trim()?.ifBlank { null },
        title = document.selectFirst("#novel .books .title")?.text()?.trim()?.ifBlank { null },
        author = document.metadataLinks("Author:").firstOrNull(),
        description = document.selectFirst("#novel .desc-text")?.text()?.trim()?.ifBlank { null },
        genres = document.metadataLinks("Genre:"),
        status = document.metadataLinks("Status:").firstOrNull(),
        thumbnailUrl = document.selectFirst("#novel .info-holder .book img[src]")
            ?.attr("abs:src")
            ?.ifBlank { null },
    )

    fun parseChapterArchive(document: Document): List<ReadNovelFullChapter> = document
        .select(".list-chapter a[href]")
        .map { link ->
            ReadNovelFullChapter(
                url = link.entryPath(),
                name = link.text().trim(),
            )
        }
        .also { chapters ->
            require(chapters.isNotEmpty()) { "ReadNovelFull returned no chapters" }
            require(chapters.distinctBy(ReadNovelFullChapter::url).size == chapters.size) {
                "ReadNovelFull returned duplicate chapter URLs"
            }
        }

    fun parseChapter(document: Document): ReadNovelFullChapterContent {
        val content = document.selectFirst("#chr-content")?.clone()
            ?: error("ReadNovelFull returned no chapter content")
        content.select("script, noscript, form, button, input, .ads, .advertisement").remove()
        content.select("p, div").filter(Element::isBlank).forEach(Element::remove)
        val assets = content.normalizeReadNovelFullProse()
        val html = content.html().trim()
        require(html.isNotBlank()) { "ReadNovelFull returned an empty chapter" }
        return ReadNovelFullChapterContent(
            title = document.selectFirst("#chapter .chr-title")?.text()?.trim()?.ifBlank { null },
            html = html,
            assets = assets,
        )
    }

    private fun listingEntry(row: Element): ReadNovelFullEntry? {
        val link = row.selectFirst("h3.novel-title a[href]") ?: return null
        return ReadNovelFullEntry(
            url = link.entryPath(),
            title = link.text().trim(),
            author = row.selectFirst(".author")?.text()?.trim()?.ifBlank { null },
            thumbnailUrl = row.selectFirst("img.cover[src]")?.attr("abs:src")?.ifBlank { null },
            completed = row.selectFirst(".label-full") != null,
        )
    }
}

internal data class ReadNovelFullListing(
    val entries: List<ReadNovelFullEntry>,
    val hasNextPage: Boolean,
)

private fun Document.metadataLinks(label: String): List<String> = select("#novel .info-meta li")
    .firstOrNull { item -> item.selectFirst("h3")?.text()?.trim() == label }
    ?.select("a")
    ?.eachText()
    ?.map(String::trim)
    ?.filter(String::isNotBlank)
    ?.distinct()
    .orEmpty()

private fun Element.entryPath(): String = attr("abs:href")
    .removePrefix(BASE_URL)
    .takeIf { it.startsWith('/') }
    ?: error("ReadNovelFull returned an invalid entry URL: ${attr("href")}")

private fun Element.isBlank(): Boolean = text().isBlank() && children().isEmpty()

private const val BASE_URL = "https://readnovelfull.com"
