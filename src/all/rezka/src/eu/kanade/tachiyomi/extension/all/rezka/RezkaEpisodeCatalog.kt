package eu.kanade.tachiyomi.extension.all.rezka

import eu.kanade.tachiyomi.source.entry.SEntryChapter
import eu.kanade.tachiyomi.source.entry.SEntryChapterGroup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

internal data class EpisodeCatalogElements(
    val seasons: List<Element>,
    val episodes: List<Element>,
)

internal data class EpisodeCatalog(
    val chapters: List<SEntryChapter>,
    val groups: List<SEntryChapterGroup>,
)

private val EPISODE_NUMBER_REGEX = Regex("""(\d+(?:[.,]\d+)?)""")

// The schedule names episodes it has no title for yet "Эпизод #8".
private val PLACEHOLDER_EPISODE_TITLE_REGEX = Regex("""^(?:Эпизод|Episode)\s*#?\s*\d+$""", RegexOption.IGNORE_CASE)

internal fun primaryTranslatorCatalogUrl(document: Document, baseUrl: String): String? {
    val href = document.selectFirst(".b-translator__item[data-translator_id][href]")
        ?.attr("href")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null

    return href.toRelativeUrl(baseUrl)?.let(baseUrl::plus)
}

internal fun mergeEpisodeCatalogs(documents: List<Document>): EpisodeCatalogElements {
    val seasons = documents
        .flatMap { document ->
            document.select(".b-simple_season__item[data-tab_id], .b-simple_season__item[data-season_id]")
        }
        .distinctBy(::seasonId)
    val episodes = documents
        .flatMap { document -> document.select(".b-simple_episode__item[data-episode_id]") }
        .distinctBy { episode ->
            episode.attr("data-season_id").trim().toIntOrNull() to
                episode.attr("data-episode_id").trim().toIntOrNull()
        }

    return EpisodeCatalogElements(seasons = seasons, episodes = episodes)
}

/** A film is one chapter; [releaseDate] is the film's, when the page gives one. */
internal fun movieChapter(entryUrl: String, releaseDate: Long?): SEntryChapter {
    return SEntryChapter.create().apply {
        url = buildEpisodeUrl(videoUrl = entryUrl, seasonId = null, episodeId = null)
        name = "Movie"
        dateUpload = releaseDate ?: 0L
        chapterNumber = 1.0
    }
}

/**
 * Lists a series' episodes newest first, each in the group of its season. [documents] are the title page and the page
 * of its first voiceover, whose seasons and episodes can differ.
 */
internal fun buildEpisodeCatalog(entryUrl: String, documents: List<Document>): EpisodeCatalog {
    val catalog = mergeEpisodeCatalogs(documents)
    val schedule = documents
        .flatMap(::parseScheduledEpisodes)
        .associateBy { it.seasonNumber to it.episodeNumber }

    val seasons = catalog.seasons
        .mapNotNull { item ->
            val id = seasonId(item) ?: return@mapNotNull null
            val label = item.text().trim().ifBlank { null }
            id to SeasonData(label = label, seasonNumber = parseSeasonNumber(label) ?: id)
        }
        .toMap()

    val episodes = catalog.episodes.mapIndexed { index, item ->
        val seasonId = item.attr("data-season_id").trim().toIntOrNull()
        val episodeId = item.attr("data-episode_id").trim().toIntOrNull()
        val title = item.text().trim().ifBlank {
            episodeId?.let { "Episode $it" } ?: "Episode ${index + 1}"
        }
        val seasonNumber = seasonId?.let { seasons[it]?.seasonNumber ?: it }
        val scheduled = if (seasonNumber != null && episodeId != null) schedule[seasonNumber to episodeId] else null

        EpisodeData(
            sourceIndex = index,
            seasonId = seasonId,
            episodeId = episodeId,
            seasonNumber = seasonNumber,
            seasonLabel = seasonId?.let { seasons[it]?.label },
            title = title,
            episodeTitle = scheduled?.title,
            localEpisodeNumber = parseEpisodeNumber(title) ?: episodeId?.toDouble(),
            uploadDate = scheduled?.airDate ?: 0L,
        )
    }
    val chapterNumbers = absoluteEpisodeNumbers(episodes)

    val groups = seasons.entries
        .filter { (id, _) -> episodes.any { it.seasonId == id } }
        .sortedWith(compareBy({ it.value.seasonNumber }, { it.key }))
        .map { (id, season) -> SEntryChapterGroup.create(key = id.toString(), title = season.label ?: "Сезон $id") }
    val groupKeys = groups.mapTo(mutableSetOf(), SEntryChapterGroup::key)

    val chapters = episodes
        .sortedByDescending { episode -> chapterNumbers.getValue(episode.sourceIndex) }
        .map { episode ->
            SEntryChapter.create().apply {
                url = buildEpisodeUrl(
                    videoUrl = entryUrl,
                    seasonId = episode.seasonId,
                    episodeId = episode.episodeId,
                )
                name = listOfNotNull(episode.seasonLabel, episode.title, episode.episodeTitle)
                    .joinToString(" - ")
                    .ifBlank { episode.title }
                dateUpload = episode.uploadDate
                chapterNumber = chapterNumbers.getValue(episode.sourceIndex)
                group = episode.seasonId?.toString()?.takeIf(groupKeys::contains)
            }
        }

    return EpisodeCatalog(chapters = chapters, groups = groups)
}

private fun seasonId(season: Element): Int? {
    return season.attr("data-tab_id").trim().toIntOrNull()
        ?: season.attr("data-season_id").trim().toIntOrNull()
}

private fun buildEpisodeUrl(videoUrl: String, seasonId: Int?, episodeId: Int?): String {
    val params = buildList {
        seasonId?.let { add("season=$it") }
        episodeId?.let { add("episode=$it") }
    }
    return if (params.isEmpty()) videoUrl else "$videoUrl#${params.joinToString("&")}"
}

/** Rows of "дата выхода серий": "5 сезон 16 серия", the episode's title, and its air date. */
private fun parseScheduledEpisodes(document: Document): List<ScheduledEpisode> {
    return document.select(".b-post__schedule_table tr")
        .mapNotNull { row ->
            val (seasonNumber, episodeNumber) = row.selectFirst("td.td-1")
                ?.text()
                ?.let(::parseScheduleSeasonEpisode)
                ?: return@mapNotNull null
            val titleCell = row.selectFirst("td.td-2")
            val title = (titleCell?.selectFirst("b")?.text() ?: titleCell?.selectFirst("span")?.text())
                ?.trim()
                ?.takeUnless { it.isEmpty() || PLACEHOLDER_EPISODE_TITLE_REGEX.matches(it) }
            ScheduledEpisode(
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                title = title,
                airDate = parseRussianDate(row.selectFirst("td.td-4")?.text()),
            )
        }
}

private fun parseScheduleSeasonEpisode(text: String): Pair<Int, Int>? {
    val match = SCHEDULE_EPISODE_REGEX.find(text.lowercase(Locale.ROOT)) ?: return null
    val seasonNumber = match.groupValues[1].toIntOrNull() ?: return null
    val episodeNumber = match.groupValues[2].toIntOrNull() ?: return null
    return seasonNumber to episodeNumber
}

private fun parseSeasonNumber(label: String?): Int? {
    val normalized = label?.lowercase(Locale.ROOT)?.ifBlank { null } ?: return null
    val match = SEASON_NUMBER_REGEX.find(normalized) ?: return null
    return match.groupValues.drop(1).firstNotNullOfOrNull(String::toIntOrNull)
}

private fun parseEpisodeNumber(title: String): Double? {
    val match = EPISODE_NUMBER_REGEX.find(title)?.groupValues?.get(1) ?: return null
    return match.replace(',', '.').toDoubleOrNull()
}
