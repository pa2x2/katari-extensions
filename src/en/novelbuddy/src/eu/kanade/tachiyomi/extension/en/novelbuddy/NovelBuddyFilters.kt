package eu.kanade.tachiyomi.extension.en.novelbuddy

import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.EntryFilterList

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

internal class NovelBuddyStatusFilter : EntryFilter.Select<String>(
    name = "Status",
    values = arrayOf("All", "Ongoing", "Completed"),
)

internal class NovelBuddyContentRatingFilter : EntryFilter.Select<String>(
    name = "Content rating",
    values = arrayOf("All", "Safe", "Suggestive", "Erotica", "Pornographic"),
)

internal class NovelBuddySortFilter : EntryFilter.Select<String>(
    name = "Sort by",
    values = arrayOf("Best match", "Latest", "Popular", "Rating", "Newest", "Chapter count"),
)

internal class NovelBuddyGenreFilter : EntryFilter.Text("Genre slug")
internal class NovelBuddyAuthorFilter : EntryFilter.Text("Author")
internal class NovelBuddyMinimumChaptersFilter : EntryFilter.Text("Minimum chapters")
internal class NovelBuddyMaximumChaptersFilter : EntryFilter.Text("Maximum chapters")

internal fun novelBuddyFilterList(): EntryFilterList = EntryFilterList(
    NovelBuddyStatusFilter(),
    NovelBuddyContentRatingFilter(),
    NovelBuddySortFilter(),
    EntryFilter.Separator(),
    NovelBuddyGenreFilter(),
    NovelBuddyAuthorFilter(),
    NovelBuddyMinimumChaptersFilter(),
    NovelBuddyMaximumChaptersFilter(),
)

internal fun EntryFilterList.toNovelBuddySearchSelection(query: String): NovelBuddySearchSelection =
    NovelBuddySearchSelection(
        query = query.trim().ifBlank { null },
        status = selectValue<NovelBuddyStatusFilter>(STATUS_VALUES),
        contentRating = selectValue<NovelBuddyContentRatingFilter>(CONTENT_RATING_VALUES),
        genre = textValue<NovelBuddyGenreFilter>(),
        author = textValue<NovelBuddyAuthorFilter>(),
        minimumChapters = textValue<NovelBuddyMinimumChaptersFilter>()?.toIntOrNull()?.coerceAtLeast(0),
        maximumChapters = textValue<NovelBuddyMaximumChaptersFilter>()?.toIntOrNull()?.coerceAtLeast(0),
        sort = filterIsInstance<NovelBuddySortFilter>().firstOrNull()?.let {
            SORT_VALUES.getOrElse(it.state) { SORT_VALUES.first() }
        },
    )

private inline fun <reified T : EntryFilter.Select<String>> EntryFilterList.selectValue(values: List<String?>): String? =
    filterIsInstance<T>().firstOrNull()?.let { values.getOrElse(it.state) { values.first() } }

private inline fun <reified T : EntryFilter.Text> EntryFilterList.textValue(): String? =
    filterIsInstance<T>().firstOrNull()?.state?.trim()?.ifBlank { null }

internal const val LATEST_SORT = "latest"
internal const val POPULAR_SORT = "popular"
private val STATUS_VALUES = listOf(null, "ongoing", "completed")
private val CONTENT_RATING_VALUES = listOf(null, "safe", "suggestive", "erotica", "pornographic")
private val SORT_VALUES = listOf(null, LATEST_SORT, POPULAR_SORT, "rating", "newest", "chapters")
