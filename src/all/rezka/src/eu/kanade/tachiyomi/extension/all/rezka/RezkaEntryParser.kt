package eu.kanade.tachiyomi.extension.all.rezka

import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.RelatedEntryGroup
import eu.kanade.tachiyomi.source.entry.SEntry
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

internal fun parseListing(document: Document, baseUrl: String): List<SEntry> {
    return document.select(".b-content__inline_items > .b-content__inline_item")
        .mapNotNull { toListingEntry(it, baseUrl) }
}

internal fun hasNextPage(document: Document): Boolean {
    return document.selectFirst("link[rel=next]") != null ||
        document.selectFirst(".b-navigation a:has(.b-navigation__next)") != null
}

private fun toListingEntry(element: Element, baseUrl: String): SEntry? {
    val anchor = element.selectFirst(".b-content__inline_item-link > a[href]") ?: return null
    val url = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: anchor.attr("href")
    val relativeUrl = url.toRelativeUrl(baseUrl) ?: return null
    val title = anchor.text().trim().ifBlank { return null }
    val meta = element.selectFirst(".b-content__inline_item-link > div")?.text()?.trim()
    val badge = element.selectFirst(".b-content__inline_item-cover .cat .entity")?.text()?.trim()
    val info = element.selectFirst(".b-content__inline_item-cover .info")?.text()?.trim()
    val thumbnail = element.selectFirst(".b-content__inline_item-cover img[src], .b-content__inline_item-cover img[data-src]")
        ?.let(::imageUrl)

    return SEntry.create().apply {
        this.url = relativeUrl
        this.title = title
        this.description = listOfNotNull(badge, meta, info).joinToString(" | ").ifBlank { null }
        this.thumbnailUrl = thumbnail
        this.initialized = false
        this.type = EntryType.ANIME
    }
}

/** Details of the title [document] shows; [entry] supplies what the page leaves out. */
internal fun parseEntryDetails(document: Document, entry: SEntry, hasPlayer: Boolean): SEntry {
    val metadata = metadataRowsByLabel(document)
    return entry.copy().apply {
        initialized = true
        title = document.selectFirst("h1[itemprop=name], h1")?.text()?.trim().orEmpty().ifBlank { entry.title }
        description = document.selectFirst(".b-post__description_text .b-post__description_text_inner, .b-post__description_text_inner, .b-post__description_text")
            ?.text()
            ?.trim()
            ?.ifBlank { entry.description }
        thumbnailUrl = document.selectFirst(".b-sidecover img[src], .b-sidecover img[data-src], .b-content__inline_item-cover img[src]")
            ?.let(::imageUrl)
            ?: entry.thumbnailUrl
        genre = parseLinkedTexts(metadata["Жанр"] ?: metadata["Жанры"], "span[itemprop=genre], a")
            ?: entry.genre
        author = parseLinkedTexts(metadata["Режиссер"] ?: metadata["Режиссёр"], ".person-name-item [itemprop=name], .persons-list-holder a, a")
            ?.joinToString(", ")
            ?: entry.author
        artist = parseJoinedValues(metadata["Студия"] ?: metadata["Студии"]) ?: entry.artist
        status = parseStatus(
            statusText = document.selectFirst(".b-post__infolast")?.text(),
            releaseDate = releaseDate(document),
            hasPlayer = hasPlayer,
            fallback = entry.status,
        )
        type = EntryType.ANIME
    }
}

internal fun releaseDate(document: Document): Long? = parseRussianDate(metadataRowsByLabel(document)["Дата выхода"]?.text())

/**
 * A title the site is still waiting for shows "Ожидаем … в хорошем качестве" with a trailer instead of the player. It
 * has nothing to play yet.
 */
internal fun isAwaited(document: Document, hasPlayer: Boolean): Boolean {
    return !hasPlayer && document.selectFirst(".b-post__go_status") != null
}

/** "Все части франшизы": the other parts of the title's franchise, as the site orders them. */
internal fun parseFranchise(document: Document, baseUrl: String): RelatedEntryGroup? {
    val entries = document.select(".b-post__partcontent > .b-post__partcontent_item:not(.current)")
        .mapNotNull { item ->
            val anchor = item.selectFirst(".title a[href]") ?: return@mapNotNull null
            val url = (anchor.absUrl("href").ifBlank { anchor.attr("href") }).toRelativeUrl(baseUrl)
                ?: return@mapNotNull null
            val title = anchor.text().trim().ifBlank { return@mapNotNull null }
            SEntry.create().apply {
                this.url = url
                this.title = title
                this.initialized = false
                this.type = EntryType.ANIME
            }
        }
        .distinctBy(SEntry::url)
    if (entries.isEmpty()) return null

    val title = document.selectFirst(".b-post__franchise_link_title")?.text()?.trim()?.ifBlank { null }
    return RelatedEntryGroup(title = title ?: "Все части франшизы", entries = entries)
}

private fun metadataRowsByLabel(document: Document): Map<String, Element> {
    return document.select(".b-post__infotable_right_inner table.b-post__info tr")
        .mapNotNull { row ->
            val label = row.selectFirst("td.l > h2")
                ?.text()
                ?.trim()
                ?.removeSuffix(":")
                ?.ifBlank { null }
                ?: return@mapNotNull null
            val valueCell = row.select("td").drop(1).firstOrNull() ?: return@mapNotNull null
            label to valueCell
        }
        .toMap()
}

private fun imageUrl(image: Element): String? {
    val raw = image.attr("src").ifBlank { image.attr("data-src") }.trim()
    if (raw.isBlank()) return null
    return if (raw.startsWith("//")) "https:$raw" else raw
}

private fun parseLinkedTexts(cell: Element?, selector: String): List<String>? {
    return cell?.select(selector)
        ?.map { it.text().trim() }
        ?.filter(String::isNotBlank)
        ?.distinct()
        ?.takeIf(List<String>::isNotEmpty)
}

private fun parseJoinedValues(cell: Element?): String? {
    if (cell == null) return null
    val linkedValues = parseLinkedTexts(cell, "a")
    if (linkedValues != null) return linkedValues.joinToString(", ")
    return cell.text().trim().ifBlank { null }
}

private fun parseStatus(statusText: String?, releaseDate: Long?, hasPlayer: Boolean, fallback: Int): Int {
    val normalized = statusText?.lowercase(Locale.ROOT)?.trim().orEmpty()

    return when {
        "заверш" in normalized -> SEntry.COMPLETED
        "онгоинг" in normalized || "выходит" in normalized || "выходят" in normalized || "в процессе" in normalized -> SEntry.ONGOING
        "отмен" in normalized || "закрыт" in normalized -> SEntry.CANCELLED
        "перерыв" in normalized || "хиатус" in normalized || "пауза" in normalized -> SEntry.ON_HIATUS
        // Nothing to watch and a premiere still ahead: an announced title.
        !hasPlayer && releaseDate != null && releaseDate > System.currentTimeMillis() -> SEntry.UPCOMING
        // A film that was announced has come out.
        fallback == SEntry.UPCOMING -> SEntry.UNKNOWN
        else -> fallback
    }
}
