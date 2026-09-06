package eu.kanade.tachiyomi.extension.en.hianime

import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.filter.*

internal class HiAnimeStartDateFilter : EntryDateFilter(
    "Start date",
    EntryFilterMetadata(
        id = "release.start",
        legacyNames = setOf("Start date"),
        description = "YYYY, YYYY-MM or YYYY-MM-DD. Leave blank for no lower bound.",
    ),
)

internal class HiAnimeEndDateFilter : EntryDateFilter(
    "End date",
    EntryFilterMetadata(
        id = "release.end",
        legacyNames = setOf("End date"),
        description = "YYYY, YYYY-MM or YYYY-MM-DD. Leave blank for no upper bound.",
    ),
)

internal class HiAnimeReleasePeriodFilter : EntryFilter.Group<EntryFilter<*>>(
    "Release period",
    listOf(HiAnimeSeasonFilter(), HiAnimeStartDateFilter(), HiAnimeEndDateFilter()),
), EntryFilterMetadataProvider, EntryFilterValidator, EntryFilterGroupSummary {
    override val filterMetadata = EntryFilterMetadata(id = "release")

    override fun validateFilter(values: List<EntryFilter<*>>): List<EntryFilterValidationIssue> {
        val start = values.date("release.start")
            ?.state?.let(EntryPartialDate::parse) ?: return emptyList()
        val end = values.date("release.end")
            ?.state?.let(EntryPartialDate::parse) ?: return emptyList()
        return if (start.earliestDayKey() > end.latestDayKey()) listOf(
            EntryFilterValidationIssue(
                setOf("release.start", "release.end"), EntryFilterValidationCode.DATE_RANGE,
                "Start date must not be after end date.",
            ),
        ) else emptyList()
    }

    override fun selectionSummary(values: List<EntryFilter<*>>): String? = buildList {
        (values.firstOrNull { (it as? EntryFilterMetadataProvider)?.filterMetadata?.id == "season" }
            as? EntryFilter.Select<*>)?.let {
            if (it.state in 1 until it.values.size) add(it.values[it.state].toString())
        }
        values.filterIsInstance<EntryDateFilter>().filter { it.state.isNotBlank() }.forEach {
            add("${it.name}: ${it.state.trim()}")
        }
    }.joinToString().ifBlank { null }
}

internal fun EntryDateFilter.toDateParameters(prefix: String): List<Pair<String, String>> {
    val date = dateValue ?: return emptyList()
    return buildList {
        add("${prefix}y" to date.year.toString().padStart(4, '0'))
        date.month?.let { add("${prefix}m" to it.toString()) }
        date.day?.let { add("${prefix}d" to it.toString()) }
    }
}

private fun List<EntryFilter<*>>.date(id: String): EntryDateFilter? =
    firstOrNull { (it as? EntryFilterMetadataProvider)?.filterMetadata?.id == id } as? EntryDateFilter
