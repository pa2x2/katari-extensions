package eu.kanade.tachiyomi.extension.all.rezka

internal fun absoluteEpisodeNumbers(episodes: List<EpisodeData>): Map<Int, Double> {
    return episodes
        .sortedWith(
            compareBy<EpisodeData>(
                { it.seasonNumber ?: Int.MAX_VALUE },
                { it.localEpisodeNumber ?: Double.MAX_VALUE },
                EpisodeData::sourceIndex,
            ),
        )
        .mapIndexed { index, episode -> episode.sourceIndex to (index + 1).toDouble() }
        .toMap()
}
