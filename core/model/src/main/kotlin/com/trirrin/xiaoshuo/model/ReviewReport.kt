package com.trirrin.xiaoshuo.model

import kotlinx.serialization.Serializable

@Serializable
data class ReviewReport(
    val score: Int,
    val issues: List<String> = emptyList(),
    val suggestedFixes: List<String> = emptyList(),
    val passed: Boolean,
    val retryCount: Int = 0,
    val status: ReviewStatus = ReviewStatus.PENDING_DECISION,
    val qualityScore: Int = 10,
    val qualityIssues: List<String> = emptyList(),
)

@Serializable
enum class ReviewStatus {
    PENDING_DECISION,
    ACCEPTED,
    NEEDS_RETRY,
    NEEDS_POLISH,
    MANUAL_EDIT,
    APPROVED,
}
