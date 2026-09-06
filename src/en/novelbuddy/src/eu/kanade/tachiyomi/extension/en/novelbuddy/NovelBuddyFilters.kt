package eu.kanade.tachiyomi.extension.en.novelbuddy

import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.EntryFilterList
import eu.kanade.tachiyomi.source.entry.filter.*

internal data class NovelBuddySearchSelection(
    val query: String? = null,
    val status: String? = null,
    val contentRating: String? = null,
    val genre: String? = null,
    val author: String? = null,
    val minimumChapters: Int? = null,
    val maximumChapters: Int? = null,
    val sort: String? = null,
)

internal abstract class NovelBuddyChoice(
    name: String,
    id: String,
    labels: Array<String>,
    optionIds: List<String>,
    legacyOptions: List<String>,
    role: EntryFilterRole = EntryFilterRole.CONSTRAINT,
) : EntryFilter.Select<String>(name, labels), EntryFilterMetadataProvider {
    override val filterMetadata = EntryFilterMetadata(
        id = id, optionIds = optionIds, legacyNames = setOf(name), legacyOptionIds = legacyOptions,
        neutralOptionId = "any", role = role,
    )
}

internal class NovelBuddyStatusFilter : NovelBuddyChoice(
    "Status", "status", arrayOf("All", "Ongoing", "Completed"),
    STATUS_VALUES.map { it ?: "any" }, listOf("any", "ongoing", "completed"),
)

internal class NovelBuddyContentRatingFilter : NovelBuddyChoice(
    "Content rating", "content_rating", arrayOf("All", "Safe", "Suggestive", "Erotica", "Pornographic"),
    CONTENT_RATING_VALUES.map { it ?: "any" }, listOf("any", "safe", "suggestive", "erotica", "pornographic"),
)

internal class NovelBuddySortFilter : NovelBuddyChoice(
    "Sort by", "sort", arrayOf("Best match", "Latest", "Popular", "Rating", "Newest", "Chapter count"),
    SORT_VALUES.map { it ?: "any" }, listOf("any", "latest", "popular", "rating", "newest", "chapters"),
    EntryFilterRole.ORDERING,
)

internal open class NovelBuddyTextFilter(name: String, id: String, description: String? = null) :
    EntryFilter.Text(name), EntryFilterMetadataProvider, EntryFilterStateSemantics {
    override val filterMetadata = EntryFilterMetadata(id = id, legacyNames = setOf(name), description = description)
    override val canClearSelection = true
    override fun activeSelectionCount(value: EntryFilter<*>): Int? =
        (value as? EntryFilter.Text)?.let { if (it.state.isBlank()) 0 else 1 }
    override fun clearSelection(value: EntryFilter<*>) { (value as? EntryFilter.Text)?.state = "" }
}

internal class NovelBuddyGenreFilter : NovelBuddyTextFilter("Genre slug", "genre")
internal class NovelBuddyAuthorFilter : NovelBuddyTextFilter("Author", "author")

internal fun novelBuddyFilterList(): EntryFilterList = EntryFilterList(
    NovelBuddyStatusFilter(),
    NovelBuddyContentRatingFilter(),
    NovelBuddySortFilter(),
    EntryFilter.Separator(),
    NovelBuddyGenreFilter(),
    NovelBuddyAuthorFilter(),
    NovelBuddyChapterCountFilter(),
)

internal fun EntryFilterList.toNovelBuddySearchSelection(query: String): NovelBuddySearchSelection {
    requireValidFilters()
    val filters = flatMap { if (it is EntryFilter.Group<*>) it.state.filterIsInstance<EntryFilter<*>>() else listOf(it) }
    return NovelBuddySearchSelection(
        query = query.trim().ifBlank { null },
        status = filters.selectValue<NovelBuddyStatusFilter>(STATUS_VALUES),
        contentRating = filters.selectValue<NovelBuddyContentRatingFilter>(CONTENT_RATING_VALUES),
        genre = filters.textValue<NovelBuddyGenreFilter>(),
        author = filters.textValue<NovelBuddyAuthorFilter>(),
        minimumChapters = filters.textValue<NovelBuddyMinimumChaptersFilter>()?.toInt(),
        maximumChapters = filters.textValue<NovelBuddyMaximumChaptersFilter>()?.toInt(),
        sort = filters.selectValue<NovelBuddySortFilter>(SORT_VALUES),
    )
}

private inline fun <reified T : EntryFilter.Select<String>> List<EntryFilter<*>>.selectValue(values: List<String?>): String? =
    filterIsInstance<T>().firstOrNull()?.let { values[it.state] }

private inline fun <reified T : EntryFilter.Text> List<EntryFilter<*>>.textValue(): String? =
    filterIsInstance<T>().firstOrNull()?.state?.trim()?.ifBlank { null }

internal const val LATEST_SORT = "latest"
internal const val POPULAR_SORT = "popular"
private val STATUS_VALUES = listOf(null, "ongoing", "completed")
private val CONTENT_RATING_VALUES = listOf(null, "safe", "suggestive", "erotica", "pornographic")
private val SORT_VALUES = listOf(null, LATEST_SORT, POPULAR_SORT, "rating", "newest", "chapters")
