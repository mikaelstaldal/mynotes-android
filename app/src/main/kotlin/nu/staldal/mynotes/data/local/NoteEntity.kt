package nu.staldal.mynotes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val slug: String,
    val title: String,
    val content: String,
    val excerpt: String,
    val createdAt: String,
    val updatedAt: String,
    val version: Int,
    val hasFullContent: Boolean,
    val isPendingDelete: Boolean = false,
)
