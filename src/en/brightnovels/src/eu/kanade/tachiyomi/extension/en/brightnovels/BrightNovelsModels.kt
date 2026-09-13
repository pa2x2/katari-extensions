package eu.kanade.tachiyomi.extension.en.brightnovels

import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.SEntry
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import java.time.OffsetDateTime

@Serializable
internal data class BrightNovelsInertiaPage(
    val component: String = "",
    val props: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class BrightNovelsSeriesListPage(
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1,
    val data: List<BrightNovelsSeriesItem> = emptyList(),
)

@Serializable
internal data class BrightNovelsSeriesItem(
    val id: Long,
    val title: String,
    val slug: String,
    val description: String? = null,
    @SerialName("story_state") val storyState: String? = null,
    val cover: BrightNovelsCover? = null,
    @SerialName("coverImage") val coverImage: String? = null,
    val genres: List<BrightNovelsNamedSlug> = emptyList(),
    val tags: List<BrightNovelsNamedSlug> = emptyList(),
)

@Serializable
internal data class BrightNovelsSeriesDetail(
    val id: Long,
    val title: String,
    val slug: String,
    val description: String? = null,
    @SerialName("story_state") val storyState: String? = null,
    val cover: BrightNovelsCover? = null,
    val genres: List<BrightNovelsNamedSlug> = emptyList(),
    val tags: List<BrightNovelsNamedSlug> = emptyList(),
)

@Serializable
internal data class BrightNovelsCover(val url: String? = null)

@Serializable
internal data class BrightNovelsNamedSlug(val name: String)

@Serializable
internal data class BrightNovelsChapterEntry(
    val id: Long,
    val slug: String,
    val name: String,
    val title: String? = null,
    val number: Double? = null,
    val price: Int = 0,
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("index_at") val indexAt: String? = null,
)

@Serializable
internal data class BrightNovelsChaptersResponse(
    val chapters: List<BrightNovelsChapterEntry> = emptyList(),
    @SerialName("unlockedChapterIds") val unlockedChapterIds: List<Long> = emptyList(),
)

@Serializable
internal data class BrightNovelsChapterContent(
    val id: Long,
    val title: String? = null,
    val slug: String? = null,
    val number: Double? = null,
    val content: String? = null,
    val price: Int = 0,
    @SerialName("is_premium") val isPremium: Boolean = false,
)

internal data class BrightNovelsChapterShow(
    val chapter: BrightNovelsChapterContent,
    val unlocked: Boolean,
)

@Serializable
internal data class BrightNovelsSearchResponse(
    val data: BrightNovelsSearchData = BrightNovelsSearchData(),
)

@Serializable
internal data class BrightNovelsSearchData(
    val series: List<BrightNovelsSeriesItem> = emptyList(),
)

internal fun BrightNovelsSeriesItem.toSEntry(): SEntry = SEntry.create().apply {
    url = brightNovelsEntryUrl(slug)
    title = this@toSEntry.title
    description = this@toSEntry.description?.let(Jsoup::parse)?.text()?.trim()?.ifBlank { null }
    genre = (genres.map(BrightNovelsNamedSlug::name) + tags.map(BrightNovelsNamedSlug::name))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .takeIf(List<String>::isNotEmpty)
    status = this@toSEntry.storyState.toBrightNovelsStatus()
    thumbnailUrl = (cover?.url ?: coverImage).toAbsoluteUrl()
    type = EntryType.BOOK
}

internal fun BrightNovelsSeriesDetail.applyTo(entry: SEntry): SEntry = entry.copy().apply {
    url = brightNovelsEntryUrl(slug)
    title = this@applyTo.title
    description = this@applyTo.description?.let(Jsoup::parse)?.text()?.trim()?.ifBlank { null }
    genre = (genres.map(BrightNovelsNamedSlug::name) + tags.map(BrightNovelsNamedSlug::name))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .takeIf(List<String>::isNotEmpty)
    status = this@applyTo.storyState.toBrightNovelsStatus()
    cover?.url.toAbsoluteUrl()?.let { thumbnailUrl = it }
    type = EntryType.BOOK
    initialized = true
}

internal fun BrightNovelsChapterEntry.toSEntryChapter(seriesSlug: String): SEntryChapter =
    SEntryChapter.create().apply {
        url = brightNovelsChapterUrl(seriesSlug, slug)
        name = if (isPremium) {
            LOCKED_CHAPTER_PREFIX + this@toSEntryChapter.name
        } else {
            this@toSEntryChapter.name
        }
        chapterNumber = number ?: -1.0
        dateUpload = indexAt.toEpochMillis()
        memo = kotlinx.serialization.json.buildJsonObject {
            put(CHAPTER_ID_MEMO_KEY, id)
            put(CHAPTER_PREMIUM_MEMO_KEY, isPremium)
            put(CHAPTER_PRICE_MEMO_KEY, price)
        }
    }

internal fun brightNovelsEntryUrl(slug: String): String = "/series/$slug"

internal fun brightNovelsChapterUrl(seriesSlug: String, chapterSlug: String): String =
    "/series/$seriesSlug/$chapterSlug"

internal data class BrightNovelsEntryKey(val slug: String)

internal fun String.toBrightNovelsEntryKey(): BrightNovelsEntryKey? {
    val match = ENTRY_URL.matchEntire(trim()) ?: return null
    return BrightNovelsEntryKey(match.groupValues[1])
}

internal data class BrightNovelsChapterKey(val seriesSlug: String, val chapterSlug: String)

internal fun String.toBrightNovelsChapterKey(): BrightNovelsChapterKey? {
    val trimmed = trim()
    if (!trimmed.startsWith("/series/")) return null
    val rest = trimmed.removePrefix("/series/")
    val slash = rest.indexOf('/')
    if (slash <= 0 || slash == rest.length - 1) return null
    return BrightNovelsChapterKey(rest.substring(0, slash), rest.substring(slash + 1))
}

internal fun String?.toAbsoluteUrl(): String? {
    if (this.isNullOrBlank()) return null
    val value = trim()
    return when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("/") -> BRIGHTNOVELS_URL + value
        else -> "$BRIGHTNOVELS_URL/$value"
    }
}

internal fun String?.toEpochMillis(): Long {
    if (this.isNullOrBlank()) return 0L
    return runCatching { OffsetDateTime.parse(trim()).toInstant().toEpochMilli() }.getOrNull() ?: 0L
}

internal fun String?.toBrightNovelsStatus(): Int = when (this?.lowercase()) {
    "ongoing" -> SEntry.ONGOING
    "completed", "complete" -> SEntry.COMPLETED
    "hiatus" -> SEntry.ON_HIATUS
    "cancelled", "canceled" -> SEntry.CANCELLED
    else -> SEntry.UNKNOWN
}

private val ENTRY_URL = Regex("^/series/([^/]+)$")

internal const val LOCKED_CHAPTER_PREFIX = "🔒 "

private const val CHAPTER_ID_MEMO_KEY = "brightnovels.chapterId"
private const val CHAPTER_PREMIUM_MEMO_KEY = "brightnovels.premium"
private const val CHAPTER_PRICE_MEMO_KEY = "brightnovels.price"
