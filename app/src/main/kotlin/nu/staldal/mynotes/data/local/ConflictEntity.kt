package nu.staldal.mynotes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conflicts")
data class ConflictEntity(
    @PrimaryKey val slug: String,
    val localTitle: String,
    val localContent: String,
    val localTags: List<TagEntity>,
    val localBaseVersion: Int,
    val serverTitle: String,
    val serverContent: String,
    val serverTags: List<TagEntity>,
    val serverVersion: Int,
    val serverUpdatedAt: String,
    val detectedAt: Long = System.currentTimeMillis(),
)
