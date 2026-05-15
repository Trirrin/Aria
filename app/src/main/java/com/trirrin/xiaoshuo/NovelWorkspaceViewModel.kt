package com.trirrin.xiaoshuo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trirrin.xiaoshuo.agent.AgentPipeline
import com.trirrin.xiaoshuo.agent.AgentUsage
import com.trirrin.xiaoshuo.agent.AgentResult
import com.trirrin.xiaoshuo.agent.PipelineEvent
import com.trirrin.xiaoshuo.data.GenerationSettings
import com.trirrin.xiaoshuo.data.GenerationSettingsRepository
import com.trirrin.xiaoshuo.data.NovelRepository
import com.trirrin.xiaoshuo.data.RevisionSnapshot
import com.trirrin.xiaoshuo.data.TokenUsageRecord
import com.trirrin.xiaoshuo.model.BibleConflict
import com.trirrin.xiaoshuo.model.BibleConflictSection
import com.trirrin.xiaoshuo.model.BibleEntrySource
import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.ChapterBrief
import com.trirrin.xiaoshuo.model.ChapterStatus
import com.trirrin.xiaoshuo.model.ChapterSynopsis
import com.trirrin.xiaoshuo.model.CharacterEntry
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.LocationEntry
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.model.NovelStatus
import com.trirrin.xiaoshuo.model.PlotPoint
import com.trirrin.xiaoshuo.model.ReviewReport
import com.trirrin.xiaoshuo.model.ReviewStatus
import com.trirrin.xiaoshuo.model.Scene
import com.trirrin.xiaoshuo.model.SceneTextSource
import com.trirrin.xiaoshuo.model.ThemeEntry
import com.trirrin.xiaoshuo.model.TimelineEvent
import com.trirrin.xiaoshuo.model.WorldRule
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NovelWorkspaceViewModel(
    private val novelRepository: NovelRepository,
    private val settingsRepository: GenerationSettingsRepository,
    private val pipelineFactory: (GenerationSettings) -> AgentPipeline,
) : ViewModel() {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
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

    private val allScenes = selection.flatMapLatest { state ->
        state.selectedNovel?.let { novelRepository.observeScenesForNovel(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val revisionSnapshots = selection.flatMapLatest { state ->
        state.selectedNovel?.let { novelRepository.observeRevisionSnapshots(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val tokenUsage = selection.flatMapLatest { state ->
        state.selectedNovel?.let { novelRepository.observeTokenUsage(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GenerationSettings())

    private val selectedChapterData = combine(novels, selection, chapters, selectedChapter, scenes) { novelList, selectionState, chapterList, chapter, sceneList ->
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

    private val sceneAndHistoryData = combine(allScenes, revisionSnapshots, tokenUsage) { allSceneList, snapshots, usage ->
        Triple(allSceneList, snapshots, usage)
    }

    private val workspaceData = combine(selectedChapterData, sceneAndHistoryData) { data, history ->
        data.copy(
            allScenes = history.first,
            revisionSnapshots = history.second,
            tokenUsage = history.third,
        )
    }
    val uiState: StateFlow<NovelWorkspaceUiState> = combine(workspaceData, settings, workflow) { data, settings, workflow ->
        NovelWorkspaceUiState(
            novels = data.novels,
            selectedNovel = data.selectedNovel,
            chapters = data.chapters,
            selectedChapter = data.selectedChapter,
            scenes = data.scenes,
            allScenes = data.allScenes,
            revisionSnapshots = data.revisionSnapshots,
            tokenUsage = data.tokenUsage,
            selectedScene = data.selectedScene,
            settings = settings,
            workflow = workflow,
            bibleWarnings = data.selectedNovel?.bible?.warnings().orEmpty(),
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

    fun restoreRevisionSnapshot(id: String) {
        viewModelScope.launch {
            val snapshot = novelRepository.getRevisionSnapshot(id) ?: run {
                setError("Snapshot not found")
                return@launch
            }
            restoreSnapshot(snapshot)
            appendEvent("Restored ${snapshot.label}")
        }
    }

    fun deleteRevisionSnapshot(id: String) {
        viewModelScope.launch {
            novelRepository.deleteRevisionSnapshot(id)
            appendEvent("Snapshot deleted")
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
            novel.outline?.let {
                saveSnapshot(novel.id, SNAPSHOT_OUTLINE, novel.id, "Outline before edit", it)
            }
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
            chapter.synopsis?.let {
                saveSnapshot(novel.id, SNAPSHOT_CHAPTER_SYNOPSIS, chapter.id, "Synopsis before edit", it)
            }
            novelRepository.upsertChapter(
                chapter.copy(
                    synopsis = synopsis,
                    reviewReport = chapter.reviewReport?.copy(status = ReviewStatus.MANUAL_EDIT),
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
            if (scene.text.isNotBlank()) {
                saveSnapshot(scene.novelId, SNAPSHOT_SCENE, scene.id, "Scene before edit", scene)
            }
            novelRepository.upsertScene(
                scene.copy(
                    text = text,
                    wordCount = countWords(text),
                    reviewReport = scene.reviewReport?.copy(status = ReviewStatus.MANUAL_EDIT),
                    status = if (text.isBlank()) SceneStatus.PENDING else SceneStatus.GENERATED,
                    textSource = if (text.isBlank()) SceneTextSource.EMPTY else SceneTextSource.EDITED,
                ),
            )
            appendEvent("Scene text saved")
        }
    }

    fun saveBibleEntry(section: BibleSection, draft: BibleEntryDraft) {
        val novel = uiState.value.selectedNovel ?: run {
            setError("Create or select a novel first")
            return
        }
        if (draft.primary.trim().isBlank()) {
            setError("Bible entry title is required")
            return
        }

        viewModelScope.launch {
            val updatedBible = novel.bible.upsert(section, draft)
            saveSnapshot(novel.id, SNAPSHOT_BIBLE, novel.id, "Bible before edit", novel.bible)
            novelRepository.upsertNovel(novel.copy(bible = updatedBible, updatedAt = Clock.System.now()))
            appendEvent("Bible ${section.label.lowercase()} saved")
        }
    }

    fun deleteBibleEntry(section: BibleSection, id: String) {
        val novel = uiState.value.selectedNovel ?: run {
            setError("Create or select a novel first")
            return
        }
        if (id.isBlank()) {
            setError("Select a Bible entry first")
            return
        }

        viewModelScope.launch {
            saveSnapshot(novel.id, SNAPSHOT_BIBLE, novel.id, "Bible before delete", novel.bible)
            novelRepository.upsertNovel(
                novel.copy(
                    bible = novel.bible.delete(section, id),
                    updatedAt = Clock.System.now(),
                ),
            )
            appendEvent("Bible ${section.label.lowercase()} deleted")
        }
    }

    fun resolveBibleConflict(id: String, useIncoming: Boolean) {
        val novel = uiState.value.selectedNovel ?: run {
            setError("Create or select a novel first")
            return
        }
        val conflict = novel.bible.conflicts.firstOrNull { it.id == id } ?: run {
            setError("Bible conflict not found")
            return
        }

        viewModelScope.launch {
            saveSnapshot(novel.id, SNAPSHOT_BIBLE, novel.id, "Bible before conflict resolution", novel.bible)
            val bible = if (useIncoming) novel.bible.applyConflict(conflict) else novel.bible
            novelRepository.upsertNovel(
                novel.copy(
                    bible = bible.copy(conflicts = bible.conflicts.filterNot { it.id == id }),
                    updatedAt = Clock.System.now(),
                ),
            )
            appendEvent("Bible conflict resolved")
        }
    }

    fun generateOutline() {
        runPipelineStep("Generating outline") { novel, settings ->
            require(settings.apiKey.isNotBlank()) { "API key is required before generation" }
            val pipeline = pipelineFactory(settings)
            val (outline, events) = pipeline.generateOutline(novel)
            events.forEach { recordPipelineEvent(novel.id, settings, it) }
            val generated = outline ?: error("Outline generation failed")
            val updated = novel.copy(
                outline = generated,
                status = NovelStatus.OUTLINE_COMPLETE,
                updatedAt = Clock.System.now(),
            )
            novel.outline?.let {
                saveSnapshot(novel.id, SNAPSHOT_OUTLINE, novel.id, "Outline before generation", it)
            }
            novelRepository.upsertNovel(updated)
            saveChapterShells(novel.id, generated.chapterBriefs)
            val chapters = novelRepository.getChapters(novel.id)
            selectedChapterId.value = chapters.firstOrNull { it.order == generated.chapterBriefs.firstOrNull()?.chapterIndex }?.id
            selectedSceneId.value = null
            appendEvent("Saved outline and ${chapters.size} chapter shells")
        }
    }

    fun generateSelectedSynopsis() {
        generateSelectedSynopsis(reviewFeedback = null, retryCount = null, busyMessage = "Generating chapter synopsis")
    }

    fun retrySelectedSynopsis() {
        val report = uiState.value.selectedChapter?.reviewReport ?: run {
            setError("No chapter review exists")
            return
        }
        if (report.retryCount >= MAX_REVIEW_RETRIES) {
            setError("Chapter review retry limit reached")
            return
        }
        val nextReport = report.copy(status = ReviewStatus.NEEDS_RETRY, retryCount = report.retryCount + 1)
        generateSelectedSynopsis(
            reviewFeedback = nextReport.toFeedback(),
            retryCount = nextReport.retryCount,
            busyMessage = "Retrying chapter synopsis",
        )
    }

    fun acceptSelectedSynopsisReview() {
        val chapter = uiState.value.selectedChapter ?: run {
            setError("Select a chapter first")
            return
        }
        viewModelScope.launch {
            novelRepository.upsertChapter(chapter.copy(reviewReport = chapter.reviewReport?.copy(status = ReviewStatus.ACCEPTED)))
            appendEvent("Chapter review accepted")
        }
    }

    fun markSelectedSynopsisForManualEdit() {
        val chapter = uiState.value.selectedChapter ?: run {
            setError("Select a chapter first")
            return
        }
        viewModelScope.launch {
            novelRepository.upsertChapter(chapter.copy(reviewReport = chapter.reviewReport?.copy(status = ReviewStatus.MANUAL_EDIT)))
            appendEvent("Chapter marked for manual edit")
        }
    }

    fun approveSelectedSynopsis() {
        val chapter = uiState.value.selectedChapter ?: run {
            setError("Select a chapter first")
            return
        }
        viewModelScope.launch {
            novelRepository.upsertChapter(
                chapter.copy(
                    reviewReport = chapter.reviewReport?.copy(status = ReviewStatus.APPROVED),
                    status = ChapterStatus.SYNOPSIS_APPROVED,
                ),
            )
            appendEvent("Chapter synopsis approved")
        }
    }

    private fun generateSelectedSynopsis(reviewFeedback: String?, retryCount: Int?, busyMessage: String) {
        runPipelineStep(busyMessage) { novel, settings ->
            require(settings.apiKey.isNotBlank()) { "API key is required before generation" }
            require(novel.outline != null) { "Generate an outline first" }
            val chapters = novelRepository.getChapters(novel.id)
            val currentChapterId = uiState.value.selectedChapter?.id
            val chapter = chapters.firstOrNull { it.id == currentChapterId } ?: chapters.firstOrNull() ?: error("No chapter shell exists")
            val pipeline = pipelineFactory(settings)
            val result = pipeline.generateChapterSynopsis(novel, chapter, chapters, reviewFeedback = reviewFeedback)
            result.events.forEach { recordPipelineEvent(novel.id, settings, it) }
            val generated = result.synopsis ?: error("Synopsis generation failed")
            val review = result.review
            val updatedChapter = chapter.copy(
                synopsis = generated,
                reviewNotes = review?.toReport(retryCount ?: 0)?.toNotes(),
                reviewReport = review?.toReport(retryCount ?: 0),
                status = ChapterStatus.SYNOPSIS_GENERATED,
            )
            chapter.synopsis?.let {
                saveSnapshot(novel.id, SNAPSHOT_CHAPTER_SYNOPSIS, chapter.id, "Synopsis before generation", it)
            }
            novelRepository.upsertChapter(updatedChapter)
            saveSceneShells(novel.id, chapter.id, generated.sceneBreakdowns)
            selectedChapterId.value = chapter.id
            selectedSceneId.value = null
            appendEvent("Saved chapter synopsis and ${generated.sceneBreakdowns.size} scene shells")
        }
    }

    fun generateSelectedScene() {
        generateSelectedScene(reviewFeedback = null, retryCount = null, busyMessage = "Generating scene")
    }

    fun retrySelectedScene() {
        val report = uiState.value.selectedScene?.reviewReport ?: run {
            setError("No scene review exists")
            return
        }
        if (report.retryCount >= MAX_REVIEW_RETRIES) {
            setError("Scene review retry limit reached")
            return
        }
        val nextReport = report.copy(status = ReviewStatus.NEEDS_RETRY, retryCount = report.retryCount + 1)
        generateSelectedScene(
            reviewFeedback = nextReport.toFeedback(),
            retryCount = nextReport.retryCount,
            busyMessage = "Retrying scene",
        )
    }

    fun acceptSelectedSceneReview() {
        val scene = uiState.value.selectedScene ?: run {
            setError("Select a scene first")
            return
        }
        viewModelScope.launch {
            novelRepository.upsertScene(scene.copy(reviewReport = scene.reviewReport?.copy(status = ReviewStatus.ACCEPTED)))
            appendEvent("Scene review accepted")
        }
    }

    fun markSelectedSceneForManualEdit() {
        val scene = uiState.value.selectedScene ?: run {
            setError("Select a scene first")
            return
        }
        viewModelScope.launch {
            novelRepository.upsertScene(scene.copy(reviewReport = scene.reviewReport?.copy(status = ReviewStatus.MANUAL_EDIT)))
            appendEvent("Scene marked for manual edit")
        }
    }

    fun approveSelectedScene() {
        val scene = uiState.value.selectedScene ?: run {
            setError("Select a scene first")
            return
        }
        viewModelScope.launch {
            novelRepository.upsertScene(
                scene.copy(
                    reviewReport = scene.reviewReport?.copy(status = ReviewStatus.APPROVED),
                    status = SceneStatus.APPROVED,
                ),
            )
            appendEvent("Scene approved")
        }
    }

    private fun generateSelectedScene(reviewFeedback: String?, retryCount: Int?, busyMessage: String) {
        runPipelineStep(busyMessage) { novel, settings ->
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
            var reviewReport: ReviewReport? = null

            if (scene.text.isNotBlank()) {
                saveSnapshot(novel.id, SNAPSHOT_SCENE, scene.id, "Scene before generation", scene)
            }
            novelRepository.upsertScene(scene.copy(status = SceneStatus.GENERATING))
            pipeline.generateScene(novel, chapter, scene, previousSceneEnding, reviewFeedback = reviewFeedback).collect { event ->
                recordPipelineEvent(novel.id, settings, event)
                when (event) {
                    is PipelineEvent.SceneTextComplete -> {
                        generatedText = event.text
                        generatedWordCount = event.wordCount
                    }
                    is PipelineEvent.BibleUpdated -> updatedBible = event.bible
                    is PipelineEvent.ReviewComplete -> reviewReport = event.result.toReport(retryCount ?: 0)
                    else -> Unit
                }
            }

            novelRepository.upsertScene(
                scene.copy(
                    text = generatedText,
                    wordCount = generatedWordCount,
                    reviewNotes = reviewReport?.toNotes(),
                    reviewReport = reviewReport,
                    status = if (reviewReport?.passed == true) SceneStatus.REVIEWED else SceneStatus.GENERATED,
                    textSource = SceneTextSource.GENERATED,
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

    private suspend fun recordPipelineEvent(novelId: String, settings: GenerationSettings, event: PipelineEvent) {
        appendPipelineEvent(event)
        if (event is PipelineEvent.UsageRecorded) {
            novelRepository.saveTokenUsage(
                TokenUsageRecord(
                    novelId = novelId,
                    agentName = event.usage.agentName,
                    provider = settings.provider,
                    model = event.usage.model,
                    inputTokens = event.usage.inputTokens,
                    outputTokens = event.usage.outputTokens,
                    estimatedCostUsd = estimateCostUsd(event.usage),
                ),
            )
        }
    }

    private suspend inline fun <reified T> saveSnapshot(
        novelId: String,
        targetType: String,
        targetId: String,
        label: String,
        content: T,
    ) {
        novelRepository.saveRevisionSnapshot(
            novelId = novelId,
            targetType = targetType,
            targetId = targetId,
            label = label,
            contentJson = json.encodeToString(content),
        )
    }

    private suspend fun restoreSnapshot(snapshot: RevisionSnapshot) {
        when (snapshot.targetType) {
            SNAPSHOT_OUTLINE -> restoreOutline(snapshot)
            SNAPSHOT_CHAPTER_SYNOPSIS -> restoreChapterSynopsis(snapshot)
            SNAPSHOT_SCENE -> restoreScene(snapshot)
            SNAPSHOT_BIBLE -> restoreBible(snapshot)
            else -> setError("Unknown snapshot type: ${snapshot.targetType}")
        }
    }

    private suspend fun restoreOutline(snapshot: RevisionSnapshot) {
        val novel = novelRepository.getNovel(snapshot.novelId) ?: return
        val outline = json.decodeFromString<NovelOutline>(snapshot.contentJson)
        novelRepository.upsertNovel(novel.copy(outline = outline, updatedAt = Clock.System.now()))
        saveChapterShells(novel.id, outline.chapterBriefs)
    }

    private suspend fun restoreChapterSynopsis(snapshot: RevisionSnapshot) {
        val chapter = novelRepository.getChapters(snapshot.novelId).firstOrNull { it.id == snapshot.targetId } ?: return
        val synopsis = json.decodeFromString<ChapterSynopsis>(snapshot.contentJson)
        novelRepository.upsertChapter(chapter.copy(synopsis = synopsis, status = ChapterStatus.SYNOPSIS_GENERATED))
        saveSceneShells(chapter.novelId, chapter.id, synopsis.sceneBreakdowns)
    }

    private suspend fun restoreScene(snapshot: RevisionSnapshot) {
        val scene = json.decodeFromString<Scene>(snapshot.contentJson)
        novelRepository.upsertScene(scene)
        selectedChapterId.value = scene.chapterId
        selectedSceneId.value = scene.id
    }

    private suspend fun restoreBible(snapshot: RevisionSnapshot) {
        val novel = novelRepository.getNovel(snapshot.novelId) ?: return
        novelRepository.upsertNovel(
            novel.copy(
                bible = json.decodeFromString<NovelBible>(snapshot.contentJson),
                updatedAt = Clock.System.now(),
            ),
        )
    }

    private fun appendPipelineEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.Step -> appendEvent(event.description)
            is PipelineEvent.OutlineGenerated -> appendEvent("Outline generated")
            is PipelineEvent.SynopsisGenerated -> appendEvent("Synopsis generated")
            is PipelineEvent.SceneTextComplete -> appendEvent("Scene text generated: ${event.wordCount} words")
            is PipelineEvent.ReviewComplete -> appendEvent("Review score: ${event.result.score}/10")
            is PipelineEvent.BibleUpdated -> appendEvent("Bible updated")
            is PipelineEvent.UsageRecorded -> appendEvent("Usage: ${event.usage.agentName} ${event.usage.inputTokens + event.usage.outputTokens} tokens")
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

    companion object {
        const val MAX_REVIEW_RETRIES = 2
        private const val SNAPSHOT_OUTLINE = "outline"
        private const val SNAPSHOT_CHAPTER_SYNOPSIS = "chapter_synopsis"
        private const val SNAPSHOT_SCENE = "scene"
        private const val SNAPSHOT_BIBLE = "bible"
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

private fun estimateCostUsd(usage: AgentUsage): Double {
    val model = usage.model.lowercase()
    val (inputPerMillion, outputPerMillion) = when {
        "haiku" in model -> 0.80 to 4.00
        "sonnet" in model -> 3.00 to 15.00
        "opus" in model -> 15.00 to 75.00
        "gpt-4o-mini" in model -> 0.15 to 0.60
        "gpt-4o" in model -> 2.50 to 10.00
        "gpt-4.1" in model -> 2.00 to 8.00
        else -> 3.00 to 15.00
    }
    return (usage.inputTokens * inputPerMillion + usage.outputTokens * outputPerMillion) / 1_000_000.0
}

private fun AgentResult.ReviewResult.toReport(retryCount: Int): ReviewReport {
    return ReviewReport(
        score = score,
        issues = issues,
        suggestedFixes = suggestedFixes,
        passed = passed,
        retryCount = retryCount,
        status = ReviewStatus.PENDING_DECISION,
    )
}

private fun ReviewReport.toNotes(): String {
    return buildString {
        appendLine("Score: $score/10")
        appendLine("Passed: $passed")
        if (issues.isNotEmpty()) {
            appendLine()
            appendLine("Issues:")
            issues.forEach { appendLine("- $it") }
        }
        if (suggestedFixes.isNotEmpty()) {
            appendLine()
            appendLine("Suggested fixes:")
            suggestedFixes.forEach { appendLine("- $it") }
        }
    }.trim()
}

private fun ReviewReport.toFeedback(): String {
    return buildString {
        appendLine("Previous review score: $score/10")
        appendLine("Previous review passed: $passed")
        appendLine("Retry attempt: $retryCount")
        if (issues.isNotEmpty()) {
            appendLine()
            appendLine("Issues to fix:")
            issues.forEach { appendLine("- $it") }
        }
        if (suggestedFixes.isNotEmpty()) {
            appendLine()
            appendLine("Suggested fixes:")
            suggestedFixes.forEach { appendLine("- $it") }
        }
    }.trim()
}

private fun NovelBible.upsert(section: BibleSection, draft: BibleEntryDraft): NovelBible {
    val entryId = draft.id.ifBlank { UUID.randomUUID().toString() }
    return when (section) {
        BibleSection.CHARACTERS -> copy(
            characters = characters.replaceOrAppend(
                item = CharacterEntry(
                    id = entryId,
                    name = draft.primary.trim(),
                    description = draft.secondary.trim(),
                    personality = draft.tertiary.trim(),
                    currentState = draft.quaternary.trim(),
                    source = BibleEntrySource.USER,
                ),
                matches = { it.id == entryId || it.name.equals(draft.primary.trim(), ignoreCase = true) },
            ),
        )
        BibleSection.LOCATIONS -> copy(
            locations = locations.replaceOrAppend(
                item = LocationEntry(
                    id = entryId,
                    name = draft.primary.trim(),
                    description = draft.secondary.trim(),
                    significance = draft.tertiary.trim(),
                    source = BibleEntrySource.USER,
                ),
                matches = { it.id == entryId || it.name.equals(draft.primary.trim(), ignoreCase = true) },
            ),
        )
        BibleSection.TIMELINE -> copy(
            timelineEvents = timelineEvents.replaceOrAppend(
                item = TimelineEvent(
                    id = entryId,
                    description = draft.primary.trim(),
                    chapterId = draft.secondary.trim().ifBlank { null },
                    sceneId = draft.tertiary.trim().ifBlank { null },
                    chronologicalOrder = draft.quaternary.trim().toIntOrNull() ?: timelineEvents.size,
                    source = BibleEntrySource.USER,
                ),
                matches = { it.id == entryId },
            ),
        )
        BibleSection.WORLD_RULES -> copy(
            worldRules = worldRules.replaceOrAppend(
                item = WorldRule(
                    id = entryId,
                    category = draft.primary.trim(),
                    rule = draft.secondary.trim(),
                    details = draft.tertiary.trim(),
                    source = BibleEntrySource.USER,
                ),
                matches = {
                    it.id == entryId ||
                        (it.category.equals(draft.primary.trim(), ignoreCase = true) && it.rule.equals(draft.secondary.trim(), ignoreCase = true))
                },
            ),
        )
        BibleSection.THEMES -> copy(
            themes = themes.replaceOrAppend(
                item = ThemeEntry(
                    id = entryId,
                    name = draft.primary.trim(),
                    description = draft.secondary.trim(),
                    motifSymbols = draft.tertiary.split(',').map { it.trim() }.filter { it.isNotBlank() },
                    source = BibleEntrySource.USER,
                ),
                matches = { it.id == entryId || it.name.equals(draft.primary.trim(), ignoreCase = true) },
            ),
        )
    }
}

private fun NovelBible.delete(section: BibleSection, id: String): NovelBible {
    return when (section) {
        BibleSection.CHARACTERS -> copy(characters = characters.filterNot { it.id == id })
        BibleSection.LOCATIONS -> copy(locations = locations.filterNot { it.id == id })
        BibleSection.TIMELINE -> copy(timelineEvents = timelineEvents.filterNot { it.id == id })
        BibleSection.WORLD_RULES -> copy(worldRules = worldRules.filterNot { it.id == id })
        BibleSection.THEMES -> copy(themes = themes.filterNot { it.id == id })
    }
}

private fun NovelBible.applyConflict(conflict: BibleConflict): NovelBible {
    return when (conflict.section) {
        BibleConflictSection.CHARACTERS -> copy(characters = characters.map { character ->
            if (character.id == conflict.entryId) character.applyConflictField(conflict) else character
        })
        BibleConflictSection.LOCATIONS -> copy(locations = locations.map { location ->
            if (location.id == conflict.entryId) location.applyConflictField(conflict) else location
        })
        BibleConflictSection.TIMELINE -> copy(timelineEvents = timelineEvents.map { event ->
            if (event.id == conflict.entryId) event.applyConflictField(conflict) else event
        })
        BibleConflictSection.WORLD_RULES -> copy(worldRules = worldRules.map { rule ->
            if (rule.id == conflict.entryId) rule.applyConflictField(conflict) else rule
        })
        BibleConflictSection.THEMES -> copy(themes = themes.map { theme ->
            if (theme.id == conflict.entryId) theme.applyConflictField(conflict) else theme
        })
    }
}

private fun CharacterEntry.applyConflictField(conflict: BibleConflict): CharacterEntry {
    return when (conflict.field) {
        "description" -> copy(description = conflict.incomingValue)
        "personality" -> copy(personality = conflict.incomingValue)
        "currentState" -> copy(currentState = conflict.incomingValue)
        else -> this
    }
}

private fun LocationEntry.applyConflictField(conflict: BibleConflict): LocationEntry {
    return when (conflict.field) {
        "description" -> copy(description = conflict.incomingValue)
        "significance" -> copy(significance = conflict.incomingValue)
        else -> this
    }
}

private fun TimelineEvent.applyConflictField(conflict: BibleConflict): TimelineEvent {
    return when (conflict.field) {
        "description" -> copy(description = conflict.incomingValue)
        "chapterId" -> copy(chapterId = conflict.incomingValue.ifBlank { null })
        "sceneId" -> copy(sceneId = conflict.incomingValue.ifBlank { null })
        "chronologicalOrder" -> copy(chronologicalOrder = conflict.incomingValue.toIntOrNull() ?: chronologicalOrder)
        else -> this
    }
}

private fun WorldRule.applyConflictField(conflict: BibleConflict): WorldRule {
    return when (conflict.field) {
        "category" -> copy(category = conflict.incomingValue)
        "rule" -> copy(rule = conflict.incomingValue)
        "details" -> copy(details = conflict.incomingValue)
        else -> this
    }
}

private fun ThemeEntry.applyConflictField(conflict: BibleConflict): ThemeEntry {
    return when (conflict.field) {
        "name" -> copy(name = conflict.incomingValue)
        "description" -> copy(description = conflict.incomingValue)
        "motifSymbols" -> copy(motifSymbols = conflict.incomingValue.split(',').map { it.trim() }.filter { it.isNotBlank() })
        else -> this
    }
}

private fun NovelBible.warnings(): List<BibleConflictWarning> {
    return conflicts.map { conflict ->
        BibleConflictWarning(
            section = conflict.section.toUiSection(),
            title = conflict.title,
            detail = "${conflict.field}: kept canon, incoming fact is waiting for resolution.",
            conflictId = conflict.id,
        )
    } + duplicateWarnings(BibleSection.CHARACTERS, characters, { it.name }, { it.description }) +
        duplicateWarnings(BibleSection.LOCATIONS, locations, { it.name }, { it.description }) +
        duplicateWarnings(BibleSection.TIMELINE, timelineEvents, { it.description }, { it.sceneId.orEmpty() }) +
        duplicateWarnings(BibleSection.WORLD_RULES, worldRules, { "${it.category}|${it.rule}" }, { it.details }) +
        duplicateWarnings(BibleSection.THEMES, themes, { it.name }, { it.description })
}

private fun BibleConflictSection.toUiSection(): BibleSection {
    return when (this) {
        BibleConflictSection.CHARACTERS -> BibleSection.CHARACTERS
        BibleConflictSection.LOCATIONS -> BibleSection.LOCATIONS
        BibleConflictSection.TIMELINE -> BibleSection.TIMELINE
        BibleConflictSection.WORLD_RULES -> BibleSection.WORLD_RULES
        BibleConflictSection.THEMES -> BibleSection.THEMES
    }
}

private fun <T> List<T>.replaceOrAppend(item: T, matches: (T) -> Boolean): List<T> {
    var replaced = false
    val updated = map { existing ->
        if (matches(existing)) {
            replaced = true
            item
        } else {
            existing
        }
    }
    return if (replaced) updated else updated + item
}

private fun <T> duplicateWarnings(
    section: BibleSection,
    items: List<T>,
    key: (T) -> String,
    detail: (T) -> String,
): List<BibleConflictWarning> {
    return items
        .groupBy { key(it).trim().lowercase() }
        .filterKeys { it.isNotBlank() }
        .filterValues { group -> group.size > 1 && group.map { detail(it).trim() }.distinct().size > 1 }
        .map { (_, group) ->
            BibleConflictWarning(
                section = section,
                title = key(group.first()),
                detail = "Multiple ${section.label.lowercase()} entries share this key with different details.",
            )
        }
}

private data class WorkspaceData(
    val novels: List<Novel> = emptyList(),
    val selectedNovel: Novel? = null,
    val chapters: List<Chapter> = emptyList(),
    val selectedChapter: Chapter? = null,
    val scenes: List<Scene> = emptyList(),
    val allScenes: List<Scene> = emptyList(),
    val revisionSnapshots: List<RevisionSnapshot> = emptyList(),
    val tokenUsage: List<TokenUsageRecord> = emptyList(),
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
    val allScenes: List<Scene> = emptyList(),
    val revisionSnapshots: List<RevisionSnapshot> = emptyList(),
    val tokenUsage: List<TokenUsageRecord> = emptyList(),
    val selectedScene: Scene? = null,
    val settings: GenerationSettings,
    val workflow: WorkflowState = WorkflowState(),
    val bibleWarnings: List<BibleConflictWarning> = emptyList(),
)

enum class BibleSection(val label: String) {
    CHARACTERS("Characters"),
    LOCATIONS("Locations"),
    TIMELINE("Timeline"),
    WORLD_RULES("World Rules"),
    THEMES("Themes"),
}

data class BibleEntryDraft(
    val id: String = "",
    val primary: String = "",
    val secondary: String = "",
    val tertiary: String = "",
    val quaternary: String = "",
)

data class BibleConflictWarning(
    val section: BibleSection,
    val title: String,
    val detail: String,
    val conflictId: String? = null,
)

data class WorkflowState(
    val isBusy: Boolean = false,
    val error: String? = null,
    val events: List<String> = emptyList(),
)
