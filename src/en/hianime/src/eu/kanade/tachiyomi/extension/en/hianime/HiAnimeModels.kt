package eu.kanade.tachiyomi.extension.en.hianime

import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.SEntry
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class HiAnimeEntry(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
)

internal data class HiAnimeDetails(
    val animeId: String?,
    val title: String?,
    val alternateNames: List<Pair<String, String>>,
    val description: String?,
    val genres: List<String>,
    val status: String?,
    val aired: String?,
    val duration: String?,
    val score: String?,
    val studios: List<String>,
    val producers: List<String>,
    val thumbnailUrl: String?,
)

internal data class HiAnimeChapter(
    val id: String,
    val url: String,
    val name: String,
    val number: Double,
)

internal data class HiAnimeServer(
    val type: String,
    val name: String,
    val key: String,
    val url: String,
)

internal fun HiAnimeEntry.toSEntry(): SEntry = SEntry.create().apply {
    url = this@toSEntry.url
    title = this@toSEntry.title
    thumbnailUrl = this@toSEntry.thumbnailUrl
    type = EntryType.ANIME
}

internal fun HiAnimeDetails.applyTo(entry: SEntry): SEntry = entry.copy().apply {
    this@applyTo.title?.let { title = it }
    author = producers.joinToString().ifBlank { null }
    artist = studios.joinToString().ifBlank { null }
    description = buildList {
        this@applyTo.description?.let(::add)
        val facts = buildList {
            alternateNames.forEach { (label, value) -> add("$label: $value") }
            aired?.let { add("Aired: $it") }
            duration?.let { add("Duration: $it") }
            score?.let { add("MAL Score: $it") }
        }
        facts.takeIf(List<String>::isNotEmpty)?.joinToString("\n")?.let(::add)
    }.joinToString("\n\n").ifBlank { null }
    genre = genres.takeIf(List<String>::isNotEmpty)
    status = this@applyTo.status.toHiAnimeStatus()
    thumbnailUrl = this@applyTo.thumbnailUrl ?: entry.thumbnailUrl
    memo = buildJsonObject {
        entry.memo.forEach { (key, value) -> put(key, value) }
        animeId?.let { put(ANIME_ID_MEMO_KEY, it) }
    }
    type = EntryType.ANIME
    initialized = true
}

internal fun HiAnimeChapter.toSEntryChapter(): SEntryChapter = SEntryChapter.create().apply {
    url = this@toSEntryChapter.url
    name = this@toSEntryChapter.name
    dateUpload = 0L
    chapterNumber = number
}

internal fun SEntry.hiAnimeId(): String? = memo[ANIME_ID_MEMO_KEY]
    ?.jsonPrimitive
    ?.contentOrNull
    ?: url.hiAnimeId()

internal fun String.hiAnimeId(): String? = substringBefore('?')
    .trimEnd('/')
    .substringAfterLast('/')
    .substringAfterLast('-')
    .takeIf { value -> value.isNotBlank() && value.all(Char::isDigit) }

private fun String?.toHiAnimeStatus(): Int = when (this?.trim()?.lowercase()) {
    "finished airing" -> SEntry.COMPLETED
    "currently airing" -> SEntry.ONGOING
    else -> SEntry.UNKNOWN
}

private const val ANIME_ID_MEMO_KEY = "hianime.animeId"
