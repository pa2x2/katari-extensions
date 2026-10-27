package eu.kanade.tachiyomi.extension.en.novelfull

import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.SEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class NovelFullEntry(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
)

internal data class NovelFullDetails(
    val novelId: String?,
    val title: String?,
    val author: String?,
    val description: String?,
    val genres: List<String>,
    val status: String?,
    val thumbnailUrl: String?,
    /** How many chapters the site counts, which numbers the chapters by position. */
    val totalChapters: Int?,
    /** The page's "Latest Chapters" box, newest first. */
    val latestChapters: List<NovelFullChapterLink>,
)

internal data class NovelFullChapterLink(
    val url: String,
    val name: String,
)

internal fun NovelFullEntry.toSEntry(): SEntry = SEntry.create().apply {
    url = this@toSEntry.url
    title = this@toSEntry.title
    thumbnailUrl = this@toSEntry.thumbnailUrl
    type = EntryType.BOOK
}

internal fun NovelFullDetails.applyTo(entry: SEntry): SEntry = entry.copy().apply {
    this@applyTo.title?.let { title = it }
    author = this@applyTo.author
    description = this@applyTo.description
    genre = this@applyTo.genres.takeIf(List<String>::isNotEmpty)
    status = this@applyTo.status.toNovelFullStatus()
    thumbnailUrl = this@applyTo.thumbnailUrl
    memo = buildJsonObject {
        entry.memo.forEach { (key, value) -> put(key, value) }
        this@applyTo.novelId?.let { put(NOVEL_ID_MEMO_KEY, it) }
    }
    type = EntryType.BOOK
    initialized = true
}

internal fun SEntry.novelFullNovelId(): String? = memo[NOVEL_ID_MEMO_KEY]
    ?.jsonPrimitive
    ?.contentOrNull

private fun String?.toNovelFullStatus(): Int = when (this?.lowercase()) {
    "ongoing" -> SEntry.ONGOING
    "completed", "complete" -> SEntry.COMPLETED
    else -> SEntry.UNKNOWN
}

private const val NOVEL_ID_MEMO_KEY = "novelfull.novelId"
