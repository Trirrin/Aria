package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmResponse
import com.trirrin.xiaoshuo.model.*
import com.trirrin.xiaoshuo.prompt.BibleDiff

interface Agent {
    val name: String
    val description: String
}

data class AgentContext(
    val novel: Novel,
    val chapter: Chapter? = null,
    val scene: Scene? = null,
    val previousSceneEnding: String? = null,
    val previousChapterEnding: String? = null,
    val relevantBible: NovelBible = NovelBible(),
    val userEdits: String? = null,
)

data class AgentUsage(
    val agentName: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

internal fun LlmResponse.toAgentUsage(agentName: String, fallbackModel: String): AgentUsage {
    return AgentUsage(
        agentName = agentName,
        model = model.ifBlank { fallbackModel },
        inputTokens = inputTokens,
        outputTokens = outputTokens,
    )
}

sealed class AgentResult {
    data class OutlineResult(val outline: NovelOutline, val usage: AgentUsage? = null) : AgentResult()
    data class SynopsisResult(val synopsis: ChapterSynopsis, val usage: AgentUsage? = null) : AgentResult()
    data class SceneTextResult(val text: String, val wordCount: Int, val usage: AgentUsage? = null) : AgentResult()
    data class ReviewResult(
        val score: Int,
        val issues: List<String>,
        val suggestedFixes: List<String>,
        val passed: Boolean,
        val usage: AgentUsage? = null,
        val qualityScore: Int = 10,
        val qualityIssues: List<String> = emptyList(),
    ) : AgentResult() {
        val needsPolish: Boolean = passed && qualityScore < 7
    }
    data class ContinuityResult(val bibleDiff: BibleDiff, val usage: AgentUsage? = null) : AgentResult()
    data class RollingSummaryResult(val summary: String, val usage: AgentUsage? = null) : AgentResult()
    data class Error(val message: String, val cause: Throwable? = null) : AgentResult()
}
