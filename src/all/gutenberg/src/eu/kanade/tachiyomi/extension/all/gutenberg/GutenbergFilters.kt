package eu.kanade.tachiyomi.extension.all.gutenberg

import eu.kanade.tachiyomi.source.entry.*
import eu.kanade.tachiyomi.source.entry.filter.*
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class GutenbergTextFilter(name: String, val prefix: String) :
    EntryFilter.Text(name), EntryFilterMetadataProvider, EntryFilterStateSemantics {
    override val filterMetadata = EntryFilterMetadata(id = "text.$prefix", legacyNames = setOf(name))
    override val canClearSelection = true
    override fun activeSelectionCount(value: EntryFilter<*>): Int? =
        (value as? EntryFilter.Text)?.let { if (it.state.isBlank()) 0 else 1 }
    override fun clearSelection(value: EntryFilter<*>) { (value as? EntryFilter.Text)?.state = "" }
}

internal class GutenbergSort : EntryFilter.Select<String>(
    "Sort", arrayOf("Popularity", "Release date", "Title"),
), EntryFilterMetadataProvider {
    override val filterMetadata = EntryFilterMetadata(
        id = "sort", role = EntryFilterRole.ORDERING,
        optionIds = listOf("downloads", "release_date", "title"),
        legacyNames = setOf("Sort"), legacyOptionIds = listOf("downloads", "release_date", "title"),
    )
    val value: String get() = filterMetadata.optionIds[state]
}

internal fun gutenbergFilters(http: GutenbergHttp): EntryFilterList = EntryFilterList(
    GutenbergSort(), GutenbergSearchFilters(http),
)

internal class GutenbergSearchFilters(http: GutenbergHttp) : EntryFilter.Group<EntryFilter<*>>(
    "Search filters",
    listOf(
        GutenbergTextFilter("Author", "a"),
        GutenbergTextFilter("Title", "t"),
        GutenbergTextFilter("Subject", "s"),
        GutenbergTextFilter("Language code (e.g. en, fr, pl)", "l"),
        GutenbergTextFilter("Library of Congress class (e.g. PR)", "lcc"),
        GutenbergShelves(http),
    ),
), EntryFilterMetadataProvider, EntryFilterValidator, EntryFilterGroupSummary {
    override val filterMetadata = EntryFilterMetadata(
        id = "search", description = "Browse a category with the search text and text filters cleared.",
    )

    override fun validateFilter(values: List<EntryFilter<*>>): List<EntryFilterValidationIssue> {
        val category = values.filterIsInstance<EntryFilter.PagedGroup<*>>().firstOrNull()
        if (category == null || category.encodeCurrentState().isEmpty()) return emptyList()
        val text = values.filterIsInstance<EntryFilter.Text>().filter { it.state.isNotBlank() }
        if (text.isEmpty()) return emptyList()
        return listOf(EntryFilterValidationIssue(
            text.mapNotNull { (it as? EntryFilterMetadataProvider)?.filterMetadata?.id }.toSet() + "category",
            EntryFilterValidationCode.SOURCE, "Clear text filters to browse a category.",
        ))
    }

    override fun selectionSummary(values: List<EntryFilter<*>>): String? = buildList {
        values.filterIsInstance<EntryFilter.Text>().filter { it.state.isNotBlank() }.forEach {
            add("${it.name}: ${it.state.trim()}")
        }
        if (values.filterIsInstance<EntryFilter.PagedGroup<*>>().any { it.currentSelectedItemCount() > 0 }) {
            add("Category selected")
        }
    }.joinToString().ifBlank { null }
}

internal fun gutenbergSearchUrl(page: Int, query: String, filters: EntryFilterList): String {
    require(page > 0) { "Page must be positive" }
    filters.requireValidFilters()
    val fields = filters.flatMap { if (it is EntryFilter.Group<*>) it.state.filterIsInstance<EntryFilter<*>>() else listOf(it) }
    val terms = buildList {
        if (query.isNotBlank()) add(query.trim())
        fields.filterIsInstance<GutenbergTextFilter>().forEach { filter ->
            // Gutenberg prefixes apply to individual words, including multi-word author/subject searches.
            filter.state.trim().split(Regex("\\s+")).filter(String::isNotBlank).forEach {
                add("${filter.prefix}.$it")
            }
        }
    }
    val shelf = fields.filterIsInstance<GutenbergShelves>().firstOrNull()?.state.orEmpty()
    require(shelf.isEmpty() || terms.isEmpty()) { "Clear the search text and text filters to browse a category." }
    val path = if (shelf.isEmpty()) "/ebooks/search/" else "/ebooks/bookshelf/$shelf"
    return gutenbergUrl(path).toHttpUrl().newBuilder()
        .apply { if (terms.isNotEmpty()) addQueryParameter("query", terms.joinToString(" ")) }
        .addQueryParameter("sort_order", filters.filterIsInstance<GutenbergSort>().firstOrNull()?.value ?: "downloads")
        .addQueryParameter("start_index", (1L + (page - 1L) * 25).toString())
        .build().toString()
}
