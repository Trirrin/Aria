package com.trirrin.xiaoshuo.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "novels")
data class NovelEntity(
    @PrimaryKey val id: String,
    val title: String,
    val genre: String,
    val concept: String,
    val themesJson: String,
    val styleGuideJson: String,
    val outlineJson: String?,
    val bibleJson: String,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("novelId"), Index(value = ["novelId", "order"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val novelId: String,
    val order: Int,
    val title: String,
    val synopsisJson: String?,
    val reviewNotes: String?,
    val reviewReportJson: String?,
    val status: String,
)

@Entity(
    tableName = "scenes",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("novelId"), Index("chapterId"), Index(value = ["chapterId", "order"], unique = true)],
)
data class SceneEntity(
    @PrimaryKey val id: String,
    val chapterId: String,
    val novelId: String,
    val order: Int,
    val synopsis: String,
    val text: String,
    val reviewNotes: String?,
    val reviewReportJson: String?,
    val status: String,
    val wordCount: Int,
    val textSource: String,
)

@Entity(
    tableName = "revision_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("novelId"), Index("targetId")],
)
data class RevisionSnapshotEntity(
    @PrimaryKey val id: String,
    val novelId: String,
    val targetType: String,
    val targetId: String,
    val label: String,
    val contentJson: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "token_usage_records",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("novelId"), Index("agentName"), Index("model")],
)
data class TokenUsageRecordEntity(
    @PrimaryKey val id: String,
    val novelId: String,
    val agentName: String,
    val provider: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "conversation_sessions", indices = [Index("novelId"), Index("updatedAtEpochMillis")])
data class ConversationSessionEntity(
    @PrimaryKey val id: String,
    val novelId: String?,
    val messagesJson: String,
    val activeToolCallJson: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "pending_approvals", indices = [Index("novelId"), Index("targetId"), Index("createdAtEpochMillis")])
data class PendingApprovalEntity(
    @PrimaryKey val id: String,
    val novelId: String?,
    val targetType: String,
    val targetId: String?,
    val actionName: String,
    val previewTitle: String,
    val previewText: String,
    val proposedPayloadJson: String,
    val riskLevel: String,
    val requiredBeforeCommit: Boolean,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "tool_call_audits", indices = [Index("sessionId"), Index("novelId"), Index("functionName"), Index("createdAtEpochMillis")])
data class ToolCallAuditEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val novelId: String?,
    val functionName: String,
    val argumentSummary: String,
    val resultStatus: String,
    val resultMessage: String,
    val createdAtEpochMillis: Long,
)
