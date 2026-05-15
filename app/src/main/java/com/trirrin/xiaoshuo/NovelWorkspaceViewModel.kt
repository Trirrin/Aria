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
import com.trirrin.xiaoshuo.model.ChapterStatus
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.NovelStatus
import com.trirrin.xiaoshuo.model.Scene
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
            val chapters = generated.chapterBriefs.map { brief ->
                Chapter(
                    novelId = novel.id,
                    order = brief.chapterIndex,
                    title = brief.title,
                    status = ChapterStatus.PENDING,
                )
            }
            chapters.forEach { novelRepository.upsertChapter(it) }
            selectedChapterId.value = chapters.firstOrNull()?.id
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
            generated.sceneBreakdowns.forEach { breakdown ->
                novelRepository.upsertScene(
                    Scene(
                        novelId = novel.id,
                        chapterId = chapter.id,
                        order = breakdown.sceneIndex,
                        synopsis = breakdown.synopsis,
                        status = SceneStatus.PENDING,
                    ),
                )
            }
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
