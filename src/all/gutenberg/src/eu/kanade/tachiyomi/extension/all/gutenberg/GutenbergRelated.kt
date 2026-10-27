package eu.kanade.tachiyomi.extension.all.gutenberg

import eu.kanade.tachiyomi.source.entry.RelatedEntryGroup
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the listings the book page links as related, each under the listing's own heading. A listing that fails is left
 * out so one busy shelf doesn't hide the rest; only when every listing fails is the first failure reported.
 */
internal suspend fun GutenbergHttp.relatedGroups(book: GutenbergBook): List<RelatedEntryGroup> {
    var failure: Exception? = null
    val groups = book.relatedPaths.mapNotNull { path ->
        try {
            val page = document(path)
            val entries = page.gutenbergListing().items.filter { it.url != book.entry.url }
            entries.takeIf { it.isNotEmpty() }?.let { RelatedEntryGroup(page.gutenbergHeading(), it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (failure == null) failure = e
            null
        }
    }
    if (groups.isEmpty()) failure?.let { throw it }
    return groups
}
