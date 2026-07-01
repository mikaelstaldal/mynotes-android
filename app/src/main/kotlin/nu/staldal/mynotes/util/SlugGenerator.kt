package nu.staldal.mynotes.util

/** Mirrors the server's slug derivation in mynotes' internal/service/service.go. */
object SlugGenerator {
    private val slugPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private const val MAX_LENGTH = 100
    private const val FALLBACK = "note"

    fun slugify(title: String): String {
        val collapsed = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        val base = collapsed.ifBlank { FALLBACK }
        return base.take(MAX_LENGTH).trim('-').ifBlank { FALLBACK }
    }

    fun isValid(slug: String): Boolean =
        slug.length in 1..MAX_LENGTH && slugPattern.matches(slug)

    fun withSuffix(baseSlug: String, suffix: Int): String {
        val suffixText = "-$suffix"
        val truncatedBase = baseSlug.take(MAX_LENGTH - suffixText.length)
        return "$truncatedBase$suffixText"
    }
}
