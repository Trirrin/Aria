package com.trirrin.xiaoshuo.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val chapterId: String,
    val novelId: String,
    val order: Int,
    val synopsis: String = "",
    val text: String = "",
    val reviewNotes: String? = null,
    val reviewReport: ReviewReport? = null,
    val status: SceneStatus = SceneStatus.PENDING,
    val wordCount: Int = 0,
    val textSource: SceneTextSource = SceneTextSource.EMPTY,
)

enum class SceneStatus {
    PENDING,
    GENERATING,
    GENERATED,
    REVIEWED,
    APPROVED,
}

enum class SceneTextSource {
    EMPTY,
    GENERATED,
    EDITED,
}
