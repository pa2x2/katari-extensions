package eu.kanade.tachiyomi.extension.en.readnovelfull

import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.SEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ReadNovelFullEntry(
    val url: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val completed: Boolean,
)

internal data class ReadNovelFullDetails(
    val novelId: String?,
    val title: String?,
    val author: String?,
    val description: String?,
    val genres: List<String>,
    val status: String?,
    val thumbnailUrl: String?,
    /** The page's "Latest chapter" row. */
    val latestChapter: ReadNovelFullLatestChapter?,
)

/** The newest chapter with its release time as the site words it, such as "5 years ago". */
internal data class ReadNovelFullLatestChapter(
    val chapter: ReadNovelFullChapter,
    val timeText: String?,
)

internal data class ReadNovelFullChapter(
    val url: String,
    val name: String,
)

internal data class ReadNovelFullChapterContent(
    val title: String?,
    val html: String,
    val assets: List<ReadNovelFullProseAsset>,
)

internal fun ReadNovelFullEntry.toSEntry(): SEntry = SEntry.create().apply {
    url = this@toSEntry.url
    title = this@toSEntry.title
    author = this@toSEntry.author
    thumbnailUrl = this@toSEntry.thumbnailUrl
    status = if (completed) SEntry.COMPLETED else SEntry.UNKNOWN
    type = EntryType.BOOK
}

internal fun ReadNovelFullDetails.applyTo(entry: SEntry): SEntry = entry.copy().apply {
    this@applyTo.title?.let { title = it }
    author = this@applyTo.author
    description = this@applyTo.description
    genre = this@applyTo.genres.takeIf(List<String>::isNotEmpty)
    status = this@applyTo.status.toReadNovelFullStatus()
    thumbnailUrl = this@applyTo.thumbnailUrl
    memo = buildJsonObject {
        entry.memo.forEach { (key, value) -> put(key, value) }
        this@applyTo.novelId?.let { put(NOVEL_ID_MEMO_KEY, it) }
    }
    type = EntryType.BOOK
    initialized = true
}

internal fun SEntry.readNovelFullNovelId(): String? = memo[NOVEL_ID_MEMO_KEY]
    ?.jsonPrimitive
    ?.contentOrNull

private fun String?.toReadNovelFullStatus(): Int = when (this?.trim()?.lowercase()) {
    "ongoing" -> SEntry.ONGOING
    "completed", "complete" -> SEntry.COMPLETED
    else -> SEntry.UNKNOWN
}

private const val NOVEL_ID_MEMO_KEY = "readnovelfull.novelId"
