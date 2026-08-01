package eu.kanade.tachiyomi.extension.all.rezka

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class EpisodeCatalogElements(
    val seasons: List<Element>,
    val episodes: List<Element>,
)

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
        .distinctBy { season ->
            season.attr("data-tab_id").trim().toIntOrNull()
                ?: season.attr("data-season_id").trim().toIntOrNull()
        }
    val episodes = documents
        .flatMap { document -> document.select(".b-simple_episode__item[data-episode_id]") }
        .distinctBy { episode ->
            episode.attr("data-season_id").trim().toIntOrNull() to
                episode.attr("data-episode_id").trim().toIntOrNull()
        }

    return EpisodeCatalogElements(seasons = seasons, episodes = episodes)
}
