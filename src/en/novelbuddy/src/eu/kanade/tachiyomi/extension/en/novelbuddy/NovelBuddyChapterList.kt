package eu.kanade.tachiyomi.extension.en.novelbuddy

import eu.kanade.tachiyomi.source.entry.SEntryChapter
import java.time.Instant

internal fun List<NovelBuddyChapter>.toSEntryChapters(key: NovelBuddyTitleKey): List<SEntryChapter> =
    map { it.toSEntryChapter(key.id, key.slug) }

/**
 * The newest chapters the title's details carry, when the stored chapters already reach the oldest of them: nothing
 * older changed, so the whole chapter list needn't be read. Null when it must be.
 */
internal fun NovelBuddyTitle.newestChaptersOver(
    key: NovelBuddyTitleKey,
    stored: List<SEntryChapter>,
): List<SEntryChapter>? {
    val newest = chapters.takeIf(List<NovelBuddyChapter>::isNotEmpty)?.toSEntryChapters(key) ?: return null
    // Chapters saved before names were unescaped are renamed only by a whole list
    if (stored.any { it.name != it.name.decodeNovelBuddyText() }) return null
    val storedUrls = stored.mapTo(HashSet(), SEntryChapter::url)
    return newest.takeIf { newest.last().url in storedUrls }
}

private fun NovelBuddyChapter.toSEntryChapter(titleId: String, titleSlug: String): SEntryChapter =
    SEntryChapter.create().apply {
        url = novelBuddyChapterUrl(titleId, titleSlug, id, slug)
        name = this@toSEntryChapter.name.decodeNovelBuddyText()
        chapterNumber = number ?: -1.0
        dateUpload = updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    }
