package nu.staldal.mynotes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artifacts")
data class ArtifactEntity(
    @PrimaryKey val localId: String,
    val localFilePath: String,
    val contentType: String,
    val sha256: String?,
    val uploadPending: Boolean = true,
    val ownerSlug: String,
)
