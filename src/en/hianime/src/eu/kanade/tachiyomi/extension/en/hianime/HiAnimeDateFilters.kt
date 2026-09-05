package eu.kanade.tachiyomi.extension.en.hianime

import eu.kanade.tachiyomi.source.entry.EntryFilter
import java.time.DateTimeException
import java.time.LocalDate

internal class HiAnimeStartDateFilter : EntryFilter.Text("Start date")
internal class HiAnimeEndDateFilter : EntryFilter.Text("End date")

internal fun EntryFilter.Text.toDateParameters(prefix: String): List<Pair<String, String>> {
    val input = state.trim()
    if (input.isEmpty()) return emptyList()

    val errorMessage = "$name must be a valid date: YYYY, YYYY-MM or YYYY-MM-DD."
    val match = requireNotNull(DATE_REGEX.matchEntire(input)) { errorMessage }
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toIntOrNull()
    val day = match.groupValues[3].toIntOrNull()
    require(year > 0) { errorMessage }
    try {
        LocalDate.of(year, month ?: 1, day ?: 1)
    } catch (_: DateTimeException) {
        throw IllegalArgumentException(errorMessage)
    }

    return buildList {
        add("${prefix}y" to match.groupValues[1])
        month?.let { add("${prefix}m" to it.toString()) }
        day?.let { add("${prefix}d" to it.toString()) }
    }
}

private val DATE_REGEX = Regex("(\\d{4})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?")
