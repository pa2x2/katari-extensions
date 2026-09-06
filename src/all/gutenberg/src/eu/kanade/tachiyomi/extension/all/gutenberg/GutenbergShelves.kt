package eu.kanade.tachiyomi.extension.all.gutenberg

import eu.kanade.tachiyomi.source.entry.*
import eu.kanade.tachiyomi.source.entry.filter.*

/** Loads category choices only when the user opens the filter, with source-owned single selection. */
internal class GutenbergShelves(private val http: GutenbergHttp) : EntryFilter.PagedGroup<String>("Category", ""),
    EntryFilterMetadataProvider, EntryFilterStateSemantics {
    override val filterMetadata = EntryFilterMetadata(id = "category", legacyNames = setOf("Category"))
    override val canClearSelection = true
    override fun activeSelectionCount(value: EntryFilter<*>): Int? =
        (value as? EntryFilter.PagedGroup<*>)?.currentSelectedItemCount()
    override fun clearSelection(value: EntryFilter<*>) {
        (value as? EntryFilter.PagedGroup<*>)?.restoreEncodedState("")
    }

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
