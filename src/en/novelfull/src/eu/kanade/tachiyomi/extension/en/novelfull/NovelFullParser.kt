package eu.kanade.tachiyomi.extension.en.novelfull

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object NovelFullParser {
    fun parseListing(document: Document): NovelFullListing = NovelFullListing(
        entries = document.select("#list-page .fwn-rank-list .li-row").mapNotNull(::listingEntry),
        hasNextPage = document.selectFirst("nav.fwn-arch-pages a[rel=next][href]") != null,
    )

    fun parseDetails(document: Document): NovelFullDetails {
        val novel = document.selectFirst("#truyen") ?: error("NovelFull returned no novel details")
        return NovelFullDetails(
            novelId = document.selectFirst("#truyen-id[value]")?.attr("value")?.trim()?.ifBlank { null }
                ?: document.selectFirst("[data-novel-id]")?.attr("data-novel-id")?.trim()?.ifBlank { null },
            title = novel.selectFirst(".m-desc h1.tit")?.text()?.trim()?.ifBlank { null },
            author = novel.infoLinks("Author").joinToString().ifBlank { null },
            description = novel.selectFirst("#novel-summary-inner")?.paragraphText(),
            genres = novel.infoLinks("Genre"),
            status = novel.infoValue("Status")?.text()?.trim(),
            thumbnailUrl = novel.selectFirst(".m-imgtxt .pic img[src]")?.attr("abs:src")?.ifBlank { null },
            totalChapters = novel.attr("data-total-chapters").trim().toIntOrNull(),
            latestChapters = novel.select("#latest-chapter li a.con[href]").map { link ->
                NovelFullChapterLink(url = link.attr("abs:href").toEntryPath(), name = link.text().trim())
            },
        )
    }

    /** The side lists of a novel page, each under the site's heading, such as "Same Author" or "Related Novels". */
    fun parseRelated(document: Document): List<NovelFullRelatedGroup> = document
        .select(".fwn-novel-side .col-l:has(> ul.ul-list6)")
        .map { block ->
            NovelFullRelatedGroup(
                title = block.selectFirst("> .g-tit .tit")?.text()?.trim()?.ifBlank { null },
                entries = block.select("> ul.ul-list6 > li").mapNotNull(::relatedEntry).distinctBy(NovelFullEntry::url),
            )
        }
        .filter { it.entries.isNotEmpty() }

    /** Every chapter, oldest first as the site's chapter selector lists them. */
    fun parseChapterArchive(document: Document): List<NovelFullChapterLink> = document
        .select("select.chapter_jump > option[value]")
        .map { option ->
            NovelFullChapterLink(url = option.attr("value").toEntryPath(), name = option.text().trim())
        }
        .also { chapters ->
            require(chapters.isNotEmpty()) { "NovelFull returned no chapters" }
            require(chapters.distinctBy(NovelFullChapterLink::url).size == chapters.size) {
                "NovelFull returned duplicate chapter URLs"
            }
        }

    fun parseChapter(document: Document): NovelFullChapterContent {
        val content = document.selectFirst("#chapter-content")?.novelFullProseRoot()
            ?: error("NovelFull returned no chapter content")
        content.select(
            ".fwn-slot-host, ins.pubadx-slot, iframe[data-aa], #frame, .ads.ads-holder.ads-middle, script, noscript, form",
        ).remove()
        content.select("div, p").filter(Element::isBlank).forEach(Element::remove)
        val assets = content.normalizeNovelFullProse()
        require(content.html().isNotBlank()) { "NovelFull returned an empty chapter" }
        return NovelFullChapterContent(
            title = document.selectFirst("#chapter .chapter-title")?.text()?.trim()?.ifBlank { null },
            html = content.outerHtml().trim(),
            assets = assets,
        )
    }

    private fun relatedEntry(item: Element): NovelFullEntry? {
        val link = item.selectFirst("h3.tit a.con[href]") ?: return null
        return NovelFullEntry(
            url = link.attr("abs:href").toEntryPath(),
            title = link.text().trim(),
            thumbnailUrl = item.selectFirst(".pic img[src]")?.attr("abs:src")?.ifBlank { null },
        )
    }

    private fun listingEntry(row: Element): NovelFullEntry? {
        val link = row.selectFirst(".txt h3.tit a[href]") ?: return null
        return NovelFullEntry(
            url = link.attr("abs:href").toEntryPath(),
            title = link.text().trim(),
            thumbnailUrl = row.selectFirst(".pic img[src]")?.attr("abs:src")?.ifBlank { null },
        )
    }
}

internal data class NovelFullListing(
    val entries: List<NovelFullEntry>,
    val hasNextPage: Boolean,
)

internal data class NovelFullRelatedGroup(
    val title: String?,
    val entries: List<NovelFullEntry>,
)

internal data class NovelFullChapterContent(
    val title: String?,
    val html: String,
    val assets: List<NovelFullProseAsset>,
)

/** The value of a details row, which the site labels only by its icon's title, such as "Author". */
private fun Element.infoValue(label: String): Element? = select(".m-imgtxt .txt .item")
    .firstOrNull { item -> item.selectFirst("> .glyphicon[title]")?.attr("title") == label }
    ?.selectFirst("> .right")

private fun Element.infoLinks(label: String): List<String> = infoValue(label)
    ?.select("a")
    ?.eachText()
    ?.map(String::trim)
    ?.filter(String::isNotBlank)
    ?.distinct()
    .orEmpty()

private fun Element.paragraphText(): String? = select("p").eachText()
    .map(String::trim)
    .filter(String::isNotBlank)
    .joinToString("\n\n")
    .ifBlank { text().trim() }
    .ifBlank { null }

private fun String.toEntryPath(): String = removePrefix("https://novelfull.net")
    .takeIf { it.startsWith('/') }
    ?: error("NovelFull returned an invalid relative URL: $this")

private fun Element.isBlank(): Boolean = text().isBlank() && children().isEmpty()
