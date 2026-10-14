package nu.staldal.mynotes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conflicts")
data class ConflictEntity(
    @PrimaryKey val slug: String,
    val localTitle: String,
    val localContent: String,
    val localTags: List<TagEntity>,
    val localBaseEtag: String,
    val serverTitle: String,
    val serverContent: String,
    val serverTags: List<TagEntity>,
    val serverEtag: String,
    val serverUpdatedAt: String,
    val detectedAt: Long = System.currentTimeMillis(),
)
