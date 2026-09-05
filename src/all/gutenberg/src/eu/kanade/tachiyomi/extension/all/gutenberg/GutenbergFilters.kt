package eu.kanade.tachiyomi.extension.all.gutenberg

import eu.kanade.tachiyomi.source.entry.*
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class GutenbergTextFilter(name: String, val prefix: String) : EntryFilter.Text(name)
internal class GutenbergSort : EntryFilter.Select<String>(
    "Sort", arrayOf("Popularity", "Release date", "Title"),
) {
    val value: String get() = listOf("downloads", "release_date", "title")[state]
}

internal fun gutenbergFilters(http: GutenbergHttp): EntryFilterList = EntryFilterList(
    GutenbergSort(),
    GutenbergTextFilter("Author", "a"),
    GutenbergTextFilter("Title", "t"),
    GutenbergTextFilter("Subject", "s"),
    GutenbergTextFilter("Language code (e.g. en, fr, pl)", "l"),
    GutenbergTextFilter("Library of Congress class (e.g. PR)", "lcc"),
    EntryFilter.Header("Browse a category with text searches cleared"),
    GutenbergShelves(http),
)

internal fun gutenbergSearchUrl(page: Int, query: String, filters: EntryFilterList): String {
    require(page > 0) { "Page must be positive" }
    val terms = buildList {
        if (query.isNotBlank()) add(query.trim())
        filters.filterIsInstance<GutenbergTextFilter>().forEach { filter ->
            // Gutenberg prefixes apply to individual words, including multi-word author/subject searches.
            filter.state.trim().split(Regex("\\s+")).filter(String::isNotBlank).forEach {
                add("${filter.prefix}.$it")
            }
        }
    }
    val shelf = filters.filterIsInstance<GutenbergShelves>().firstOrNull()?.state.orEmpty()
    require(shelf.isEmpty() || terms.isEmpty()) { "Clear the search text and text filters to browse a category." }
    val path = if (shelf.isEmpty()) "/ebooks/search/" else "/ebooks/bookshelf/$shelf"
    return gutenbergUrl(path).toHttpUrl().newBuilder()
        .apply { if (terms.isNotEmpty()) addQueryParameter("query", terms.joinToString(" ")) }
        .addQueryParameter("sort_order", filters.filterIsInstance<GutenbergSort>().firstOrNull()?.value ?: "downloads")
        .addQueryParameter("start_index", (1L + (page - 1L) * 25).toString())
        .build().toString()
}

/** Loads category choices only when the user opens the filter, with source-owned single selection. */
internal class GutenbergShelves(private val http: GutenbergHttp) : EntryFilter.PagedGroup<String>("Category", "") {
    override suspend fun getPage(request: EntryFilterPageRequest): EntryFilterPage {
        val choices = http.document("/ebooks/categories").select("a[href*=/ebooks/bookshelf/]")
            .mapNotNull { link ->
                val id = Regex("/ebooks/bookshelf/([0-9]+)").find(link.attr("href"))?.groupValues?.get(1)
                    ?: return@mapNotNull null
                EntryFilterPageItem(id, link.text())
            }.distinctBy { it.id }.filter {
                (request.scope != EntryFilterPageScope.SELECTED || it.id == state) &&
                    (request.query.isNullOrBlank() || it.label.contains(request.query.orEmpty(), ignoreCase = true))
            }
        val start = request.continuationToken?.toIntOrNull()?.coerceIn(0, choices.size) ?: 0
        val end = (start + request.requestedSize).coerceAtMost(choices.size)
        return EntryFilterPage(choices.subList(start, end), end.takeIf { it < choices.size }?.toString())
    }

    override fun projectItem(item: EntryFilterPageItem, previous: EntryFilter<*>?): EntryFilter<*> =
        object : EntryFilter.CheckBox(item.label, state == item.id) {}

    override fun reduceItemUpdate(item: EntryFilterPageItem, updatedFilter: EntryFilter<*>): String =
        if ((updatedFilter as EntryFilter.CheckBox).state) item.id else if (state == item.id) "" else state

    override fun selectedItemCount(state: String): Int = if (state.isEmpty()) 0 else 1
    override fun encodeState(state: String): String = state
    override fun decodeState(value: String): String? = value.takeIf { it.isEmpty() || it.matches(Regex("[0-9]+")) }
}
