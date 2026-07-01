package nu.staldal.mynotes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ChangeType {
    CREATE, UPDATE, DELETE
}

enum class PendingChangeStatus {
    PENDING, CONFLICT
}

@Entity(tableName = "pending_changes")
data class PendingChange(
    @PrimaryKey(autoGenerate = true) val changeId: Long = 0,
    val slug: String,
    val changeType: ChangeType,
    val baseVersion: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val status: PendingChangeStatus = PendingChangeStatus.PENDING,
)
