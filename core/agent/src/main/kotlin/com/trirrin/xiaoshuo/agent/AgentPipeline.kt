package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.model.*
import com.trirrin.xiaoshuo.prompt.OutlineInput
import com.trirrin.xiaoshuo.prompt.ReviewType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed class PipelineEvent {
    data class OutlineGenerated(val outline: NovelOutline) : PipelineEvent()
    data class SynopsisGenerated(val synopsis: ChapterSynopsis) : PipelineEvent()
    data class SceneTextDelta(val delta: String) : PipelineEvent()
    data class SceneTextComplete(val text: String, val wordCount: Int) : PipelineEvent()
    data class ReviewComplete(val result: AgentResult.ReviewResult) : PipelineEvent()
    data class BibleUpdated(val bible: NovelBible) : PipelineEvent()
    data class Error(val message: String, val cause: Throwable? = null) : PipelineEvent()
    data class Step(val agentName: String, val description: String) : PipelineEvent()
}

data class PipelineConfig(
    val outlineModel: String,
    val synopsisModel: String,
    val textModel: String,
    val reviewModel: String,
    val continuityModel: String,
    val maxReviewRetries: Int = 2,
)

class AgentPipeline(
    private val outlineAgent: OutlineAgent,
    private val chapterSynopsisAgent: ChapterSynopsisAgent,
    private val sceneExpansionAgent: SceneExpansionAgent,
    private val reviewAgent: ReviewAgent,
    private val continuityAgent: ContinuityAgent,
    private val bibleMerger: BibleMerger,
) {

    suspend fun generateOutline(
        novel: Novel,
    ): Pair<NovelOutline?, List<PipelineEvent>> {
        val events = mutableListOf<PipelineEvent>()
        events.add(PipelineEvent.Step("outline", "Generating outline for: ${novel.title}"))

        val input = OutlineInput(
            concept = novel.concept,
            genre = novel.genre,
            themes = novel.themes,
            styleGuide = novel.styleGuide,
        )

        val result = outlineAgent.generate(input)
        when (result) {
            is AgentResult.OutlineResult -> {
                events.add(PipelineEvent.OutlineGenerated(result.outline))
                return result.outline to events
            }
            is AgentResult.Error -> {
                events.add(PipelineEvent.Error(result.message, result.cause))
                return null to events
            }
            else -> {
                events.add(PipelineEvent.Error("Unexpected result type from outline agent"))
                return null to events
            }
        }
    }

    suspend fun generateChapterSynopsis(
        novel: Novel,
        chapter: Chapter,
        chapters: List<Chapter>,
        previousChapterEnding: String? = null,
    ): Pair<ChapterSynopsis?, AgentResult.ReviewResult?> {
        val synopsisResult = chapterSynopsisAgent.generate(
            novel = novel,
            chapter = chapter,
            chapters = chapters,
            previousChapterEnding = previousChapterEnding,
        )

        val synopsis = when (synopsisResult) {
            is AgentResult.SynopsisResult -> synopsisResult.synopsis
            is AgentResult.Error -> return null to null
            else -> return null to null
        }

        val outline = novel.outline ?: return synopsis to null
        val brief = outline.chapterBriefs.find { it.chapterIndex == chapter.order }
            ?: return synopsis to null

        val reviewResult = reviewAgent.review(
            parentDirective = "Chapter ${chapter.order}: ${brief.title}\nPlot beats: ${brief.plotBeats}\nPurpose: ${brief.purposeInStory}",
            childOutput = synopsis.sceneBreakdowns.joinToString("\n") { it.synopsis },
            reviewType = ReviewType.SYNOPSIS_AGAINST_OUTLINE,
        )

        return when (reviewResult) {
            is AgentResult.ReviewResult -> synopsis to reviewResult
            else -> synopsis to null
        }
    }

    suspend fun generateScene(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        previousSceneEnding: String? = null,
    ): Flow<PipelineEvent> = flow {
        emit(PipelineEvent.Step("scene_expansion", "Generating scene ${scene.order} text"))

        val result = sceneExpansionAgent.generate(
            novel = novel,
            chapter = chapter,
            scene = scene,
            previousSceneEnding = previousSceneEnding,
        )

        val text = when (result) {
            is AgentResult.SceneTextResult -> {
                emit(PipelineEvent.SceneTextComplete(result.text, result.wordCount))
                result.text
            }
            is AgentResult.Error -> {
                emit(PipelineEvent.Error(result.message, result.cause))
                return@flow
            }
            else -> {
                emit(PipelineEvent.Error("Unexpected result from scene agent"))
                return@flow
            }
        }

        val synopsis = chapter.synopsis ?: return@flow
        val breakdown = synopsis.sceneBreakdowns.find { it.sceneIndex == scene.order } ?: return@flow

        emit(PipelineEvent.Step("review", "Reviewing scene against synopsis"))
        val reviewResult = reviewAgent.review(
            parentDirective = breakdown.synopsis,
            childOutput = text,
            reviewType = ReviewType.TEXT_AGAINST_SYNOPSIS,
        )
        when (reviewResult) {
            is AgentResult.ReviewResult -> emit(PipelineEvent.ReviewComplete(reviewResult))
            else -> {}
        }

        emit(PipelineEvent.Step("continuity", "Extracting facts for Bible update"))
        val continuityResult = continuityAgent.extract(
            sceneText = text,
            existingBible = novel.bible,
            chapterId = chapter.id,
            sceneId = scene.id,
        )
        when (continuityResult) {
            is AgentResult.ContinuityResult -> {
                val updatedBible = bibleMerger.merge(
                    novel.bible, continuityResult.bibleDiff, chapter.id, scene.id
                )
                emit(PipelineEvent.BibleUpdated(updatedBible))
            }
            is AgentResult.Error -> {
                emit(PipelineEvent.Error("Continuity extraction failed: ${continuityResult.message}"))
            }
            else -> {}
        }
    }

    suspend fun streamScene(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        previousSceneEnding: String? = null,
    ): Flow<String> {
        return sceneExpansionAgent.stream(
            novel = novel,
            chapter = chapter,
            scene = scene,
            previousSceneEnding = previousSceneEnding,
        )
    }
}
