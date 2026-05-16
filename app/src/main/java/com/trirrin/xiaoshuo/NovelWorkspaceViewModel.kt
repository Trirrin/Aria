package com.trirrin.xiaoshuo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import com.trirrin.xiaoshuo.agent.AgentPipeline
import com.trirrin.xiaoshuo.agent.AgentUsage
import com.trirrin.xiaoshuo.agent.ConversationPlanInput
import com.trirrin.xiaoshuo.agent.WorkflowToolCall
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
import com.trirrin.xiaoshuo.model.NovelBackgroundProposal
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.model.NovelStatus
import com.trirrin.xiaoshuo.model.NarrativeTense
import com.trirrin.xiaoshuo.model.NarrativeVoice
import com.trirrin.xiaoshuo.model.PlotPoint
import com.trirrin.xiaoshuo.model.ProseStyle
import com.trirrin.xiaoshuo.model.Relationship
import com.trirrin.xiaoshuo.model.ReviewReport
import com.trirrin.xiaoshuo.model.ReviewStatus
import com.trirrin.xiaoshuo.model.Scene
import com.trirrin.xiaoshuo.model.SceneTextSource
import com.trirrin.xiaoshuo.model.ThemeEntry
import com.trirrin.xiaoshuo.model.TimelineEvent
import com.trirrin.xiaoshuo.model.WorldRule
import com.trirrin.xiaoshuo.prompt.ChatImportPayload
import com.trirrin.xiaoshuo.prompt.ChatImportPrompt
import com.trirrin.xiaoshuo.model.SceneBreakdown
import com.trirrin.xiaoshuo.model.SceneStatus
import com.trirrin.xiaoshuo.model.StyleGuide
import kotlinx.coroutines.CancellationException
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NovelWorkspaceViewModel(
    private val novelRepository: NovelRepository,
    private val settingsRepository: GenerationSettingsRepository,
    private val pipelineFactory: (GenerationSettings) -> AgentPipeline,
    private val generationCoordinator: GenerationCoordinator,
) : ViewModel() {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val workflow = generationCoordinator.workflow
    private val conversation = MutableStateFlow(ConversationState())
    private val selectedNovelId = MutableStateFlow<String?>(null)
    private val selectedChapterId = MutableStateFlow<String?>(null)
    private val selectedSceneId = MutableStateFlow<String?>(null)
    private var pendingOverwriteAction: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            val recovered = novelRepository.resetInterruptedScenes()
            if (recovered > 0) {
                appendEvent("Recovered $recovered interrupted scene drafts")
            }
        }
    }

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
    val uiState: StateFlow<NovelWorkspaceUiState> = combine(workspaceData, settings, workflow, conversation) { data, settings, workflow, conversation ->
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
            conversation = conversation,
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
            workflow.value = WorkflowState(events = listOf(timestamped("Created ${novel.title}")))
        }
    }

    fun importFromChat(rawOutput: String) {
        val cleanOutput = rawOutput.trim()
        if (cleanOutput.isBlank()) {
            setError("Paste the chat import JSON first")
            return
        }
        val payload = ChatImportPrompt.parseOutput(cleanOutput).getOrElse { error ->
            setError("Import JSON could not be parsed: ${error.message ?: "invalid structure"}")
            return
        }
        if (payload.title.trim().isBlank() || payload.concept.trim().isBlank()) {
            setError("Imported title and concept are required")
            return
        }

        viewModelScope.launch {
            importPayload(payload)
        }
    }

    fun submitConversationMessage(message: String) {
        val cleanMessage = message.trim()
        if (cleanMessage.isBlank()) return

        appendConversation(ConversationRole.USER, cleanMessage)
        planConversationTool(cleanMessage)
    }

    fun acceptPendingApproval() {
        when (val approval = conversation.value.pendingApproval) {
            null -> appendConversation(ConversationRole.ASSISTANT, "No proposal is waiting for approval.")
            else -> acceptApproval(approval)
        }
    }

    fun rejectPendingApproval() {
        val approval = conversation.value.pendingApproval ?: run {
            appendConversation(ConversationRole.ASSISTANT, "No proposal is waiting for rejection.")
            return
        }
        conversation.update { it.copy(pendingApproval = null) }
        appendConversation(ConversationRole.ASSISTANT, "Rejected ${approval.previewTitle}. Nothing was saved.")
        appendEvent("Proposal rejected")
    }

    fun revisePendingApproval(feedback: String) {
        val cleanFeedback = feedback.trim()
        if (cleanFeedback.isBlank()) {
            setError("Revision feedback is required")
            return
        }
        when (val approval = conversation.value.pendingApproval) {
            null -> appendConversation(ConversationRole.ASSISTANT, "No proposal is waiting for revision.")
            else -> reviseApproval(approval, cleanFeedback)
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
        val novel = uiState.value.selectedNovel ?: run {
            setError("Create or select a novel first")
            return
        }
        generateOutlineProposal(novel.id)
    }

    fun generateSelectedSynopsis() {
        val chapter = uiState.value.selectedChapter
        if (chapter?.synopsis != null) {
            requestOverwriteConfirmation(OverwriteTarget.SYNOPSIS) {
                generateSelectedSynopsisConfirmed(reviewFeedback = null, retryCount = null, busyMessage = "Generating chapter synopsis")
            }
            return
        }
        generateSelectedSynopsisConfirmed(reviewFeedback = null, retryCount = null, busyMessage = "Generating chapter synopsis")
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
        requestOverwriteConfirmation(OverwriteTarget.SYNOPSIS) {
            generateSelectedSynopsisConfirmed(
                reviewFeedback = nextReport.toFeedback(),
                retryCount = nextReport.retryCount,
                busyMessage = "Retrying chapter synopsis",
            )
        }
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

    private fun generateSelectedSynopsisConfirmed(reviewFeedback: String?, retryCount: Int?, busyMessage: String) {
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
        val scene = uiState.value.selectedScene
        if (scene?.text?.isNotBlank() == true) {
            requestOverwriteConfirmation(OverwriteTarget.SCENE) {
                streamSelectedScene(busyMessage = "Streaming scene")
            }
            return
        }
        streamSelectedScene(busyMessage = "Streaming scene")
    }

    fun queueSelectedChapterScenes() {
        queueSelectedScenes(fromSelectedScene = false)
    }

    fun queueScenesFromSelectedScene() {
        queueSelectedScenes(fromSelectedScene = true)
    }

    fun cancelGeneration() {
        if (!generationCoordinator.cancel()) {
            appendEvent("No active generation to cancel")
            return
        }
        appendEvent("Cancellation requested")
    }

    fun confirmOverwrite() {
        val action = pendingOverwriteAction ?: return
        pendingOverwriteAction = null
        workflow.update { it.copy(overwriteConfirmation = null) }
        action()
    }

    fun cancelOverwrite() {
        pendingOverwriteAction = null
        workflow.update { it.copy(overwriteConfirmation = null) }
        appendEvent("Overwrite cancelled")
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
        requestOverwriteConfirmation(OverwriteTarget.SCENE) {
            generateSelectedSceneConfirmed(
                reviewFeedback = nextReport.toFeedback(),
                retryCount = nextReport.retryCount,
                busyMessage = "Retrying scene",
            )
        }
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

    private fun streamSelectedScene(busyMessage: String) {
        runPipelineStep(busyMessage) { novel, settings ->
            require(settings.apiKey.isNotBlank()) { "API key is required before generation" }
            val selectedChapter = uiState.value.selectedChapter ?: error("Select a chapter first")
            val selectedScene = uiState.value.selectedScene ?: error("Select a scene first")
            val chapter = novelRepository.getChapters(novel.id).firstOrNull { it.id == selectedChapter.id }
                ?: error("Selected chapter no longer exists")
            val scene = novelRepository.getScenes(chapter.id).firstOrNull { it.id == selectedScene.id }
                ?: error("Selected scene no longer exists")
            streamSceneAndFinalize(novel, chapter, scene, settings, snapshotExistingText = true)
        }
    }

    private fun queueSelectedScenes(fromSelectedScene: Boolean) {
        val label = if (fromSelectedScene) "Queueing scenes from selection" else "Queueing selected chapter"
        runPipelineStep(label) { novel, settings ->
            require(settings.apiKey.isNotBlank()) { "API key is required before generation" }
            val selectedChapter = uiState.value.selectedChapter ?: error("Select a chapter first")
            val selectedScene = uiState.value.selectedScene
            val chapter = novelRepository.getChapters(novel.id).firstOrNull { it.id == selectedChapter.id }
                ?: error("Selected chapter no longer exists")
            val scenes = novelRepository.getScenes(chapter.id)
            val startOrder = if (fromSelectedScene) {
                selectedScene?.order ?: error("Select a starting scene first")
            } else {
                Int.MIN_VALUE
            }
            val queue = scenes
                .filter { it.order >= startOrder }
                .filter { it.text.isBlank() }
                .sortedBy { it.order }
            val skipped = scenes.count { it.order >= startOrder && it.text.isNotBlank() }
            if (skipped > 0) {
                appendEvent("Skipped $skipped scene(s) with existing prose")
            }
            if (queue.isEmpty()) {
                appendEvent("No blank scenes to queue")
                return@runPipelineStep
            }

            queue.forEachIndexed { index, queuedScene ->
                workflow.update { it.copy(queuePosition = index + 1, queueTotal = queue.size) }
                val currentNovel = novelRepository.getNovel(novel.id) ?: novel
                val currentChapter = novelRepository.getChapters(novel.id).firstOrNull { it.id == chapter.id }
                    ?: error("Queued chapter no longer exists")
                val currentScene = novelRepository.getScenes(chapter.id).firstOrNull { it.id == queuedScene.id }
                    ?: error("Queued scene no longer exists")
                appendEvent("Queue ${index + 1}/${queue.size}: scene ${currentScene.order}")
                streamSceneAndFinalize(currentNovel, currentChapter, currentScene, settings, snapshotExistingText = false)
            }
            workflow.update { it.copy(queuePosition = 0, queueTotal = 0) }
            appendEvent("Scene queue complete")
        }
    }

    private suspend fun streamSceneAndFinalize(
        novel: Novel,
        chapter: Chapter,
        scene: Scene,
        settings: GenerationSettings,
        snapshotExistingText: Boolean,
    ) {
        require(chapter.synopsis != null) { "Generate a chapter synopsis first" }
        val scenes = novelRepository.getScenes(chapter.id)
        val previousSceneEnding = previousSceneEnding(scenes, scene)
        val pipeline = pipelineFactory(settings)
        val buffer = StringBuilder()
        var persistedLength = 0
        var updatedBible = novel.bible
        var reviewReport: ReviewReport? = null
        var rollingSummary: String? = null
        val referenceProseStyle = latestApprovedStyleSample(scenes, scene)

        selectedChapterId.value = chapter.id
        selectedSceneId.value = scene.id
        if (snapshotExistingText && scene.text.isNotBlank()) {
            saveSnapshot(novel.id, SNAPSHOT_SCENE, scene.id, "Scene before generation", scene)
        }
        novelRepository.upsertScene(scene.copy(status = SceneStatus.GENERATING))
        workflow.update { it.copy(streamingSceneId = scene.id, streamingSceneText = "") }

        try {
            pipeline.streamSceneEvents(
                novel = novel,
                chapter = chapter,
                scene = scene,
                previousSceneEnding = previousSceneEnding,
                rollingChapterSummary = rollingSummary,
                referenceProseStyle = referenceProseStyle,
            ).collect { event ->
                recordPipelineEvent(novel.id, settings, event)
                if (event !is PipelineEvent.SceneTextDelta) return@collect
                val delta = event.delta
                if (delta.isEmpty()) return@collect
                buffer.append(delta)
                val streamedText = buffer.toString()
                workflow.update { it.copy(streamingSceneId = scene.id, streamingSceneText = streamedText) }
                if (streamedText.length - persistedLength >= STREAM_SAVE_MIN_CHARS) {
                    persistStreamedScene(scene, streamedText, SceneStatus.GENERATING)
                    persistedLength = streamedText.length
                }
            }

            val generatedText = buffer.toString()
            require(generatedText.isNotBlank()) { "Scene stream returned no text" }
            val generatedWordCount = countWords(generatedText)
            persistStreamedScene(scene, generatedText, SceneStatus.GENERATING)
            recordPipelineEvent(novel.id, settings, PipelineEvent.SceneTextComplete(generatedText, generatedWordCount))

            pipeline.finalizeSceneText(novel, chapter, scene, generatedText, rollingSummary).collect { event ->
                recordPipelineEvent(novel.id, settings, event)
                when (event) {
                    is PipelineEvent.BibleUpdated -> updatedBible = event.bible
                    is PipelineEvent.ReviewComplete -> reviewReport = event.result.toReport(0)
                    is PipelineEvent.RollingSummaryUpdated -> rollingSummary = event.summary
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
            appendEvent("Saved streamed scene text and Bible updates")
        } catch (e: CancellationException) {
            recoverStreamedScene(scene, buffer.toString())
            appendEvent("Scene generation cancelled")
            throw e
        } catch (e: Exception) {
            recoverStreamedScene(scene, buffer.toString())
            throw e
        } finally {
            workflow.update { it.copy(streamingSceneId = null, streamingSceneText = null) }
        }
    }

    private suspend fun persistStreamedScene(scene: Scene, text: String, status: SceneStatus) {
        novelRepository.upsertScene(
            scene.copy(
                text = text,
                wordCount = countWords(text),
                reviewNotes = null,
                reviewReport = null,
                status = status,
                textSource = SceneTextSource.GENERATED,
            ),
        )
    }

    private suspend fun recoverStreamedScene(scene: Scene, text: String) {
        if (text.isBlank()) {
            novelRepository.upsertScene(scene)
            return
        }
        persistStreamedScene(scene, text, SceneStatus.GENERATED)
        appendEvent("Partial scene draft saved")
    }

    private fun previousSceneEnding(scenes: List<Scene>, scene: Scene): String? {
        return scenes
            .filter { it.order < scene.order && it.text.isNotBlank() }
            .maxByOrNull { it.order }
            ?.text
            ?.split(Regex("\\s+"))
            ?.takeLast(200)
            ?.joinToString(" ")
    }

    private fun latestApprovedStyleSample(scenes: List<Scene>, scene: Scene): String? {
        return scenes
            .filter { it.order < scene.order && it.status == SceneStatus.APPROVED && it.text.isNotBlank() }
            .maxByOrNull { it.order }
            ?.text
            ?.split(Regex("\\s+"))
            ?.take(100)
            ?.joinToString(" ")
    }

    private fun generateSelectedSceneConfirmed(reviewFeedback: String?, retryCount: Int?, busyMessage: String) {
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
            val previousSceneEnding = previousSceneEnding(scenes, scene)
            val pipeline = pipelineFactory(settings)
            var generatedText = scene.text
            var generatedWordCount = scene.wordCount
            var updatedBible = novel.bible
            var reviewReport: ReviewReport? = null
            var rollingSummary: String? = null
            val referenceProseStyle = latestApprovedStyleSample(scenes, scene)

            if (scene.text.isNotBlank()) {
                saveSnapshot(novel.id, SNAPSHOT_SCENE, scene.id, "Scene before generation", scene)
            }
            novelRepository.upsertScene(scene.copy(status = SceneStatus.GENERATING))
            try {
                pipeline.generateScene(
                    novel = novel,
                    chapter = chapter,
                    scene = scene,
                    previousSceneEnding = previousSceneEnding,
                    reviewFeedback = reviewFeedback,
                    rollingChapterSummary = rollingSummary,
                    referenceProseStyle = referenceProseStyle,
                ).collect { event ->
                    recordPipelineEvent(novel.id, settings, event)
                    when (event) {
                        is PipelineEvent.SceneTextComplete -> {
                            generatedText = event.text
                            generatedWordCount = event.wordCount
                        }
                        is PipelineEvent.BibleUpdated -> updatedBible = event.bible
                        is PipelineEvent.ReviewComplete -> reviewReport = event.result.toReport(retryCount ?: 0)
                        is PipelineEvent.RollingSummaryUpdated -> rollingSummary = event.summary
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
            } catch (e: CancellationException) {
                novelRepository.upsertScene(scene)
                throw e
            } catch (e: Exception) {
                novelRepository.upsertScene(scene)
                throw e
            }
        }
    }

    private fun planConversationTool(userMessage: String) {
        val settings = uiState.value.settings
        if (settings.apiKey.isBlank()) {
            setError("API key is required before conversation planning")
            appendConversation(ConversationRole.ASSISTANT, "Add an API key in Settings before I can route requests through the writing agent.")
            return
        }
        if (generationCoordinator.isRunning || workflow.value.isBusy) {
            setError("Generation already running")
            return
        }

        viewModelScope.launch {
            var plannedTool: WorkflowToolCall? = null
            workflow.update {
                it.copy(
                    isBusy = true,
                    error = null,
                    events = (it.events + timestamped("Planning workflow tool call")).takeLast(12),
                )
            }
            try {
                val novel = uiState.value.selectedNovel
                val pipeline = pipelineFactory(settings)
                val (toolCall, events) = pipeline.planConversationTool(
                    ConversationPlanInput(
                        userMessage = userMessage,
                        selectedNovelTitle = novel?.title,
                        hasOutline = novel?.outline != null,
                        pendingApprovalType = conversation.value.pendingApproval?.targetType?.name,
                    ),
                )
                events.forEach { recordPipelineEvent(novel?.id, settings, it) }
                plannedTool = toolCall ?: error("Conversation agent did not choose a tool")
            } catch (error: CancellationException) {
                appendEvent("Conversation planning cancelled")
            } catch (error: Exception) {
                setError(error.message ?: "Conversation planning failed")
                appendConversation(ConversationRole.ASSISTANT, "I could not route that request. ${error.message ?: "Try rephrasing it."}")
            } finally {
                workflow.update { it.copy(isBusy = false) }
            }
            plannedTool?.let { executeConversationToolCall(it, userMessage) }
        }
    }

    private fun executeConversationToolCall(toolCall: WorkflowToolCall, originalMessage: String) {
        val args = toolCall.argumentsJson.toToolArguments(json)
        when (toolCall.name) {
            "createNovelBackgroundProposal" -> generateBackgroundProposal(
                userRequest = args.string("userRequest") ?: originalMessage,
            )
            "generateOutlineProposal" -> generateOutline()
            "acceptPendingApproval" -> acceptPendingApproval()
            "rejectPendingApproval" -> rejectPendingApproval()
            "revisePendingApproval" -> revisePendingApproval(
                feedback = args.string("revisionFeedback") ?: originalMessage,
            )
            "askClarification" -> appendConversation(
                ConversationRole.ASSISTANT,
                args.string("message") ?: "Please clarify what you want me to do next.",
            )
            else -> {
                setError("Unknown tool call: ${toolCall.name}")
                appendConversation(ConversationRole.ASSISTANT, "I tried to call an unknown internal tool. That is a bug, not your fault.")
            }
        }
    }

    private fun generateBackgroundProposal(
        userRequest: String,
        revisionFeedback: String? = null,
        previousProposal: NovelBackgroundProposal? = null,
    ) {
        val settings = uiState.value.settings
        if (settings.apiKey.isBlank()) {
            setError("API key is required before generation")
            appendConversation(ConversationRole.ASSISTANT, "Add an API key in Settings before I generate a background proposal.")
            return
        }
        if (generationCoordinator.isRunning) {
            setError("Generation already running")
            return
        }

        generationCoordinator.launch {
            workflow.update { it.copy(isBusy = true, error = null, events = (it.events + timestamped("Generating background proposal")).takeLast(12)) }
            try {
                val pipeline = pipelineFactory(settings)
                val (proposal, events) = pipeline.generateBackground(
                    userRequest = userRequest,
                    revisionFeedback = revisionFeedback,
                    previousProposal = previousProposal,
                )
                events.forEach { recordPipelineEvent(null, settings, it) }
                val generated = proposal ?: error("Background proposal generation failed")
                val approval = PendingApproval(
                    targetType = ApprovalTargetType.NOVEL_BACKGROUND,
                    actionName = "acceptNovelBackground",
                    previewTitle = generated.titleOptions.firstOrNull { it.isNotBlank() } ?: "Novel Background",
                    previewText = generated.toPreviewText(),
                    riskLevel = ApprovalRiskLevel.MEDIUM,
                    requiredBeforeCommit = true,
                    payload = ApprovalPayload.Background(userRequest, generated),
                )
                conversation.update { it.copy(pendingApproval = approval) }
                appendConversation(ConversationRole.ASSISTANT, "I drafted a background proposal. Review it, then accept, reject, or request a revision.")
                appendEvent("Background proposal ready")
            } catch (error: CancellationException) {
                appendEvent("Generation cancelled")
            } catch (error: Exception) {
                setError(error.message ?: "Background generation failed")
            } finally {
                workflow.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun generateOutlineProposal(
        novelId: String,
        revisionFeedback: String? = null,
        previousProposal: NovelOutline? = null,
    ) {
        val settings = uiState.value.settings
        if (settings.apiKey.isBlank()) {
            setError("API key is required before generation")
            appendConversation(ConversationRole.ASSISTANT, "Add an API key in Settings before I generate an outline proposal.")
            return
        }
        if (generationCoordinator.isRunning) {
            setError("Generation already running")
            return
        }

        generationCoordinator.launch {
            workflow.update { it.copy(isBusy = true, error = null, events = (it.events + timestamped("Generating outline proposal")).takeLast(12)) }
            try {
                val novel = novelRepository.getNovel(novelId) ?: error("Novel not found")
                val promptNovel = previousProposal?.let { novel.copy(outline = it) } ?: novel
                val pipeline = pipelineFactory(settings)
                val (outline, events) = pipeline.generateOutline(promptNovel, revisionFeedback = revisionFeedback)
                events.forEach { recordPipelineEvent(novel.id, settings, it) }
                val generated = outline ?: error("Outline proposal generation failed")
                val risk = if (novel.outline == null) ApprovalRiskLevel.MEDIUM else ApprovalRiskLevel.HIGH
                val approval = PendingApproval(
                    novelId = novel.id,
                    targetType = if (novel.outline == null) ApprovalTargetType.OUTLINE else ApprovalTargetType.OUTLINE_STRUCTURE_CHANGE,
                    actionName = "acceptOutlineProposal",
                    previewTitle = "Outline for ${novel.title}",
                    previewText = generated.toPreviewText(),
                    riskLevel = risk,
                    requiredBeforeCommit = true,
                    payload = ApprovalPayload.Outline(novel.id, generated),
                )
                conversation.update { it.copy(pendingApproval = approval) }
                appendConversation(ConversationRole.ASSISTANT, "I drafted an outline proposal. It is only a proposal until you accept it.")
                appendEvent("Outline proposal ready")
            } catch (error: CancellationException) {
                appendEvent("Generation cancelled")
            } catch (error: Exception) {
                setError(error.message ?: "Outline generation failed")
            } finally {
                workflow.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun acceptApproval(approval: PendingApproval) {
        when (val payload = approval.payload) {
            is ApprovalPayload.Background -> acceptBackgroundProposal(payload.proposal)
            is ApprovalPayload.Outline -> acceptOutlineProposal(payload.novelId, payload.outline)
        }
    }

    private fun reviseApproval(approval: PendingApproval, feedback: String) {
        appendConversation(ConversationRole.USER, "Revision request: $feedback")
        conversation.update { it.copy(pendingApproval = null) }
        when (val payload = approval.payload) {
            is ApprovalPayload.Background -> generateBackgroundProposal(
                userRequest = payload.userRequest,
                revisionFeedback = feedback,
                previousProposal = payload.proposal,
            )
            is ApprovalPayload.Outline -> generateOutlineProposal(
                novelId = payload.novelId,
                revisionFeedback = feedback,
                previousProposal = payload.outline,
            )
        }
    }

    private fun acceptBackgroundProposal(proposal: NovelBackgroundProposal) {
        viewModelScope.launch {
            val now = Clock.System.now()
            val novel = proposal.toNovel(now)
            novelRepository.upsertNovel(novel)
            selectedNovelId.value = novel.id
            selectedChapterId.value = null
            selectedSceneId.value = null
            conversation.update { it.copy(pendingApproval = null) }
            appendConversation(ConversationRole.ASSISTANT, "Accepted background and saved ${novel.title}. I will draft an outline proposal next.")
            appendEvent("Accepted background proposal")
            generateOutlineProposal(novel.id)
        }
    }

    private fun acceptOutlineProposal(novelId: String, outline: NovelOutline) {
        viewModelScope.launch {
            val novel = novelRepository.getNovel(novelId) ?: run {
                setError("Novel not found")
                return@launch
            }
            val now = Clock.System.now()
            novel.outline?.let {
                saveSnapshot(novel.id, SNAPSHOT_OUTLINE, novel.id, "Outline before proposal acceptance", it)
            }
            novelRepository.upsertNovel(
                novel.copy(
                    outline = outline,
                    status = NovelStatus.OUTLINE_COMPLETE,
                    updatedAt = now,
                ),
            )
            saveChapterShells(novel.id, outline.chapterBriefs)
            val chapters = novelRepository.getChapters(novel.id)
            selectedNovelId.value = novel.id
            selectedChapterId.value = chapters.firstOrNull { it.order == outline.chapterBriefs.firstOrNull()?.chapterIndex }?.id
            selectedSceneId.value = null
            conversation.update { it.copy(pendingApproval = null) }
            appendConversation(ConversationRole.ASSISTANT, "Accepted outline and created ${chapters.size} chapter shell(s).")
            appendEvent("Accepted outline proposal")
        }
    }

    private suspend fun importPayload(payload: ChatImportPayload) {
        val now = Clock.System.now()
        val outline = payload.toImportedOutline()
        val novel = Novel(
            title = payload.title.trim(),
            genre = payload.genre.toImportedGenre(),
            concept = payload.concept.trim(),
            themes = payload.themes.map { it.trim() }.filter { it.isNotBlank() },
            styleGuide = payload.toImportedStyleGuide(),
            outline = outline,
            bible = payload.toImportedBible(),
            status = if (outline == null) NovelStatus.DRAFTING_OUTLINE else NovelStatus.OUTLINE_COMPLETE,
            createdAt = now,
            updatedAt = now,
        )
        novelRepository.upsertNovel(novel)

        val firstChapterId = payload.outline?.chapters
            ?.sortedBy { it.index.coerceAtLeast(0) }
            ?.mapNotNull { chapter -> importChapter(novel.id, chapter) }
            ?.firstOrNull()
        selectedNovelId.value = novel.id
        selectedChapterId.value = firstChapterId
        selectedSceneId.value = firstChapterId?.let { chapterId -> novelRepository.getScenes(chapterId).firstOrNull()?.id }
        workflow.value = WorkflowState(events = listOf(timestamped("Imported ${novel.title} from chat")))
    }

    private suspend fun importChapter(novelId: String, chapter: com.trirrin.xiaoshuo.prompt.ImportedChapter): String? {
        val chapterIndex = chapter.index.takeIf { it > 0 } ?: return null
        val synopsis = chapter.synopsis?.toImportedSynopsis()
        val savedChapter = Chapter(
            novelId = novelId,
            order = chapterIndex,
            title = chapter.title.trim().ifBlank { "Chapter $chapterIndex" },
            synopsis = synopsis,
            status = if (synopsis == null) ChapterStatus.PENDING else ChapterStatus.SYNOPSIS_GENERATED,
        )
        novelRepository.upsertChapter(savedChapter)
        chapter.synopsis?.scenes
            ?.sortedBy { it.index.coerceAtLeast(0) }
            ?.forEach { scene -> importScene(novelId, savedChapter.id, scene) }
        return savedChapter.id
    }

    private suspend fun importScene(novelId: String, chapterId: String, scene: com.trirrin.xiaoshuo.prompt.ImportedScene) {
        val sceneIndex = scene.index.takeIf { it > 0 } ?: return
        val prose = scene.prose.trim()
        novelRepository.upsertScene(
            Scene(
                novelId = novelId,
                chapterId = chapterId,
                order = sceneIndex,
                synopsis = scene.synopsis.trim(),
                text = prose,
                status = if (prose.isBlank()) SceneStatus.PENDING else SceneStatus.GENERATED,
                wordCount = countWords(prose),
                textSource = if (prose.isBlank()) SceneTextSource.EMPTY else SceneTextSource.EDITED,
            ),
        )
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
        if (generationCoordinator.isRunning) {
            setError("Generation already running")
            return
        }
        generationCoordinator.launch {
            workflow.update {
                it.copy(
                    isBusy = true,
                    error = null,
                    queuePosition = 0,
                    queueTotal = 0,
                    events = (it.events + timestamped(busyMessage)).takeLast(12),
                )
            }
            try {
                block(novel, settings)
            } catch (e: CancellationException) {
                appendEvent("Generation cancelled")
            } catch (e: Exception) {
                setError(e.message ?: "Generation failed")
            } finally {
                workflow.update {
                    it.copy(
                        isBusy = false,
                        queuePosition = 0,
                        queueTotal = 0,
                        streamingSceneId = null,
                        streamingSceneText = null,
                    )
                }
            }
        }
    }

    private fun requestOverwriteConfirmation(target: OverwriteTarget, action: () -> Unit) {
        if (generationCoordinator.isRunning) {
            setError("Generation already running")
            return
        }
        pendingOverwriteAction = action
        workflow.update { it.copy(error = null, overwriteConfirmation = OverwriteConfirmation(target)) }
        appendEvent("Overwrite confirmation required")
    }

    private suspend fun recordPipelineEvent(novelId: String?, settings: GenerationSettings, event: PipelineEvent) {
        appendPipelineEvent(event)
        if (event is PipelineEvent.UsageRecorded && novelId != null) {
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
            is PipelineEvent.BackgroundGenerated -> appendEvent("Background proposal generated")
            is PipelineEvent.OutlineGenerated -> appendEvent("Outline generated")
            is PipelineEvent.SynopsisGenerated -> appendEvent("Synopsis generated")
            is PipelineEvent.SceneTextComplete -> appendEvent("Scene text generated: ${event.wordCount} words")
            is PipelineEvent.ReviewComplete -> appendEvent("Review score: ${event.result.score}/10")
            is PipelineEvent.BibleUpdated -> appendEvent("Bible updated")
            is PipelineEvent.RollingSummaryUpdated -> appendEvent("Rolling summary updated")
            is PipelineEvent.UsageRecorded -> appendEvent("Usage: ${event.usage.agentName} ${event.usage.inputTokens + event.usage.outputTokens} tokens")
            is PipelineEvent.Error -> setError(event.message)
            is PipelineEvent.SceneTextDelta -> Unit
        }
    }

    private fun appendEvent(message: String) {
        workflow.update { state -> state.copy(events = (state.events + timestamped(message)).takeLast(12)) }
    }

    private fun appendConversation(role: ConversationRole, text: String) {
        conversation.update { state ->
            state.copy(
                messages = (state.messages + ConversationMessage(role = role, text = text)).takeLast(80),
            )
        }
    }

    private fun setError(message: String) {
        workflow.update { state -> state.copy(error = message, events = (state.events + timestamped("Error: $message")).takeLast(12)) }
    }

    private fun timestamped(message: String): String {
        val time = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
        val hour = time.hour.toString().padStart(2, '0')
        val minute = time.minute.toString().padStart(2, '0')
        val second = time.second.toString().padStart(2, '0')
        return "$hour:$minute:$second $message"
    }

    companion object {
        const val MAX_REVIEW_RETRIES = 2
        private const val STREAM_SAVE_MIN_CHARS = 240
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
                generationCoordinator = container.generationCoordinator,
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

private fun String.toImportedGenre(): Genre {
    val normalized = trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    return when (normalized) {
        "fantasy" -> Genre.FANTASY
        "sci_fi", "science_fiction", "sciencefiction", "sf" -> Genre.SCI_FI
        "romance" -> Genre.ROMANCE
        "mystery" -> Genre.MYSTERY
        "thriller" -> Genre.THRILLER
        "literary", "literary_fiction" -> Genre.LITERARY
        "historical", "historical_fiction" -> Genre.HISTORICAL
        "horror" -> Genre.HORROR
        else -> Genre.OTHER
    }
}

private fun ChatImportPayload.toImportedStyleGuide(): StyleGuide {
    return StyleGuide(
        narrativeVoice = parseNarrativeVoice(styleGuide.narrativeVoice),
        tense = parseNarrativeTense(styleGuide.tense),
        proseStyle = parseProseStyle(styleGuide.proseStyle),
        additionalNotes = listOf(styleGuide.narrativeVoice, styleGuide.tense, styleGuide.proseStyle)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("; "),
    )
}

private fun parseNarrativeVoice(value: String): NarrativeVoice {
    val normalized = value.trim().lowercase()
    return when {
        "first" in normalized -> NarrativeVoice.FIRST_PERSON
        "omniscient" in normalized -> NarrativeVoice.THIRD_PERSON_OMNISCIENT
        "second" in normalized -> NarrativeVoice.SECOND_PERSON
        else -> NarrativeVoice.THIRD_PERSON_LIMITED
    }
}

private fun parseNarrativeTense(value: String): NarrativeTense {
    return if ("present" in value.trim().lowercase()) NarrativeTense.PRESENT else NarrativeTense.PAST
}

private fun parseProseStyle(value: String): ProseStyle {
    val normalized = value.trim().lowercase()
    return when {
        "minimal" in normalized || "lean" in normalized || "spare" in normalized -> ProseStyle.MINIMALIST
        "purple" in normalized || "lush" in normalized || "ornate" in normalized -> ProseStyle.PURPLE
        "conversation" in normalized || "casual" in normalized -> ProseStyle.CONVERSATIONAL
        else -> ProseStyle.LITERARY
    }
}

private fun ChatImportPayload.toImportedOutline(): NovelOutline? {
    val importedOutline = outline ?: return null
    val chapters = importedOutline.chapters
        .mapNotNull { chapter -> chapter.toBrief() }
        .sortedBy { it.chapterIndex }
    if (importedOutline.premise.isBlank() && chapters.isEmpty()) return null
    return NovelOutline(
        premise = importedOutline.premise.trim().ifBlank { concept.trim() },
        majorPlotPoints = importedOutline.majorPlotPoints.mapNotNull { point -> point.toPlotPoint() },
        characterArcs = importedOutline.characterArcs.map { it.trim() }.filter { it.isNotBlank() },
        thematicStructure = importedOutline.thematicStructure.trim(),
        chapterCount = chapters.size,
        chapterBriefs = chapters,
    )
}

private fun com.trirrin.xiaoshuo.prompt.ImportedChapter.toBrief(): ChapterBrief? {
    val chapterIndex = index.takeIf { it > 0 } ?: return null
    return ChapterBrief(
        chapterIndex = chapterIndex,
        title = title.trim().ifBlank { "Chapter $chapterIndex" },
        plotBeats = plotBeats.trim().ifBlank { synopsis?.chapterGoal?.trim().orEmpty() },
        purposeInStory = purposeInStory.trim(),
    )
}

private fun com.trirrin.xiaoshuo.prompt.ImportedPlotPoint.toPlotPoint(): PlotPoint? {
    if (name.isBlank() && description.isBlank()) return null
    return PlotPoint(
        name = name.trim().ifBlank { "Plot Point" },
        description = description.trim(),
        position = position.coerceIn(0f, 1f),
    )
}

private fun com.trirrin.xiaoshuo.prompt.ImportedChapterSynopsis.toImportedSynopsis(): ChapterSynopsis? {
    val breakdowns = scenes.mapNotNull { scene -> scene.toBreakdown() }.sortedBy { it.sceneIndex }
    if (chapterGoal.isBlank() && breakdowns.isEmpty()) return null
    return ChapterSynopsis(
        chapterGoal = chapterGoal.trim(),
        sceneBreakdowns = breakdowns,
        chapterEnding = chapterEnding.trim(),
        transitionNotes = transitionNotes.trim(),
    )
}

private fun com.trirrin.xiaoshuo.prompt.ImportedScene.toBreakdown(): SceneBreakdown? {
    val sceneIndex = index.takeIf { it > 0 } ?: return null
    return SceneBreakdown(
        sceneIndex = sceneIndex,
        synopsis = synopsis.trim(),
        targetWordCount = targetWordCount.takeIf { it > 0 } ?: 2500,
    )
}

private fun ChatImportPayload.toImportedBible(): NovelBible {
    return NovelBible(
        characters = bible.characters.mapNotNull { character -> character.toCharacterEntry() },
        locations = bible.locations.mapNotNull { location -> location.toLocationEntry() },
        timelineEvents = bible.timelineEvents.mapNotNull { event -> event.toTimelineEvent() },
        worldRules = bible.worldRules.mapNotNull { rule -> rule.toWorldRule() },
        themes = bible.themes.mapNotNull { theme -> theme.toThemeEntry() },
    )
}

private fun com.trirrin.xiaoshuo.prompt.ImportedCharacter.toCharacterEntry(): CharacterEntry? {
    if (name.isBlank()) return null
    return CharacterEntry(
        name = name.trim(),
        aliases = aliases.map { it.trim() }.filter { it.isNotBlank() },
        description = description.trim(),
        personality = personality.trim(),
        relationships = relationships.mapNotNull { relationship -> relationship.toRelationship() },
        currentState = currentState.trim(),
        source = BibleEntrySource.USER,
    )
}

private fun com.trirrin.xiaoshuo.prompt.ImportedRelationship.toRelationship(): Relationship? {
    if (targetCharacterName.isBlank() || relationType.isBlank()) return null
    return Relationship(
        targetCharacterName = targetCharacterName.trim(),
        relationType = relationType.trim(),
    )
}

private fun com.trirrin.xiaoshuo.prompt.ImportedLocation.toLocationEntry(): LocationEntry? {
    if (name.isBlank()) return null
    return LocationEntry(
        name = name.trim(),
        description = description.trim(),
        significance = significance.trim(),
        source = BibleEntrySource.USER,
    )
}

private fun com.trirrin.xiaoshuo.prompt.ImportedTimelineEvent.toTimelineEvent(): TimelineEvent? {
    if (description.isBlank()) return null
    return TimelineEvent(
        description = description.trim(),
        chronologicalOrder = chronologicalOrder,
        source = BibleEntrySource.USER,
    )
}

private fun com.trirrin.xiaoshuo.prompt.ImportedWorldRule.toWorldRule(): WorldRule? {
    if (category.isBlank() || rule.isBlank()) return null
    return WorldRule(
        category = category.trim(),
        rule = rule.trim(),
        details = details.trim(),
        source = BibleEntrySource.USER,
    )
}

private fun com.trirrin.xiaoshuo.prompt.ImportedTheme.toThemeEntry(): ThemeEntry? {
    if (name.isBlank()) return null
    return ThemeEntry(
        name = name.trim(),
        description = description.trim(),
        motifSymbols = motifSymbols.map { it.trim() }.filter { it.isNotBlank() },
        source = BibleEntrySource.USER,
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
        status = if (needsPolish) ReviewStatus.NEEDS_POLISH else ReviewStatus.PENDING_DECISION,
        qualityScore = qualityScore,
        qualityIssues = qualityIssues,
    )
}

private fun ReviewReport.toNotes(): String {
    return buildString {
        appendLine("Score: $score/10")
        appendLine("Quality: $qualityScore/10")
        appendLine("Passed: $passed")
        if (issues.isNotEmpty()) {
            appendLine()
            appendLine("Issues:")
            issues.forEach { appendLine("- $it") }
        }
        if (qualityIssues.isNotEmpty()) {
            appendLine()
            appendLine("Quality issues:")
            qualityIssues.forEach { appendLine("- $it") }
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
        appendLine("Previous prose quality score: $qualityScore/10")
        appendLine("Previous review passed: $passed")
        appendLine("Retry attempt: $retryCount")
        if (issues.isNotEmpty()) {
            appendLine()
            appendLine("Issues to fix:")
            issues.forEach { appendLine("- $it") }
        }
        if (qualityIssues.isNotEmpty()) {
            appendLine()
            appendLine("Quality issues to polish:")
            qualityIssues.forEach { appendLine("- $it") }
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

private fun String.toToolArguments(json: Json): JsonObject {
    return runCatching { json.decodeFromString<JsonObject>(this) }
        .getOrDefault(JsonObject(emptyMap()))
}

private fun JsonObject.string(name: String): String? {
    return this[name]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
}

private fun NovelBackgroundProposal.toNovel(now: kotlinx.datetime.Instant): Novel {
    val title = titleOptions.firstOrNull { it.isNotBlank() }?.trim() ?: "Untitled Novel"
    return Novel(
        title = title,
        genre = genre,
        concept = toConceptText(),
        themes = themes.map { it.trim() }.filter { it.isNotBlank() },
        styleGuide = styleGuide,
        bible = initialBibleCandidates,
        status = NovelStatus.DRAFTING_OUTLINE,
        createdAt = now,
        updatedAt = now,
    )
}

private fun NovelBackgroundProposal.toConceptText(): String {
    return listOf(
        premise,
        worldSetup,
        protagonistSeed,
        majorConflict,
    ).map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n\n")
}

private fun NovelBackgroundProposal.toPreviewText(): String {
    return buildString {
        appendSection("Title Options", titleOptions.joinToString("\n") { "- $it" })
        appendSection("Genre And Tone", listOf(genre.label, tone).filter { it.isNotBlank() }.joinToString(" | "))
        appendSection("Premise", premise)
        appendSection("World Setup", worldSetup)
        appendSection("Protagonist", protagonistSeed)
        appendSection("Core Cast", coreCastSeeds.joinToString("\n") { "- $it" })
        appendSection("Major Conflict", majorConflict)
        appendSection("Themes", themes.joinToString(", "))
        appendSection("Style Guide", styleGuide.toPreviewText())
        appendSection("Initial Bible Candidates", initialBibleCandidates.toPreviewText())
    }.trim()
}

private fun NovelOutline.toPreviewText(): String {
    return buildString {
        appendSection("Premise", premise)
        appendSection("Major Plot Points", majorPlotPoints.joinToString("\n") { point ->
            "- ${point.name} (${point.position}): ${point.description}"
        })
        appendSection("Character Arcs", characterArcs.joinToString("\n") { "- $it" })
        appendSection("Thematic Structure", thematicStructure)
        appendSection("Chapters", chapterBriefs.joinToString("\n") { brief ->
            "${brief.chapterIndex}. ${brief.title}\n   ${brief.plotBeats}\n   ${brief.purposeInStory}"
        })
    }.trim()
}

private fun StyleGuide.toPreviewText(): String {
    return listOf(
        "Voice: $narrativeVoice",
        "Tense: $tense",
        "Prose: $proseStyle",
        "Scene target: $targetSceneWordCountMin-$targetSceneWordCountMax words",
        additionalNotes.takeIf { it.isNotBlank() }?.let { "Notes: $it" },
    ).filterNotNull().joinToString("\n")
}

private fun NovelBible.toPreviewText(): String {
    return buildString {
        appendSection("Characters", characters.joinToString("\n") { "- ${it.name}: ${it.description}" })
        appendSection("Locations", locations.joinToString("\n") { "- ${it.name}: ${it.description}" })
        appendSection("World Rules", worldRules.joinToString("\n") { "- ${it.category}: ${it.rule}" })
        appendSection("Themes", themes.joinToString("\n") { "- ${it.name}: ${it.description}" })
    }.trim().ifBlank { "No initial Bible candidates." }
}

private fun StringBuilder.appendSection(title: String, body: String) {
    if (body.isBlank()) return
    if (isNotEmpty()) appendLine()
    appendLine(title)
    appendLine(body)
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
    val conversation: ConversationState = ConversationState(),
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

data class ConversationState(
    val messages: List<ConversationMessage> = listOf(
        ConversationMessage(
            role = ConversationRole.ASSISTANT,
            text = "Tell me what kind of novel you want to write. I will turn it into proposals you can approve before anything is saved.",
        ),
    ),
    val pendingApproval: PendingApproval? = null,
)

data class ConversationMessage(
    val role: ConversationRole,
    val text: String,
    val createdAt: kotlinx.datetime.Instant = Clock.System.now(),
)

enum class ConversationRole {
    USER,
    ASSISTANT,
}

data class PendingApproval(
    val id: String = UUID.randomUUID().toString(),
    val novelId: String? = null,
    val targetType: ApprovalTargetType,
    val actionName: String,
    val previewTitle: String,
    val previewText: String,
    val riskLevel: ApprovalRiskLevel,
    val requiredBeforeCommit: Boolean,
    val payload: ApprovalPayload,
    val createdAt: kotlinx.datetime.Instant = Clock.System.now(),
)

enum class ApprovalTargetType {
    NOVEL_BACKGROUND,
    OUTLINE,
    OUTLINE_STRUCTURE_CHANGE,
}

enum class ApprovalRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

sealed interface ApprovalPayload {
    data class Background(
        val userRequest: String,
        val proposal: NovelBackgroundProposal,
    ) : ApprovalPayload

    data class Outline(
        val novelId: String,
        val outline: NovelOutline,
    ) : ApprovalPayload
}

data class WorkflowState(
    val isBusy: Boolean = false,
    val error: String? = null,
    val events: List<String> = emptyList(),
    val streamingSceneId: String? = null,
    val streamingSceneText: String? = null,
    val queuePosition: Int = 0,
    val queueTotal: Int = 0,
    val overwriteConfirmation: OverwriteConfirmation? = null,
)

data class OverwriteConfirmation(
    val target: OverwriteTarget,
)

enum class OverwriteTarget {
    OUTLINE,
    SYNOPSIS,
    SCENE,
}

class GenerationCoordinator(private val scope: CoroutineScope) {
    val workflow = MutableStateFlow(WorkflowState())
    private var activeJob: kotlinx.coroutines.Job? = null

    val isRunning: Boolean
        get() = activeJob?.isActive == true

    fun launch(block: suspend () -> Unit) {
        check(activeJob?.isActive != true) { "Generation already running" }
        activeJob = scope.launch {
            try {
                block()
            } finally {
                activeJob = null
            }
        }
    }

    fun cancel(): Boolean {
        val job = activeJob?.takeIf { it.isActive } ?: return false
        job.cancel()
        return true
    }
}
