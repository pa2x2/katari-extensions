package eu.kanade.tachiyomi.extension.en.readnovelfull

import eu.kanade.tachiyomi.source.entry.SEntryChapter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The archive's chapters newest first. Each is numbered from its name, or by its position when the name has no number,
 * and the latest one carries the release time the novel's page shows for it.
 */
internal fun List<ReadNovelFullChapter>.toNewestFirstChapters(latest: ReadNovelFullLatestChapter?): List<SEntryChapter> =
    mapIndexed { index, chapter ->
        chapter.toSEntryChapter(
            number = chapter.name.readNovelFullChapterNumber() ?: (index + 1).toDouble(),
            dateText = latest?.timeText?.takeIf { latest.chapter.url == chapter.url },
        )
    }.asReversed()

/**
 * The page's latest chapter alone, when it is already stored: nothing newer came out, so the whole archive needn't be
 * read. Null when it must be.
 */
internal fun ReadNovelFullLatestChapter.aloneOver(stored: List<SEntryChapter>): List<SEntryChapter>? {
    // Chapters saved before the list turned newest first are stored the other way round; only a whole list reorders
    // them, since Katari places a newest part before the stored chapters it doesn't list
    if (stored.isEmpty() || !stored.all(SEntryChapter::isListedNewestFirst)) return null
    val storedLatest = stored.firstOrNull { it.url == chapter.url } ?: return null
    return listOf(chapter.toSEntryChapter(number = storedLatest.chapterNumber, dateText = timeText))
}

private fun ReadNovelFullChapter.toSEntryChapter(number: Double, dateText: String?): SEntryChapter =
    SEntryChapter.create().apply {
        url = this@toSEntryChapter.url
        name = this@toSEntryChapter.name
        dateUpload = 0L
        this.dateText = dateText
        chapterNumber = number
        memo = buildJsonObject { put(LIST_ORDER_MEMO_KEY, NEWEST_FIRST) }
    }

private fun SEntryChapter.isListedNewestFirst(): Boolean = memo[LIST_ORDER_MEMO_KEY] == JsonPrimitive(NEWEST_FIRST)

private fun String.readNovelFullChapterNumber(): Double? = CHAPTER_NUMBER_REGEX.find(this)
    ?.groupValues
    ?.getOrNull(1)
    ?.toDoubleOrNull()

private val CHAPTER_NUMBER_REGEX = Regex("\\bchapter\\s+(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
private const val LIST_ORDER_MEMO_KEY = "readnovelfull.listOrder"
private const val NEWEST_FIRST = "newest-first"
