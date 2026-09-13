package eu.kanade.tachiyomi.extension.en.brightnovels

import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.EntryFilterList
import eu.kanade.tachiyomi.source.entry.filter.*

internal abstract class BrightNovelsChoice(
    name: String,
    id: String,
    labels: Array<String>,
    optionIds: List<String>,
    legacyOptions: List<String>,
    role: EntryFilterRole = EntryFilterRole.CONSTRAINT,
    neutralOptionId: String? = "any",
) : EntryFilter.Select<String>(name, labels), EntryFilterMetadataProvider {
    override val filterMetadata = EntryFilterMetadata(
        id = id, optionIds = optionIds, legacyNames = setOf(name), legacyOptionIds = legacyOptions,
        neutralOptionId = neutralOptionId, role = role,
    )
}

internal class BrightNovelsGenreModeFilter : BrightNovelsChoice(
    "Genre match", "genre_mode", arrayOf("Or", "And"),
    listOf("or", "and"), listOf("or", "and"),
    neutralOptionId = null,
)

internal class BrightNovelsCountryFilter : BrightNovelsChoice(
    "Country", "country", arrayOf("All", "China", "Japan", "Korea"),
    listOf(null, "china", "japan", "korea").map { it ?: "any" },
    listOf("any", "china", "japan", "korea"),
)

internal class BrightNovelsStatusFilter : BrightNovelsChoice(
    "Status", "status", arrayOf("All", "Ongoing", "Completed", "Hiatus", "Cancelled"),
    listOf(null, "ongoing", "completed", "hiatus", "cancelled").map { it ?: "any" },
    listOf("any", "ongoing", "completed", "hiatus", "cancelled"),
)

internal class BrightNovelsSortFilter : BrightNovelsChoice(
    "Sort by", "sort", arrayOf("Default", "Recently Updated", "Title", "Trending"),
    listOf(null, "updated_at", "title", "trending").map { it ?: "any" },
    listOf("any", "updated_at", "title", "trending"),
    EntryFilterRole.ORDERING,
)

internal class BrightNovelsOrderFilter : BrightNovelsChoice(
    "Order", "order", arrayOf("Descending", "Ascending"),
    listOf("desc", "asc"), listOf("desc", "asc"),
    EntryFilterRole.ORDERING,
    neutralOptionId = null,
)

internal open class BrightNovelsTextFilter(name: String, id: String, description: String? = null) :
    EntryFilter.Text(name), EntryFilterMetadataProvider, EntryFilterStateSemantics {
    override val filterMetadata = EntryFilterMetadata(id = id, legacyNames = setOf(name), description = description)
    override val canClearSelection = true
    override fun activeSelectionCount(value: EntryFilter<*>): Int? =
        (value as? EntryFilter.Text)?.let { if (it.state.isBlank()) 0 else 1 }
    override fun clearSelection(value: EntryFilter<*>) { (value as? EntryFilter.Text)?.state = "" }
}

internal class BrightNovelsGenreFilter : BrightNovelsTextFilter(
    "Genre slugs", "genres", "Comma-separated genre slugs, e.g. action,adventure.",
)

internal class BrightNovelsTagFilter : BrightNovelsTextFilter(
    "Tag slugs", "tags", "Comma-separated tag slugs, e.g. cultivation.",
)

internal fun brightNovelsFilterList(): EntryFilterList = EntryFilterList(
    BrightNovelsGenreFilter(),
    BrightNovelsGenreModeFilter(),
    BrightNovelsTagFilter(),
    BrightNovelsCountryFilter(),
    BrightNovelsStatusFilter(),
    BrightNovelsSortFilter(),
    BrightNovelsOrderFilter(),
)

internal fun EntryFilterList.toBrightNovelsParams(): Map<String, String> {
    requireValidFilters()
    val filters = flatMap { if (it is EntryFilter.Group<*>) it.state.filterIsInstance<EntryFilter<*>>() else listOf(it) }
    return buildMap {
        filters.textValue<BrightNovelsGenreFilter>()?.let { put("genres", it) }
        put("genre_mode", filters.selectValue<BrightNovelsGenreModeFilter>(GENRE_MODE_VALUES) ?: "or")
        filters.textValue<BrightNovelsTagFilter>()?.let { put("tags", it) }
        filters.selectValue<BrightNovelsCountryFilter>(COUNTRY_VALUES)?.let { put("country", it) }
        filters.selectValue<BrightNovelsStatusFilter>(STATUS_VALUES)?.let { put("status", it) }
        filters.selectValue<BrightNovelsSortFilter>(SORT_VALUES)?.let { put("sort", it) }
        put("order", filters.selectValue<BrightNovelsOrderFilter>(ORDER_VALUES) ?: "desc")
    }
}

private inline fun <reified T : EntryFilter.Select<String>> List<EntryFilter<*>>.selectValue(values: List<String?>): String? =
    filterIsInstance<T>().firstOrNull()?.let { values[it.state] }

private inline fun <reified T : EntryFilter.Text> List<EntryFilter<*>>.textValue(): String? =
    filterIsInstance<T>().firstOrNull()?.state?.trim()?.ifBlank { null }

private val GENRE_MODE_VALUES = listOf("or", "and")
private val COUNTRY_VALUES = listOf(null, "china", "japan", "korea")
private val STATUS_VALUES = listOf(null, "ongoing", "completed", "hiatus", "cancelled")
private val SORT_VALUES = listOf(null, "updated_at", "title", "trending")
private val ORDER_VALUES = listOf("desc", "asc")
