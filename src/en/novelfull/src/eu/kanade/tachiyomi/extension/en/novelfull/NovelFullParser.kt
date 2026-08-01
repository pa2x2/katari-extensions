package eu.kanade.tachiyomi.extension.en.novelfull

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object NovelFullParser {
    fun parseListing(document: Document): NovelFullListing = NovelFullListing(
        entries = document.select("#list-page .archive > .list.list-truyen > .row")
            .mapNotNull(::listingEntry),
        hasNextPage = document.selectFirst(".pagination li.next:not(.disabled) a[href]") != null,
    )

    fun parseDetails(document: Document): NovelFullDetails = NovelFullDetails(
        novelId = document.selectFirst("#rating[data-novel-id]")
            ?.attr("data-novel-id")
            ?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("#truyen-id[value]")?.attr("value")?.trim()?.ifBlank { null },
        title = document.selectFirst("#truyen .info-holder .books h3.title")?.text()?.trim()?.ifBlank { null },
        author = document.selectFirst("#truyen .info-holder .info a[href^=/author/]")?.text()?.trim()?.ifBlank { null },
        description = document.selectFirst("#truyen .desc-text")?.text()?.trim()?.ifBlank { null },
        genres = document.select("#truyen .info-holder .info a[href^=/genre/]")
            .eachText()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct(),
        status = document.selectFirst("#truyen .info-holder .info a[href^=/status/]")?.text()?.trim(),
        thumbnailUrl = document.selectFirst("#truyen .info-holder .book img[src]")
            ?.attr("abs:src")
            ?.ifBlank { null },
    )

    fun parseChapterArchive(document: Document): List<NovelFullChapter> = document
        .select("select.chapter_jump > option[value]")
        .mapIndexed { index, link ->
            NovelFullChapter(
                url = link.attr("value").toEntryPath(),
                name = link.text().trim(),
                order = index + 1,
            )
        }
        .also { chapters ->
            require(chapters.isNotEmpty()) { "NovelFull returned no chapters" }
            require(chapters.distinctBy(NovelFullChapter::url).size == chapters.size) {
                "NovelFull returned duplicate chapter URLs"
            }
        }

    fun parseChapter(document: Document): NovelFullChapterContent {
        val content = document.selectFirst("#chapter-content")?.clone()
            ?: error("NovelFull returned no chapter content")
        content.select("iframe[data-aa], #frame, .ads.ads-holder.ads-middle, script, noscript, form").remove()
        content.select("div, p").filter(Element::isBlank).forEach(Element::remove)
        val assets = content.normalizeNovelFullProse()
        return NovelFullChapterContent(
            title = document.selectFirst("#chapter .chapter-title")?.text()?.trim()?.ifBlank { null },
            html = content.html().trim(),
            assets = assets,
        )
    }

    private fun listingEntry(row: Element): NovelFullEntry? {
        val link = row.selectFirst("h3.truyen-title a[href]") ?: return null
        return NovelFullEntry(
            url = link.attr("href").toEntryPath(),
            title = link.text().trim(),
            author = row.selectFirst(".author")?.text()?.trim()?.ifBlank { null },
            thumbnailUrl = row.selectFirst("img.cover[src]")?.attr("abs:src")?.ifBlank { null },
        )
    }
}

internal data class NovelFullListing(
    val entries: List<NovelFullEntry>,
    val hasNextPage: Boolean,
)

internal data class NovelFullChapterContent(
    val title: String?,
    val html: String,
    val assets: List<NovelFullProseAsset>,
)

private fun String.toEntryPath(): String = removePrefix("https://novelfull.net")
    .takeIf { it.startsWith('/') }
    ?: error("NovelFull returned an invalid relative URL: $this")

private fun Element.isBlank(): Boolean = text().isBlank() && children().isEmpty()
