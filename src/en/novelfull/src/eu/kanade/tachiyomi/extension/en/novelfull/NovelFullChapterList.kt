package eu.kanade.tachiyomi.extension.en.novelfull

import eu.kanade.tachiyomi.source.entry.SEntryChapter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The chapter selector's list newest first, each numbered by its position like the selector orders them. */
internal fun List<NovelFullChapterLink>.toNewestFirstChapters(): List<SEntryChapter> =
    mapIndexed { index, link -> link.toSEntryChapter(position = index + 1) }.asReversed()

/**
 * The page's "Latest Chapters" box alone, when the stored chapters already hold its oldest chapter at the same
 * position: nothing older changed, so the whole chapter selector needn't be read. Null when it must be.
 */
internal fun NovelFullDetails.newestChaptersOver(stored: List<SEntryChapter>): List<SEntryChapter>? {
    val total = totalChapters ?: return null
    val oldest = latestChapters.lastOrNull() ?: return null
    // Chapters saved before the list turned newest first are stored the other way round; only a whole list reorders
    // them, since Katari places a newest part before the stored chapters it doesn't list
    if (stored.isEmpty() || !stored.all(SEntryChapter::isListedNewestFirst)) return null
    val oldestPosition = total - latestChapters.lastIndex
    val storedOldest = stored.firstOrNull { it.url == oldest.url } ?: return null
    if (storedOldest.chapterNumber != oldestPosition.toDouble()) return null
    return latestChapters.mapIndexed { index, link -> link.toSEntryChapter(position = total - index) }
}

private fun NovelFullChapterLink.toSEntryChapter(position: Int): SEntryChapter = SEntryChapter.create().apply {
    url = this@toSEntryChapter.url
    name = this@toSEntryChapter.name
    dateUpload = 0L
    chapterNumber = position.toDouble()
    memo = buildJsonObject { put(LIST_ORDER_MEMO_KEY, NEWEST_FIRST) }
}

private fun SEntryChapter.isListedNewestFirst(): Boolean = memo[LIST_ORDER_MEMO_KEY] == JsonPrimitive(NEWEST_FIRST)

private const val LIST_ORDER_MEMO_KEY = "novelfull.listOrder"
private const val NEWEST_FIRST = "newest-first"
