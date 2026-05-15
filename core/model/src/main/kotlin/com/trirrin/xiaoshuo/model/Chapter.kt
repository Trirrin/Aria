package com.trirrin.xiaoshuo.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Chapter(
    val id: String = UUID.randomUUID().toString(),
    val novelId: String,
    val order: Int,
    val title: String = "",
    val synopsis: ChapterSynopsis? = null,
    val reviewNotes: String? = null,
    val reviewReport: ReviewReport? = null,
    val status: ChapterStatus = ChapterStatus.PENDING,
)

enum class ChapterStatus {
    PENDING,
    SYNOPSIS_GENERATED,
    SYNOPSIS_APPROVED,
    DRAFTING,
    COMPLETE,
}
