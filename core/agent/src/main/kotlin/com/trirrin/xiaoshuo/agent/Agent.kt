package com.trirrin.xiaoshuo.agent

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

sealed class AgentResult {
    data class OutlineResult(val outline: NovelOutline) : AgentResult()
    data class SynopsisResult(val synopsis: ChapterSynopsis) : AgentResult()
    data class SceneTextResult(val text: String, val wordCount: Int) : AgentResult()
    data class ReviewResult(
        val score: Int,
        val issues: List<String>,
        val suggestedFixes: List<String>,
        val passed: Boolean,
    ) : AgentResult()
    data class ContinuityResult(val bibleDiff: BibleDiff) : AgentResult()
    data class Error(val message: String, val cause: Throwable? = null) : AgentResult()
}
