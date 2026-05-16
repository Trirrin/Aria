package com.trirrin.xiaoshuo.data

import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.ChapterStatus
import com.trirrin.xiaoshuo.model.ChapterSynopsis
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.model.NovelStatus
import com.trirrin.xiaoshuo.model.ReviewReport
import com.trirrin.xiaoshuo.model.Scene
import com.trirrin.xiaoshuo.model.SceneStatus
import com.trirrin.xiaoshuo.model.SceneTextSource
import com.trirrin.xiaoshuo.model.StyleGuide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class NovelRepository(
    private val dao: NovelDao,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun observeNovels(): Flow<List<Novel>> {
        return dao.observeNovels().map { novels -> novels.map { it.toModel(json) } }
    }

    suspend fun getNovel(id: String): Novel? {
        return dao.getNovel(id)?.toModel(json)
    }

    suspend fun upsertNovel(novel: Novel) {
        dao.upsertNovel(novel.toEntity(json))
    }

    suspend fun deleteNovel(id: String) {
        dao.deleteNovel(id)
    }

    fun observeChapters(novelId: String): Flow<List<Chapter>> {
        return dao.observeChapters(novelId).map { chapters -> chapters.map { it.toModel(json) } }
    }

    suspend fun getChapters(novelId: String): List<Chapter> {
        return dao.getChapters(novelId).map { it.toModel(json) }
    }

    suspend fun upsertChapter(chapter: Chapter) {
        dao.upsertChapter(chapter.toEntity(json))
    }

    fun observeScenes(chapterId: String): Flow<List<Scene>> {
        return dao.observeScenes(chapterId).map { scenes -> scenes.map { it.toModel(json) } }
    }

    suspend fun getScenes(chapterId: String): List<Scene> {
        return dao.getScenes(chapterId).map { it.toModel(json) }
    }

    fun observeScenesForNovel(novelId: String): Flow<List<Scene>> {
        return dao.observeScenesForNovel(novelId).map { scenes -> scenes.map { it.toModel(json) } }
    }

    suspend fun getScenesForNovel(novelId: String): List<Scene> {
        return dao.getScenesForNovel(novelId).map { it.toModel(json) }
    }

    suspend fun upsertScene(scene: Scene) {
        dao.upsertScene(scene.toEntity(json))
    }

    suspend fun resetInterruptedScenes(): Int {
        return dao.resetGeneratingScenes()
    }

    fun observeRevisionSnapshots(novelId: String): Flow<List<RevisionSnapshot>> {
        return dao.observeRevisionSnapshots(novelId).map { snapshots -> snapshots.map { it.toModel() } }
    }

    suspend fun getRevisionSnapshot(id: String): RevisionSnapshot? {
        return dao.getRevisionSnapshot(id)?.toModel()
    }

    suspend fun saveRevisionSnapshot(novelId: String, targetType: String, targetId: String, label: String, contentJson: String) {
        dao.upsertRevisionSnapshot(
            RevisionSnapshotEntity(
                id = UUID.randomUUID().toString(),
                novelId = novelId,
                targetType = targetType,
                targetId = targetId,
                label = label,
                contentJson = contentJson,
                createdAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    suspend fun deleteRevisionSnapshot(id: String) {
        dao.deleteRevisionSnapshot(id)
    }

    fun observeTokenUsage(novelId: String): Flow<List<TokenUsageRecord>> {
        return dao.observeTokenUsage(novelId).map { records -> records.map { it.toModel() } }
    }

    suspend fun saveTokenUsage(record: TokenUsageRecord) {
        dao.upsertTokenUsage(record.toEntity())
    }

    fun observeConversationSessions(): Flow<List<ConversationSessionRecord>> {
        return dao.observeConversationSessions().map { sessions -> sessions.map { it.toModel() } }
    }

    suspend fun getConversationSession(id: String): ConversationSessionRecord? {
        return dao.getConversationSession(id)?.toModel()
    }

    suspend fun saveConversationSession(session: ConversationSessionRecord) {
        dao.upsertConversationSession(session.toEntity())
    }

    suspend fun deleteConversationSession(id: String) {
        dao.deleteConversationSession(id)
    }

    fun observePendingApprovals(): Flow<List<PendingApprovalRecord>> {
        return dao.observePendingApprovals().map { approvals -> approvals.map { it.toModel() } }
    }

    suspend fun getPendingApproval(id: String): PendingApprovalRecord? {
        return dao.getPendingApproval(id)?.toModel()
    }

    suspend fun savePendingApproval(approval: PendingApprovalRecord) {
        dao.upsertPendingApproval(approval.toEntity())
    }

    suspend fun deletePendingApproval(id: String) {
        dao.deletePendingApproval(id)
    }

    fun observeToolCallAudits(sessionId: String): Flow<List<ToolCallAuditRecord>> {
        return dao.observeToolCallAudits(sessionId).map { audits -> audits.map { it.toModel() } }
    }

    suspend fun saveToolCallAudit(audit: ToolCallAuditRecord) {
        dao.upsertToolCallAudit(audit.toEntity())
    }
}

private fun Novel.toEntity(json: Json): NovelEntity {
    return NovelEntity(
        id = id,
        title = title,
        genre = genre.name,
        concept = concept,
        themesJson = json.encodeToString(themes),
        styleGuideJson = json.encodeToString(styleGuide),
        outlineJson = outline?.let { json.encodeToString(it) },
        bibleJson = json.encodeToString(bible),
        status = status.name,
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
        updatedAtEpochMillis = updatedAt.toEpochMilliseconds(),
    )
}

private fun NovelEntity.toModel(json: Json): Novel {
    return Novel(
        id = id,
        title = title,
        genre = enumValueOf<Genre>(genre),
        concept = concept,
        themes = json.decodeFromString<List<String>>(themesJson),
        styleGuide = json.decodeFromString<StyleGuide>(styleGuideJson),
        outline = outlineJson?.let { json.decodeFromString<NovelOutline>(it) },
        bible = json.decodeFromString<NovelBible>(bibleJson),
        status = enumValueOf<NovelStatus>(status),
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMillis),
        updatedAt = Instant.fromEpochMilliseconds(updatedAtEpochMillis),
    )
}

private fun Chapter.toEntity(json: Json): ChapterEntity {
    return ChapterEntity(
        id = id,
        novelId = novelId,
        order = order,
        title = title,
        synopsisJson = synopsis?.let { json.encodeToString(it) },
        reviewNotes = reviewNotes,
        reviewReportJson = reviewReport?.let { json.encodeToString(it) },
        status = status.name,
    )
}

private fun ChapterEntity.toModel(json: Json): Chapter {
    return Chapter(
        id = id,
        novelId = novelId,
        order = order,
        title = title,
        synopsis = synopsisJson?.let { json.decodeFromString<ChapterSynopsis>(it) },
        reviewNotes = reviewNotes,
        reviewReport = reviewReportJson?.let { json.decodeFromString<ReviewReport>(it) },
        status = enumValueOf<ChapterStatus>(status),
    )
}

private fun Scene.toEntity(json: Json): SceneEntity {
    return SceneEntity(
        id = id,
        chapterId = chapterId,
        novelId = novelId,
        order = order,
        synopsis = synopsis,
        text = text,
        reviewNotes = reviewNotes,
        reviewReportJson = reviewReport?.let { json.encodeToString(it) },
        status = status.name,
        wordCount = wordCount,
        textSource = textSource.name,
    )
}

private fun SceneEntity.toModel(json: Json): Scene {
    return Scene(
        id = id,
        chapterId = chapterId,
        novelId = novelId,
        order = order,
        synopsis = synopsis,
        text = text,
        reviewNotes = reviewNotes,
        reviewReport = reviewReportJson?.let { json.decodeFromString<ReviewReport>(it) },
        status = enumValueOf<SceneStatus>(status),
        wordCount = wordCount,
        textSource = enumValueOf<SceneTextSource>(textSource),
    )
}

data class RevisionSnapshot(
    val id: String,
    val novelId: String,
    val targetType: String,
    val targetId: String,
    val label: String,
    val contentJson: String,
    val createdAt: Instant,
)

data class TokenUsageRecord(
    val id: String = UUID.randomUUID().toString(),
    val novelId: String,
    val agentName: String,
    val provider: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double,
    val createdAt: Instant = Clock.System.now(),
)

data class ConversationSessionRecord(
    val id: String = UUID.randomUUID().toString(),
    val novelId: String? = null,
    val messagesJson: String,
    val activeToolCallJson: String? = null,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = createdAt,
)

data class PendingApprovalRecord(
    val id: String = UUID.randomUUID().toString(),
    val novelId: String? = null,
    val targetType: String,
    val targetId: String? = null,
    val actionName: String,
    val previewTitle: String,
    val previewText: String,
    val proposedPayloadJson: String,
    val riskLevel: String,
    val requiredBeforeCommit: Boolean,
    val createdAt: Instant = Clock.System.now(),
)

data class ToolCallAuditRecord(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val novelId: String? = null,
    val functionName: String,
    val argumentSummary: String,
    val resultStatus: String,
    val resultMessage: String,
    val createdAt: Instant = Clock.System.now(),
)

private fun RevisionSnapshotEntity.toModel(): RevisionSnapshot {
    return RevisionSnapshot(
        id = id,
        novelId = novelId,
        targetType = targetType,
        targetId = targetId,
        label = label,
        contentJson = contentJson,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMillis),
    )
}

private fun TokenUsageRecordEntity.toModel(): TokenUsageRecord {
    return TokenUsageRecord(
        id = id,
        novelId = novelId,
        agentName = agentName,
        provider = provider,
        model = model,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        estimatedCostUsd = estimatedCostUsd,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMillis),
    )
}

private fun TokenUsageRecord.toEntity(): TokenUsageRecordEntity {
    return TokenUsageRecordEntity(
        id = id,
        novelId = novelId,
        agentName = agentName,
        provider = provider,
        model = model,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        estimatedCostUsd = estimatedCostUsd,
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
    )
}

private fun ConversationSessionEntity.toModel(): ConversationSessionRecord {
    return ConversationSessionRecord(
        id = id,
        novelId = novelId,
        messagesJson = messagesJson,
        activeToolCallJson = activeToolCallJson,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMillis),
        updatedAt = Instant.fromEpochMilliseconds(updatedAtEpochMillis),
    )
}

private fun ConversationSessionRecord.toEntity(): ConversationSessionEntity {
    return ConversationSessionEntity(
        id = id,
        novelId = novelId,
        messagesJson = messagesJson,
        activeToolCallJson = activeToolCallJson,
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
        updatedAtEpochMillis = updatedAt.toEpochMilliseconds(),
    )
}

private fun PendingApprovalEntity.toModel(): PendingApprovalRecord {
    return PendingApprovalRecord(
        id = id,
        novelId = novelId,
        targetType = targetType,
        targetId = targetId,
        actionName = actionName,
        previewTitle = previewTitle,
        previewText = previewText,
        proposedPayloadJson = proposedPayloadJson,
        riskLevel = riskLevel,
        requiredBeforeCommit = requiredBeforeCommit,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMillis),
    )
}

private fun PendingApprovalRecord.toEntity(): PendingApprovalEntity {
    return PendingApprovalEntity(
        id = id,
        novelId = novelId,
        targetType = targetType,
        targetId = targetId,
        actionName = actionName,
        previewTitle = previewTitle,
        previewText = previewText,
        proposedPayloadJson = proposedPayloadJson,
        riskLevel = riskLevel,
        requiredBeforeCommit = requiredBeforeCommit,
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
    )
}

private fun ToolCallAuditEntity.toModel(): ToolCallAuditRecord {
    return ToolCallAuditRecord(
        id = id,
        sessionId = sessionId,
        novelId = novelId,
        functionName = functionName,
        argumentSummary = argumentSummary,
        resultStatus = resultStatus,
        resultMessage = resultMessage,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMillis),
    )
}

private fun ToolCallAuditRecord.toEntity(): ToolCallAuditEntity {
    return ToolCallAuditEntity(
        id = id,
        sessionId = sessionId,
        novelId = novelId,
        functionName = functionName,
        argumentSummary = argumentSummary,
        resultStatus = resultStatus,
        resultMessage = resultMessage,
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
    )
}
