package nu.staldal.mynotes.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object NoteDateUtils {
    private val displayFormatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())

    fun formatDisplayDateTime(rfc3339: String): String =
        try {
            displayFormatter.format(Instant.parse(rfc3339))
        } catch (_: Exception) {
            rfc3339
        }

    fun nowRfc3339(): String = Instant.now().toString()
}
