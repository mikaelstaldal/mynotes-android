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
    version = version,
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
    version = version,
    hasFullContent = false,
    tags = tags.map { it.toEntity() },
)

fun NoteEntity.mergeContentFrom(existing: NoteEntity?): NoteEntity =
    if (existing != null && existing.hasFullContent && existing.version == version) {
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
