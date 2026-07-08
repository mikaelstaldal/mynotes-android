package nu.staldal.mynotes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Doubles as the row type for the standalone "tags" table (all known tags, refreshed from
 * GET /tags) and as the element type embedded in [NoteEntity.tags] (a note's own tag set).
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val slug: String,
)
