package eu.kanade.tachiyomi.extension.en.novelbuddy

import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.filter.*

internal class NovelBuddyMinimumChaptersFilter : NovelBuddyChapterBound("Minimum chapters", "chapters.minimum")
internal class NovelBuddyMaximumChaptersFilter : NovelBuddyChapterBound("Maximum chapters", "chapters.maximum")

internal abstract class NovelBuddyChapterBound(name: String, id: String) :
    NovelBuddyTextFilter(name, id, "A nonnegative whole number. Leave blank for no limit."), EntryFilterValidator {
    override fun validateFilter(values: List<EntryFilter<*>>): List<EntryFilterValidationIssue> {
        val text = (values.singleOrNull() as? EntryFilter.Text)?.state?.trim() ?: state.trim()
        if (text.isEmpty() || (text.all { it in '0'..'9' } && text.toIntOrNull() != null)) return emptyList()
        return listOf(EntryFilterValidationIssue(
            setOfNotNull(filterMetadata.id), EntryFilterValidationCode.SOURCE,
            "$name must be a nonnegative whole number no greater than ${Int.MAX_VALUE}.",
        ))
    }
}

internal class NovelBuddyChapterCountFilter : EntryFilter.Group<EntryFilter<*>>(
    "Chapter count", listOf(NovelBuddyMinimumChaptersFilter(), NovelBuddyMaximumChaptersFilter()),
), EntryFilterMetadataProvider, EntryFilterValidator, EntryFilterGroupSummary {
    override val filterMetadata = EntryFilterMetadata(id = "chapters")

    override fun validateFilter(values: List<EntryFilter<*>>): List<EntryFilterValidationIssue> {
        val minimum = values.bound("chapters.minimum")?.toIntOrNull() ?: return emptyList()
        val maximum = values.bound("chapters.maximum")?.toIntOrNull() ?: return emptyList()
        return if (minimum > maximum) listOf(EntryFilterValidationIssue(
            setOf("chapters.minimum", "chapters.maximum"), EntryFilterValidationCode.SOURCE,
            "Minimum chapters must not exceed maximum chapters.",
        )) else emptyList()
    }

    override fun selectionSummary(values: List<EntryFilter<*>>): String? {
        val minimum = values.bound("chapters.minimum")
        val maximum = values.bound("chapters.maximum")
        return when {
            minimum != null && maximum != null -> "$minimum–$maximum chapters"
            minimum != null -> "At least $minimum chapters"
            maximum != null -> "At most $maximum chapters"
            else -> null
        }
    }
}

private fun List<EntryFilter<*>>.bound(id: String): String? =
    (firstOrNull { (it as? EntryFilterMetadataProvider)?.filterMetadata?.id == id } as? EntryFilter.Text)
        ?.state?.trim()?.ifBlank { null }
