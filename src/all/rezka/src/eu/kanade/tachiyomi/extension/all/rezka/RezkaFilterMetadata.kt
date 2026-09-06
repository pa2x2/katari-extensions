package eu.kanade.tachiyomi.extension.all.rezka

import eu.kanade.tachiyomi.source.entry.filter.EntryFilterMetadata

internal fun rezkaChoiceMetadata(
    id: String, name: String, options: List<String>, legacyOptions: List<String>, neutral: String? = null,
) = EntryFilterMetadata(
    id = id, optionIds = options, legacyNames = setOf(name), legacyOptionIds = legacyOptions,
    neutralOptionId = neutral,
)

// Published 2.5 option order. Never derive these tables from the current display configuration.
internal val LEGACY_GENRE_IDS = mapOf(
    "films" to listOf(
        "any", "western", "family", "fantasy", "biographical",
        "arthouse", "action", "military", "detective", "crime",
        "adventures", "drama", "sport", "fiction", "comedy",
        "melodrama", "thriller", "horror", "musical", "historical",
        "documentary", "erotic", "kids", "travel", "cognitive",
        "theatre", "concert", "standup", "short", "russian",
        "ukrainian", "foreign",
    ),
    "series" to listOf(
        "any", "military", "action", "arthouse", "thriller",
        "horror", "adventures", "family", "fiction", "fantasy",
        "drama", "melodrama", "sport", "comedy", "detective",
        "crime", "historical", "biographical", "western", "documentary",
        "musical", "realtv", "telecasts", "standup", "erotic",
        "russian", "ukrainian", "foreign",
    ),
    "cartoons" to listOf(
        "any", "fiction", "fantasy", "action", "biographical",
        "comedy", "western", "military", "drama", "melodrama",
        "arthouse", "detective", "crime", "thriller", "historical",
        "documentary", "erotic", "fairytale", "family", "horror",
        "adventures", "sport", "cognitive", "musical", "anime",
        "kids", "adult", "multseries", "short", "full-length",
        "soyzmyltfilm", "russian", "ukrainian", "foreign",
    ),
    "anime" to listOf(
        "any", "military", "drama", "detective", "thriller",
        "comedy", "fiction", "fantasy", "adventures", "romance",
        "historical", "horror", "mystery", "musical", "erotic",
        "action", "fighting", "samurai", "sport", "educational",
        "everyday", "parody", "school", "kids", "fairytale",
        "kodomo", "shoujoai", "shoujo", "shounen", "shounenai",
        "ecchi", "mahoushoujo", "mecha",
    ),
)

internal val LEGACY_COLLECTION_IDS = mapOf(
    "films" to listOf(
        "834-filmy-netflix", "1419-filmy-hbo", "1417-filmy-amazon", "1075-filmy-disney", "1416-filmy-apple-tv",
    ),
    "series" to listOf(
        "640-serialy-netflix", "639-serialy-hbo", "831-serialy-amazon", "1148-serialy-disney", "1146-serialy-apple-tv",
        "813-serialy-hulu", "1251-serialy-epix",
    ),
    "cartoons" to listOf(
        "1004-animacionnye-serialy-netflix", "1-multfilmy-disney",
    ),
    "anime" to listOf(
        "1242-anime-netflix",
    ),
)

