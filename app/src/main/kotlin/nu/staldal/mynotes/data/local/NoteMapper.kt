package nu.staldal.mynotes.data.local

import nu.staldal.mynotes.data.api.CreateNoteRequest
import nu.staldal.mynotes.data.api.Note
import nu.staldal.mynotes.data.api.NoteSummary
import nu.staldal.mynotes.data.api.Tag
import nu.staldal.mynotes.data.api.UpdateNoteRequest

fun Tag.toEntity(): TagEntity = TagEntity(slug = slug)

fun Note.toEntity(hasFullContent: Boolean = true): NoteEntity = NoteEntity(
    slug = slug,
    title = title,
    content = content,
    excerpt = "",
    createdAt = createdAt,
    updatedAt = updatedAt,
    etag = timestampEtag(updatedAt),
    hasFullContent = hasFullContent,
    tags = tags.map { it.toEntity() },
)

/**
 * List/search results have no `content` field. Callers must merge this with any existing
 * cached row (see [mergeContentFrom]) rather than upserting it directly, or a full note's
 * cached content would be silently wiped by the next list refresh.
 */
fun NoteSummary.toEntity(): NoteEntity = NoteEntity(
    slug = slug,
    title = title,
    content = "",
    excerpt = excerpt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    etag = timestampEtag(updatedAt),
    hasFullContent = false,
    tags = tags.map { it.toEntity() },
)

fun NoteEntity.mergeContentFrom(existing: NoteEntity?): NoteEntity =
    if (existing != null && existing.hasFullContent && existing.etag == etag) {
        copy(content = existing.content, hasFullContent = true)
    } else {
        this
    }

fun NoteEntity.toCreateRequest(): CreateNoteRequest = CreateNoteRequest(
    title = title,
    content = content,
    slug = slug,
    tags = tags.map { it.slug },
)

fun NoteEntity.toUpdateRequest(): UpdateNoteRequest = UpdateNoteRequest(
    title = title,
    content = content,
    tags = tags.map { it.slug },
)

/** The API serializes updated_at in UTC with exactly millisecond precision. */
fun timestampEtag(updatedAt: String): String = "\"$updatedAt\""

/** A proxy may weaken or rewrite the header; the API defines the body timestamp as canonical. */
fun responseEtag(header: String?, updatedAt: String): String =
    timestampEtag(updatedAt).let { canonical -> header?.takeIf { it == canonical } ?: canonical }
