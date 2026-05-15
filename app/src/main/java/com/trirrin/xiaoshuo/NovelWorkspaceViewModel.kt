package com.trirrin.xiaoshuo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trirrin.xiaoshuo.agent.AgentPipeline
import com.trirrin.xiaoshuo.agent.PipelineEvent
import com.trirrin.xiaoshuo.data.GenerationSettings
import com.trirrin.xiaoshuo.data.GenerationSettingsRepository
import com.trirrin.xiaoshuo.data.NovelRepository
import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.ChapterBrief
import com.trirrin.xiaoshuo.model.ChapterStatus
import com.trirrin.xiaoshuo.model.ChapterSynopsis
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.model.NovelStatus
import com.trirrin.xiaoshuo.model.PlotPoint
import com.trirrin.xiaoshuo.model.Scene
import com.trirrin.xiaoshuo.model.SceneBreakdown
import com.trirrin.xiaoshuo.model.SceneStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class NovelWorkspaceViewModel(
    private val novelRepository: NovelRepository,
    private val settingsRepository: GenerationSettingsRepository,
    private val pipelineFactory: (GenerationSettings) -> AgentPipeline,
) : ViewModel() {
    private val workflow = MutableStateFlow(WorkflowState())
    private val selectedNovelId = MutableStateFlow<String?>(null)
    private val selectedChapterId = MutableStateFlow<String?>(null)
    private val selectedSceneId = MutableStateFlow<String?>(null)

    private val novels = novelRepository.observeNovels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selection = combine(novels, selectedNovelId, selectedChapterId, selectedSceneId) { novelList, novelId, chapterId, sceneId ->
        val selectedNovel = novelList.firstOrNull { it.id == novelId } ?: novelList.firstOrNull()
        SelectionState(
            selectedNovel = selectedNovel,
            selectedChapterId = chapterId,
            selectedSceneId = sceneId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectionState())

    private val chapters = selection.flatMapLatest { state ->
        state.selectedNovel?.let { novelRepository.observeChapters(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedChapter = combine(chapters, selection) { chapterList, state ->
        chapterList.firstOrNull { it.id == state.selectedChapterId } ?: chapterList.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val scenes = selectedChapter.flatMapLatest { chapter ->
        chapter?.let { novelRepository.observeScenes(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GenerationSettings())

    private val workspaceData = combine(novels, selection, chapters, selectedChapter, scenes) { novelList, selectionState, chapterList, chapter, sceneList ->
        val selectedScene = sceneList.firstOrNull { it.id == selectionState.selectedSceneId } ?: sceneList.firstOrNull()
        WorkspaceData(
            novels = novelList,
            selectedNovel = selectionState.selectedNovel,
            chapters = chapterList,
            selectedChapter = chapter,
            scenes = sceneList,
            selectedScene = selectedScene,
        )
    }

    val uiState: StateFlow<NovelWorkspaceUiState> = combine(workspaceData, settings, workflow) { data, settings, workflow ->
        NovelWorkspaceUiState(
            novels = data.novels,
            selectedNovel = data.selectedNovel,
            chapters = data.chapters,
            selectedChapter = data.selectedChapter,
            scenes = data.scenes,
            selectedScene = data.selectedScene,
            settings = settings,
            workflow = workflow,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NovelWorkspaceUiState(settings = GenerationSettings()),
    )

    fun createNovel(title: String, concept: String, genre: Genre, themes: String) {
        val cleanTitle = title.trim()
        val cleanConcept = concept.trim()
        if (cleanTitle.isBlank() || cleanConcept.isBlank()) {
            setError("Title and concept are required")
            return
        }

        viewModelScope.launch {
            val now = Clock.System.now()
            val novel = Novel(
                title = cleanTitle,
                genre = genre,
                concept = cleanConcept,
                themes = themes.split(',').map { it.trim() }.filter { it.isNotBlank() },
                createdAt = now,
                updatedAt = now,
            )
            novelRepository.upsertNovel(novel)
            selectedNovelId.value = novel.id
            selectedChapterId.value = null
            selectedSceneId.value = null
            workflow.value = WorkflowState(events = listOf("Created ${novel.title}"))
        }
    }

    fun selectNovel(id: String) {
        selectedNovelId.value = id
        selectedChapterId.value = null
        selectedSceneId.value = null
    }

    fun selectChapter(id: String) {
        selectedChapterId.value = id
        selectedSceneId.value = null
    }

    fun selectScene(id: String) {
        selectedSceneId.value = id
    }

    fun saveSettings(settings: GenerationSettings) {
        viewModelScope.launch {
            settingsRepository.save(settings)
            appendEvent("Settings saved")
        }
    }

    fun saveOutlineDraft(draft: OutlineDraft) {
        val novel = uiState.value.selectedNovel ?: run {
            setError("Create or select a novel first")
            return
        }
        val outline = draft.toOutline()
        if (outline.premise.isBlank() || outline.chapterBriefs.isEmpty()) {
            setError("Premise and at least one chapter are required")
            return
        }

        viewModelScope.launch {
            val now = Clock.System.now()
            novelRepository.upsertNovel(
                novel.copy(
                    outline = outline,
                    status = NovelStatus.OUTLINE_COMPLETE,
                    updatedAt = now,
                ),
            )
            saveChapterShells(novel.id, outline.chapterBriefs)
            selectedChapterId.value = selectedChapterId.value ?: outline.chapterBriefs.firstOrNull()?.let { brief ->
                novelRepository.getChapters(novel.id).firstOrNull { it.order == brief.chapterIndex }?.id
            }
            appendEvent("Outline edits saved")
        }
    }

    fun saveChapterSynopsisDraft(draft: ChapterSynopsisDraft) {
        val novel = uiState.value.selectedNovel ?: run {
            setError("Create or select a novel first")
            return
        }
        val chapter = uiState.value.selectedChapter ?: run {
            setError("Select a chapter first")
            return
        }
        val synopsis = draft.toSynopsis()
        if (synopsis.chapterGoal.isBlank() || synopsis.sceneBreakdowns.isEmpty()) {
            setError("Chapter goal and at least one scene are required")
            return
        }

        viewModelScope.launch {
            novelRepository.upsertChapter(
                chapter.copy(
                    synopsis = synopsis,
                    status = ChapterStatus.SYNOPSIS_GENERATED,
                ),
            )
            saveSceneShells(novel.id, chapter.id, synopsis.sceneBreakdowns)
            selectedSceneId.value = selectedSceneId.value ?: novelRepository.getScenes(chapter.id).firstOrNull()?.id
            appendEvent("Chapter synopsis edits saved")
        }
    }

    fun saveSceneText(text: String) {
        val scene = uiState.value.selectedScene ?: run {
            setError("Select a scene first")
            return
        }
        viewModelScope.launch {
            novelRepository.upsertScene(
                scene.copy(
                    text = text,
                    wordCount = countWords(text),
                    status = if (text.isBlank()) SceneStatus.PENDING else SceneStatus.GENERATED,
                ),
            )
            appendEvent("Scene text saved")
        }
    }

    fun generateOutline() {
        runPipelineStep("Generating outline") { novel, settings ->
            require(settings.apiKey.isNotBlank()) { "API key is required before generation" }
            val pipeline = pipelineFactory(settings)
            val (outline, events) = pipeline.generateOutline(novel)
            events.forEach { appendPipelineEvent(it) }
            val generated = outline ?: error("Outline generation failed")
            val updated = novel.copy(
                outline = generated,
                status = NovelStatus.OUTLINE_COMPLETE,
                updatedAt = Clock.System.now(),
            )
            novelRepository.upsertNovel(updated)
            saveChapterShells(novel.id, generated.chapterBriefs)
            val chapters = novelRepository.getChapters(novel.id)
            selectedChapterId.value = chapters.firstOrNull { it.order == generated.chapterBriefs.firstOrNull()?.chapterIndex }?.id
            selectedSceneId.value = null
            appendEvent("Saved outline and ${chapters.size} chapter shells")
        }
    }

    fun generateSelectedSynopsis() {
        runPipelineStep("Generating chapter synopsis") { novel, settings ->
            require(settings.apiKey.isNotBlank()) { "API key is required before generation" }
            require(novel.outline != null) { "Generate an outline first" }
            val chapters = novelRepository.getChapters(novel.id)
            val currentChapterId = uiState.value.selectedChapter?.id
            val chapter = chapters.firstOrNull { it.id == currentChapterId } ?: chapters.firstOrNull() ?: error("No chapter shell exists")
            val pipeline = pipelineFactory(settings)
            val (synopsis, review) = pipeline.generateChapterSynopsis(novel, chapter, chapters)
            val generated = synopsis ?: error("Synopsis generation failed")
            val updatedChapter = chapter.copy(
                synopsis = generated,
                reviewNotes = review?.issues?.joinToString("\n"),
                status = ChapterStatus.SYNOPSIS_GENERATED,
            )
            novelRepository.upsertChapter(updatedChapter)
            saveSceneShells(novel.id, chapter.id, generated.sceneBreakdowns)
            selectedChapterId.value = chapter.id
            selectedSceneId.value = null
            appendEvent("Saved chapter synopsis and ${generated.sceneBreakdowns.size} scene shells")
        }
    }

    fun generateSelectedScene() {
        runPipelineStep("Generating scene") { novel, settings ->
            require(settings.apiKey.isNotBlank()) { "API key is required before generation" }
            val chapters = novelRepository.getChapters(novel.id)
            val currentChapterId = uiState.value.selectedChapter?.id
            val chapter = chapters.firstOrNull { it.id == currentChapterId }
                ?: chapters.firstOrNull { it.synopsis != null }
                ?: error("Generate a chapter synopsis first")
            val scenes = novelRepository.getScenes(chapter.id)
            val currentSceneId = uiState.value.selectedScene?.id
            val scene = scenes.firstOrNull { it.id == currentSceneId } ?: scenes.firstOrNull() ?: error("No scene shell exists")
            val previousSceneEnding = scenes
                .filter { it.order < scene.order && it.text.isNotBlank() }
                .maxByOrNull { it.order }
                ?.text
                ?.split(Regex("\\s+"))
                ?.takeLast(200)
                ?.joinToString(" ")
            val pipeline = pipelineFactory(settings)
            var generatedText = scene.text
            var generatedWordCount = scene.wordCount
            var updatedBible = novel.bible

            novelRepository.upsertScene(scene.copy(status = SceneStatus.GENERATING))
            pipeline.generateScene(novel, chapter, scene, previousSceneEnding).collect { event ->
                appendPipelineEvent(event)
                when (event) {
                    is PipelineEvent.SceneTextComplete -> {
                        generatedText = event.text
                        generatedWordCount = event.wordCount
                    }
                    is PipelineEvent.BibleUpdated -> updatedBible = event.bible
                    else -> Unit
                }
            }

            novelRepository.upsertScene(
                scene.copy(
                    text = generatedText,
                    wordCount = generatedWordCount,
                    status = SceneStatus.GENERATED,
                ),
            )
            novelRepository.upsertNovel(novel.copy(bible = updatedBible, updatedAt = Clock.System.now()))
            selectedChapterId.value = chapter.id
            selectedSceneId.value = scene.id
            appendEvent("Saved scene text and Bible updates")
        }
    }

    private suspend fun saveChapterShells(novelId: String, chapterBriefs: List<ChapterBrief>) {
        val existingByOrder = novelRepository.getChapters(novelId).associateBy { it.order }
        chapterBriefs.forEach { brief ->
            val existing = existingByOrder[brief.chapterIndex]
            novelRepository.upsertChapter(
                existing?.copy(title = brief.title)
                    ?: Chapter(
                        novelId = novelId,
                        order = brief.chapterIndex,
                        title = brief.title,
                        status = ChapterStatus.PENDING,
                    ),
            )
        }
    }

    private suspend fun saveSceneShells(novelId: String, chapterId: String, breakdowns: List<SceneBreakdown>) {
        val existingByOrder = novelRepository.getScenes(chapterId).associateBy { it.order }
        breakdowns.forEach { breakdown ->
            val existing = existingByOrder[breakdown.sceneIndex]
            novelRepository.upsertScene(
                existing?.copy(synopsis = breakdown.synopsis)
                    ?: Scene(
                        novelId = novelId,
                        chapterId = chapterId,
                        order = breakdown.sceneIndex,
                        synopsis = breakdown.synopsis,
                        status = SceneStatus.PENDING,
                    ),
            )
        }
    }

    private fun runPipelineStep(
        busyMessage: String,
        block: suspend (Novel, GenerationSettings) -> Unit,
    ) {
        val novel = uiState.value.selectedNovel ?: run {
            setError("Create or select a novel first")
            return
        }
        val settings = uiState.value.settings
        viewModelScope.launch {
            workflow.update { it.copy(isBusy = true, error = null, events = it.events + busyMessage) }
            try {
                block(novel, settings)
            } catch (e: Exception) {
                setError(e.message ?: "Generation failed")
            } finally {
                workflow.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun appendPipelineEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.Step -> appendEvent(event.description)
            is PipelineEvent.OutlineGenerated -> appendEvent("Outline generated")
            is PipelineEvent.SynopsisGenerated -> appendEvent("Synopsis generated")
            is PipelineEvent.SceneTextComplete -> appendEvent("Scene text generated: ${event.wordCount} words")
            is PipelineEvent.ReviewComplete -> appendEvent("Review score: ${event.result.score}/10")
            is PipelineEvent.BibleUpdated -> appendEvent("Bible updated")
            is PipelineEvent.Error -> setError(event.message)
            is PipelineEvent.SceneTextDelta -> Unit
        }
    }

    private fun appendEvent(message: String) {
        workflow.update { state -> state.copy(events = (state.events + message).takeLast(12)) }
    }

    private fun setError(message: String) {
        workflow.update { state -> state.copy(error = message, events = (state.events + "Error: $message").takeLast(12)) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NovelWorkspaceViewModel(
                novelRepository = container.novelRepository,
                settingsRepository = container.settingsRepository,
                pipelineFactory = container::createPipeline,
            ) as T
        }
    }
}

data class OutlineDraft(
    val premise: String,
    val majorPlotPoints: String,
    val characterArcs: String,
    val thematicStructure: String,
    val chapters: String,
)

data class ChapterSynopsisDraft(
    val chapterGoal: String,
    val sceneBreakdowns: String,
    val chapterEnding: String,
    val transitionNotes: String,
)

fun NovelOutline.toDraft(): OutlineDraft {
    return OutlineDraft(
        premise = premise,
        majorPlotPoints = majorPlotPoints.joinToString("\n") { point ->
            listOf(point.name, point.position.toString(), point.description).joinToString(" | ")
        },
        characterArcs = characterArcs.joinToString("\n"),
        thematicStructure = thematicStructure,
        chapters = chapterBriefs.joinToString("\n") { brief ->
            listOf(brief.chapterIndex.toString(), brief.title, brief.plotBeats, brief.purposeInStory).joinToString(" | ")
        },
    )
}

fun ChapterSynopsis.toDraft(): ChapterSynopsisDraft {
    return ChapterSynopsisDraft(
        chapterGoal = chapterGoal,
        sceneBreakdowns = sceneBreakdowns.joinToString("\n") { scene ->
            listOf(scene.sceneIndex.toString(), scene.targetWordCount.toString(), scene.synopsis).joinToString(" | ")
        },
        chapterEnding = chapterEnding,
        transitionNotes = transitionNotes,
    )
}

private fun OutlineDraft.toOutline(): NovelOutline {
    val chapters = chapters.lines().mapNotNull { line -> parseChapterBrief(line) }
    return NovelOutline(
        premise = premise.trim(),
        majorPlotPoints = majorPlotPoints.lines().mapNotNull { line -> parsePlotPoint(line) },
        characterArcs = characterArcs.lines().map { it.trim() }.filter { it.isNotBlank() },
        thematicStructure = thematicStructure.trim(),
        chapterCount = chapters.size,
        chapterBriefs = chapters,
    )
}

private fun ChapterSynopsisDraft.toSynopsis(): ChapterSynopsis {
    return ChapterSynopsis(
        chapterGoal = chapterGoal.trim(),
        sceneBreakdowns = sceneBreakdowns.lines().mapNotNull { line -> parseSceneBreakdown(line) },
        chapterEnding = chapterEnding.trim(),
        transitionNotes = transitionNotes.trim(),
    )
}

private fun parsePlotPoint(line: String): PlotPoint? {
    val parts = splitDraftLine(line, 3)
    if (parts.size < 3) return null
    return PlotPoint(
        name = parts[0],
        position = parts[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
        description = parts[2],
    )
}

private fun parseChapterBrief(line: String): ChapterBrief? {
    val parts = splitDraftLine(line, 4)
    if (parts.size < 4) return null
    return ChapterBrief(
        chapterIndex = parts[0].toIntOrNull() ?: return null,
        title = parts[1],
        plotBeats = parts[2],
        purposeInStory = parts[3],
    )
}

private fun parseSceneBreakdown(line: String): SceneBreakdown? {
    val parts = splitDraftLine(line, 3)
    if (parts.size < 3) return null
    return SceneBreakdown(
        sceneIndex = parts[0].toIntOrNull() ?: return null,
        targetWordCount = parts[1].toIntOrNull() ?: 2500,
        synopsis = parts[2],
    )
}

private fun splitDraftLine(line: String, limit: Int): List<String> {
    return line.split("|", limit = limit).map { it.trim() }.filter { it.isNotBlank() }
}

private fun countWords(text: String): Int {
    return text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
}

private data class WorkspaceData(
    val novels: List<Novel> = emptyList(),
    val selectedNovel: Novel? = null,
    val chapters: List<Chapter> = emptyList(),
    val selectedChapter: Chapter? = null,
    val scenes: List<Scene> = emptyList(),
    val selectedScene: Scene? = null,
)

private data class SelectionState(
    val selectedNovel: Novel? = null,
    val selectedChapterId: String? = null,
    val selectedSceneId: String? = null,
)

data class NovelWorkspaceUiState(
    val novels: List<Novel> = emptyList(),
    val selectedNovel: Novel? = null,
    val chapters: List<Chapter> = emptyList(),
    val selectedChapter: Chapter? = null,
    val scenes: List<Scene> = emptyList(),
    val selectedScene: Scene? = null,
    val settings: GenerationSettings,
    val workflow: WorkflowState = WorkflowState(),
)

data class WorkflowState(
    val isBusy: Boolean = false,
    val error: String? = null,
    val events: List<String> = emptyList(),
)
