package eu.kanade.tachiyomi.extension.en.novelbuddy

import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.SEntry
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.time.Instant

@Serializable
internal data class NovelBuddySearchResponse(val data: NovelBuddySearchData)

@Serializable
internal data class NovelBuddySearchData(
    val items: List<NovelBuddyTitle>,
    val pagination: NovelBuddyPagination,
)

@Serializable
internal data class NovelBuddyTitleResponse(val data: NovelBuddyTitleData)

@Serializable
internal data class NovelBuddyTitleData(val title: NovelBuddyTitle)

@Serializable
internal data class NovelBuddyChaptersResponse(val data: NovelBuddyChaptersData)

@Serializable
internal data class NovelBuddyChaptersData(val chapters: List<NovelBuddyChapter>)

@Serializable
internal data class NovelBuddyChapterResponse(val data: NovelBuddyChapterData)

@Serializable
internal data class NovelBuddyChapterData(val chapter: NovelBuddyChapterContent)

@Serializable
internal data class NovelBuddyTitle(
    val id: String,
    val name: String,
    val slug: String,
    val cover: String? = null,
    val summary: String? = null,
    val status: String? = null,
    val genres: List<NovelBuddyNamedValue> = emptyList(),
    val tags: List<NovelBuddyNamedValue> = emptyList(),
    val authors: List<NovelBuddyNamedValue> = emptyList(),
)

@Serializable
internal data class NovelBuddyNamedValue(val name: String)

@Serializable
internal data class NovelBuddyPagination(
    @SerialName("has_next") val hasNext: Boolean = false,
)

@Serializable
internal data class NovelBuddyChapter(
    val id: String,
    val name: String,
    val slug: String,
    val number: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val cv: Long? = null,
    val content: String? = null,
)

internal typealias NovelBuddyChapterContent = NovelBuddyChapter

internal fun NovelBuddyTitle.toSEntry(): SEntry = SEntry.create().apply {
    url = titleUrl(id, slug)
    title = name
    author = authors.map(NovelBuddyNamedValue::name)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString()
        .ifBlank { null }
    description = summary?.let(Jsoup::parse)?.text()?.trim()?.ifBlank { null }
    genre = (genres + tags).map(NovelBuddyNamedValue::name)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .takeIf(List<String>::isNotEmpty)
    status = when (this@toSEntry.status?.lowercase()?.replace(" ", "")) {
        "ongoing" -> SEntry.ONGOING
        "completed", "complete" -> SEntry.COMPLETED
        else -> SEntry.UNKNOWN
    }
    thumbnailUrl = cover
    type = EntryType.BOOK
}

internal fun NovelBuddyChapter.toSEntryChapter(titleId: String, titleSlug: String): SEntryChapter =
    SEntryChapter.create().apply {
        url = novelBuddyChapterUrl(titleId, titleSlug, id, slug)
        name = this@toSEntryChapter.name
        chapterNumber = number ?: -1.0
        dateUpload = updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    }

internal fun titleUrl(id: String, slug: String): String = "/titles/$id-$slug"

internal fun novelBuddyChapterUrl(
    titleId: String,
    titleSlug: String,
    chapterId: String,
    chapterSlug: String,
): String = "novelbuddy:chapter/$titleId/$titleSlug/$chapterId/$chapterSlug"

internal data class NovelBuddyTitleKey(val id: String, val slug: String)

internal fun String.toNovelBuddyTitleKey(): NovelBuddyTitleKey? {
    val match = TITLE_URL.matchEntire(this) ?: return null
    return NovelBuddyTitleKey(match.groupValues[1], match.groupValues[2])
}

internal data class NovelBuddyChapterKey(
    val titleId: String,
    val titleSlug: String,
    val chapterId: String,
    val chapterSlug: String,
)

internal fun String.toNovelBuddyChapterKey(): NovelBuddyChapterKey? {
    val match = CHAPTER_URL.matchEntire(this) ?: return null
    return NovelBuddyChapterKey(
        titleId = match.groupValues[1],
        titleSlug = match.groupValues[2],
        chapterId = match.groupValues[3],
        chapterSlug = match.groupValues[4],
    )
}

internal fun String.toNovelBuddyContentWebUrl(baseUrl: String): String {
    val key = toNovelBuddyTitleKey() ?: error("Invalid NovelBuddy BOOK identity: $this")
    return "$baseUrl/${key.slug}"
}

internal fun String.toNovelBuddyChildWebUrl(baseUrl: String): String {
    val key = toNovelBuddyChapterKey() ?: error("Invalid NovelBuddy chapter identity: $this")
    return "$baseUrl/${key.titleSlug}/${key.chapterSlug}"
}

private val TITLE_URL = Regex("^/titles/([^/-]+)-(.+)$")
private val CHAPTER_URL = Regex("^novelbuddy:chapter/([^/]+)/([^/]+)/([^/]+)/([^/]+)$")
