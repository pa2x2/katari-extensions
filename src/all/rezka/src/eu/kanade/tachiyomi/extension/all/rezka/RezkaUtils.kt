package eu.kanade.tachiyomi.extension.all.rezka

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Calendar
import java.util.Locale

internal val DEFAULT_BEST_BROWSE_YEAR = Calendar.getInstance().get(Calendar.YEAR)

internal val RELEASE_DATE_REGEX = Regex("""(\d{1,2})\s+([а-я]+)\s+(\d{4})""")
internal val SCHEDULE_EPISODE_REGEX = Regex("""(\d+)\s*сезон\s*(\d+)\s*серия""")
internal val SEASON_NUMBER_REGEX = Regex("""(\d+)\s*сезон|сезон\s*(\d+)""")

internal val RU_MONTHS = mapOf(
    "января" to Calendar.JANUARY,
    "февраля" to Calendar.FEBRUARY,
    "марта" to Calendar.MARCH,
    "апреля" to Calendar.APRIL,
    "мая" to Calendar.MAY,
    "июня" to Calendar.JUNE,
    "июля" to Calendar.JULY,
    "августа" to Calendar.AUGUST,
    "сентября" to Calendar.SEPTEMBER,
    "октября" to Calendar.OCTOBER,
    "ноября" to Calendar.NOVEMBER,
    "декабря" to Calendar.DECEMBER,
)

/** Reads a date such as "30 сентября 2013" or "13 ноября 2026 года" as local midnight. */
internal fun parseRussianDate(text: String?): Long? {
    val normalized = text
        ?.lowercase(Locale.ROOT)
        ?.replace('ё', 'е')
        ?.ifBlank { null }
        ?: return null
    val match = RELEASE_DATE_REGEX.find(normalized) ?: return null
    val day = match.groupValues[1].toIntOrNull() ?: return null
    val month = RU_MONTHS[match.groupValues[2]] ?: return null
    val year = match.groupValues[3].toIntOrNull() ?: return null

    return Calendar.getInstance().apply {
        clear()
        set(year, month, day)
    }.timeInMillis
}

internal fun String.encodeForQuery(): String {
    return java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}

internal fun String.toRelativeUrl(baseUrl: String): String? {
    return when {
        startsWith("/") -> this
        startsWith(baseUrl) -> runCatching {
            toHttpUrl().encodedPath +
                if (toHttpUrl().encodedQuery != null) {
                    "?${toHttpUrl().encodedQuery}"
                } else {
                    ""
                }
        }.getOrNull()
        else -> null
    }
}

internal fun JsonObject.stringFieldOrNull(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive.booleanOrNull == false) {
        null
    } else {
        primitive.contentOrNull
            ?.replace("\\/", "/")
            ?.replace("\\\"", "\"")
            ?.takeIf(String::isNotBlank)
    }
}

internal fun JsonObject.jsonObjectFieldOrNull(key: String): JsonObject? {
    return runCatching { this[key]?.jsonObject }.getOrNull()
}
