package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.llm.LlmMessage
import com.trirrin.xiaoshuo.llm.LlmRequest
import com.trirrin.xiaoshuo.llm.MessageRole
import com.trirrin.xiaoshuo.prompt.ChapterSynopsisInput
import com.trirrin.xiaoshuo.prompt.ChapterSynopsisPrompt
import com.trirrin.xiaoshuo.model.*
import com.trirrin.xiaoshuo.prompt.OutlineInput
import com.trirrin.xiaoshuo.prompt.SceneExpansionInput
import com.trirrin.xiaoshuo.prompt.SceneExpansionPrompt
import com.trirrin.xiaoshuo.prompt.ReviewType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

sealed class PipelineEvent {
    data class OutlineGenerated(val outline: NovelOutline) : PipelineEvent()
    data class SynopsisGenerated(val synopsis: ChapterSynopsis) : PipelineEvent()
    data class SceneTextDelta(val delta: String) : PipelineEvent()
    data class SceneTextComplete(val text: String, val wordCount: Int) : PipelineEvent()
    data class ReviewComplete(val result: AgentResult.ReviewResult) : PipelineEvent()
    data class BibleUpdated(val bible: NovelBible) : PipelineEvent()
    data class RollingSummaryUpdated(val summary: String) : PipelineEvent()
    data class UsageRecorded(val usage: AgentUsage) : PipelineEvent()
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

data class ChapterSynopsisPipelineResult(
    val synopsis: ChapterSynopsis?,
    val review: AgentResult.ReviewResult?,
    val events: List<PipelineEvent> = emptyList(),
)

class AgentPipeline(
    private val outlineAgent: OutlineAgent,
    private val chapterSynopsisAgent: ChapterSynopsisAgent,
    private val sceneExpansionAgent: SceneExpansionAgent,
    private val reviewAgent: ReviewAgent,
    private val continuityAgent: ContinuityAgent,
    private val bibleMerger: BibleMerger,
    private val rollingSummaryAgent: RollingSummaryAgent? = null,
    private val config: PipelineConfig? = null,
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
                result.usage?.let { events.add(PipelineEvent.UsageRecorded(it)) }
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
        reviewFeedback: String? = null,
    ): ChapterSynopsisPipelineResult {
        val events = mutableListOf<PipelineEvent>()
        val synopsisResult = if (reviewFeedback.isNullOrBlank()) {
            chapterSynopsisAgent.generate(
                novel = novel,
                chapter = chapter,
                chapters = chapters,
                previousChapterEnding = trimContinuityContext(previousChapterEnding),
            )
        } else {
            regenerateChapterSynopsis(novel, chapter, previousChapterEnding, reviewFeedback)
        }

        val synopsis = when (synopsisResult) {
            is AgentResult.SynopsisResult -> {
                synopsisResult.usage?.let { events.add(PipelineEvent.UsageRecorded(it)) }
                events.add(PipelineEvent.SynopsisGenerated(synopsisResult.synopsis))
                synopsisResult.synopsis
            }
            is AgentResult.Error -> return ChapterSynopsisPipelineResult(null, null, events)
            else -> return ChapterSynopsisPipelineResult(null, null, events)
        }

        val outline = novel.outline ?: return ChapterSynopsisPipelineResult(synopsis, null, events)
        val brief = outline.chapterBriefs.find { it.chapterIndex == chapter.order }
            ?: return ChapterSynopsisPipelineResult(synopsis, null, events)

        val reviewResult = reviewAgent.review(
            parentDirective = "Chapter ${chapter.order}: ${brief.title}\nPlot beats: ${brief.plotBeats}\nPurpose: ${brief.purposeInStory}",
            childOutput = synopsis.sceneBreakdowns.joinToString("\n") { it.synopsis },
            reviewType = ReviewType.SYNOPSIS_AGAINST_OUTLINE,
        )

        return when (reviewResult) {
            is AgentResult.ReviewResult -> {
                reviewResult.usage?.let { events.add(PipelineEvent.UsageRecorded(it)) }
                ChapterSynopsisPipelineResult(synopsis, reviewResult, events)
            }
            else -> ChapterSynopsisPipelineResult(synopsis, null, events)
        }
    }

    suspend fun generateScene(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        previousSceneEnding: String? = null,
        reviewFeedback: String? = null,
        rollingChapterSummary: String? = null,
        referenceProseStyle: String? = null,
    ): Flow<PipelineEvent> = flow {
        emit(PipelineEvent.Step("scene_expansion", "Generating scene ${scene.order} text"))

        val result = if (reviewFeedback.isNullOrBlank()) {
            sceneExpansionAgent.generate(
                novel = novel,
                chapter = chapter,
                scene = scene,
                previousSceneEnding = trimContinuityContext(previousSceneEnding),
                rollingChapterSummary = rollingChapterSummary,
                referenceProseStyle = referenceProseStyle,
            )
        } else {
            regenerateScene(novel, chapter, scene, previousSceneEnding, reviewFeedback, rollingChapterSummary, referenceProseStyle)
        }

        val text = when (result) {
            is AgentResult.SceneTextResult -> {
                emit(PipelineEvent.SceneTextComplete(result.text, result.wordCount))
                result.usage?.let { emit(PipelineEvent.UsageRecorded(it)) }
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

        finishSceneText(novel, chapter, scene, text, rollingChapterSummary)
    }

    fun finalizeSceneText(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        text: String,
        rollingChapterSummary: String? = null,
    ): Flow<PipelineEvent> = flow {
        finishSceneText(novel, chapter, scene, text, rollingChapterSummary)
    }

    private suspend fun FlowCollector<PipelineEvent>.finishSceneText(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        text: String,
        rollingChapterSummary: String? = null,
    ) {
        val synopsis = chapter.synopsis ?: return
        val breakdown = synopsis.sceneBreakdowns.find { it.sceneIndex == scene.order } ?: return

        emit(PipelineEvent.Step("review", "Reviewing scene against synopsis"))
        val reviewResult = reviewAgent.review(
            parentDirective = breakdown.synopsis,
            childOutput = text,
            reviewType = ReviewType.TEXT_AGAINST_SYNOPSIS,
        )
        when (reviewResult) {
            is AgentResult.ReviewResult -> {
                emit(PipelineEvent.ReviewComplete(reviewResult))
                reviewResult.usage?.let { emit(PipelineEvent.UsageRecorded(it)) }
                if (!reviewResult.passed) return
            }
            else -> return
        }

        updateRollingSummary(rollingChapterSummary, breakdown.synopsis, text)

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
                    novel.bible, continuityResult.bibleDiff, chapter.id, scene.id,
                )
                continuityResult.usage?.let { emit(PipelineEvent.UsageRecorded(it)) }
                emit(PipelineEvent.BibleUpdated(updatedBible))
            }
            is AgentResult.Error -> {
                emit(PipelineEvent.Error("Continuity extraction failed: ${continuityResult.message}"))
            }
            else -> Unit
        }
    }

    private suspend fun FlowCollector<PipelineEvent>.updateRollingSummary(
        previousSummary: String?,
        sceneSynopsis: String,
        sceneText: String,
    ) {
        val agent = rollingSummaryAgent ?: return
        emit(PipelineEvent.Step("rolling_chapter_summary", "Updating chapter tension summary"))
        when (val result = agent.update(previousSummary, sceneSynopsis, sceneText)) {
            is AgentResult.RollingSummaryResult -> {
                result.usage?.let { emit(PipelineEvent.UsageRecorded(it)) }
                emit(PipelineEvent.RollingSummaryUpdated(result.summary))
            }
            is AgentResult.Error -> emit(PipelineEvent.Error("Rolling summary failed: ${result.message}", result.cause))
            else -> Unit
        }
    }

    private suspend fun regenerateChapterSynopsis(
        novel: Novel,
        chapter: Chapter,
        previousChapterEnding: String?,
        reviewFeedback: String,
    ): AgentResult {
        val outline = novel.outline ?: return AgentResult.Error("Novel has no outline")
        val brief = outline.chapterBriefs.find { it.chapterIndex == chapter.order }
            ?: return AgentResult.Error("No brief found for chapter ${chapter.order}")
        val prompt = ChapterSynopsisPrompt()
        val input = ChapterSynopsisInput(
            chapterBrief = brief,
            previousChapterBrief = outline.chapterBriefs.find { it.chapterIndex == chapter.order - 1 },
            nextChapterBrief = outline.chapterBriefs.find { it.chapterIndex == chapter.order + 1 },
            premise = outline.premise,
            majorPlotPoints = outline.majorPlotPoints,
            relevantCharacters = novel.bible.characters,
            relevantLocations = novel.bible.locations,
            previousChapterEnding = trimContinuityContext(previousChapterEnding),
        )
        val userPrompt = buildString {
            append(prompt.buildUserPrompt(input))
            appendLine()
            appendLine("REVIEW FEEDBACK TO FIX:")
            appendLine(reviewFeedback)
            appendLine()
            appendLine("Regenerate the chapter synopsis JSON. Fix the review issues without dropping valid story beats.")
        }
        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(LlmMessage(MessageRole.USER, userPrompt)),
            model = chapterSynopsisAgent.model,
            maxTokens = 4096,
            temperature = 0.7,
            cacheableSystemPrompt = true,
        )
        return try {
            val response = chapterSynopsisAgent.llmClient.complete(request)
            val baseUsage = response.toAgentUsage(chapterSynopsisAgent.name, chapterSynopsisAgent.model)
            parseOrRepairJsonOutput(
                rawContent = response.content,
                llmClient = chapterSynopsisAgent.llmClient,
                model = chapterSynopsisAgent.model,
                agentName = chapterSynopsisAgent.name,
                parse = prompt::parseOutput,
            ).fold(
                onSuccess = { (synopsis, repairUsage) ->
                    AgentResult.SynopsisResult(synopsis, baseUsage.plusRepair(repairUsage))
                },
                onFailure = { AgentResult.Error("Failed to parse retried synopsis: ${it.message}", it) },
            )
        } catch (e: Exception) {
            AgentResult.Error("LLM retry failed: ${e.message}", e)
        }
    }

    private suspend fun regenerateScene(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        previousSceneEnding: String?,
        reviewFeedback: String,
        rollingChapterSummary: String?,
        referenceProseStyle: String?,
    ): AgentResult {
        val synopsis = chapter.synopsis ?: return AgentResult.Error("Chapter has no synopsis")
        val breakdown = synopsis.sceneBreakdowns.find { it.sceneIndex == scene.order }
            ?: return AgentResult.Error("No breakdown found for scene ${scene.order}")
        val prompt = SceneExpansionPrompt()
        val input = SceneExpansionInput(
            sceneBreakdown = breakdown,
            chapterGoal = synopsis.chapterGoal,
            relevantCharacters = novel.bible.characters,
            relevantLocations = novel.bible.locations,
            relevantWorldRules = novel.bible.worldRules,
            previousSceneEnding = trimContinuityContext(previousSceneEnding),
            styleGuide = novel.styleGuide,
            rollingChapterSummary = rollingChapterSummary,
            referenceProseStyle = referenceProseStyle,
        )
        val userPrompt = buildString {
            append(prompt.buildUserPrompt(input))
            appendLine()
            appendLine("REVIEW FEEDBACK TO FIX:")
            appendLine(reviewFeedback)
            appendLine()
            appendLine("Regenerate the prose. Preserve useful material, but fix the review issues and output only story text.")
        }
        val request = LlmRequest(
            systemPrompt = prompt.buildSystemPrompt(),
            messages = listOf(LlmMessage(MessageRole.USER, userPrompt)),
            model = sceneExpansionAgent.model,
            maxTokens = breakdown.targetWordCount * 2,
            temperature = 0.85,
            cacheableSystemPrompt = true,
        )
        return try {
            val response = sceneExpansionAgent.llmClient.complete(request)
            prompt.parseOutput(response.content).fold(
                onSuccess = {
                    AgentResult.SceneTextResult(
                        text = it,
                        wordCount = it.split(Regex("\\s+")).count { word -> word.isNotBlank() },
                        usage = response.toAgentUsage(sceneExpansionAgent.name, sceneExpansionAgent.model),
                    )
                },
                onFailure = { AgentResult.Error("Failed to process retried scene text: ${it.message}", it) },
            )
        } catch (e: Exception) {
            AgentResult.Error("LLM retry failed: ${e.message}", e)
        }
    }

    suspend fun streamSceneEvents(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        previousSceneEnding: String? = null,
        rollingChapterSummary: String? = null,
        referenceProseStyle: String? = null,
    ): Flow<PipelineEvent> = flow {
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        sceneExpansionAgent.streamChunks(
            novel = novel,
            chapter = chapter,
            scene = scene,
            previousSceneEnding = trimContinuityContext(previousSceneEnding),
            rollingChapterSummary = rollingChapterSummary,
            referenceProseStyle = referenceProseStyle,
        ).collect { chunk ->
            if (chunk.delta.isNotEmpty()) {
                emit(PipelineEvent.SceneTextDelta(chunk.delta))
            }
            inputTokens = chunk.inputTokens ?: inputTokens
            outputTokens = chunk.outputTokens ?: outputTokens
            if (chunk.isComplete && inputTokens != null && outputTokens != null) {
                emit(
                    PipelineEvent.UsageRecorded(
                        AgentUsage(
                            agentName = sceneExpansionAgent.name,
                            model = sceneExpansionAgent.model,
                            inputTokens = inputTokens ?: 0,
                            outputTokens = outputTokens ?: 0,
                        ),
                    ),
                )
            }
        }
    }

    suspend fun streamScene(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        previousSceneEnding: String? = null,
        rollingChapterSummary: String? = null,
        referenceProseStyle: String? = null,
    ): Flow<String> {
        return sceneExpansionAgent.stream(
            novel = novel,
            chapter = chapter,
            scene = scene,
            previousSceneEnding = trimContinuityContext(previousSceneEnding),
            rollingChapterSummary = rollingChapterSummary,
            referenceProseStyle = referenceProseStyle,
        )
    }
}
