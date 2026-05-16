package com.trirrin.xiaoshuo

import app.cash.turbine.test
import com.trirrin.xiaoshuo.agent.AgentPipeline
import com.trirrin.xiaoshuo.agent.AgentResult
import com.trirrin.xiaoshuo.agent.ChapterSynopsisPipelineResult
import com.trirrin.xiaoshuo.agent.PipelineEvent
import com.trirrin.xiaoshuo.agent.WorkflowToolCall
import com.trirrin.xiaoshuo.data.ConversationSessionRecord
import com.trirrin.xiaoshuo.data.GenerationSettings
import com.trirrin.xiaoshuo.data.GenerationSettingsRepository
import com.trirrin.xiaoshuo.data.NovelRepository
import com.trirrin.xiaoshuo.data.PendingApprovalRecord
import com.trirrin.xiaoshuo.data.ToolCallAuditRecord
import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.ChapterBrief
import com.trirrin.xiaoshuo.model.ChapterStatus
import com.trirrin.xiaoshuo.model.ChapterSynopsis
import com.trirrin.xiaoshuo.model.CharacterEntry
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.model.PlotPoint
import com.trirrin.xiaoshuo.model.ReviewStatus
import com.trirrin.xiaoshuo.model.Scene
import com.trirrin.xiaoshuo.model.SceneBreakdown
import com.trirrin.xiaoshuo.model.SceneStatus
import com.trirrin.xiaoshuo.model.SceneTextSource
import com.trirrin.xiaoshuo.model.NovelStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NovelWorkspaceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var novelRepository: NovelRepository
    private lateinit var settingsRepository: GenerationSettingsRepository
    private lateinit var pipeline: AgentPipeline
    private lateinit var novels: MutableStateFlow<List<Novel>>
    private lateinit var chapters: MutableStateFlow<List<Chapter>>
    private lateinit var scenes: MutableStateFlow<List<Scene>>
    private lateinit var allScenes: MutableStateFlow<List<Scene>>
    private lateinit var settings: MutableStateFlow<GenerationSettings>
    private lateinit var conversationSessions: MutableStateFlow<List<ConversationSessionRecord>>
    private lateinit var pendingApprovals: MutableStateFlow<List<PendingApprovalRecord>>
    private lateinit var toolCallAudits: MutableStateFlow<List<ToolCallAuditRecord>>
    private lateinit var generationCoordinator: GenerationCoordinator

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        novelRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        pipeline = mockk(relaxed = true)
        novels = MutableStateFlow(emptyList())
        chapters = MutableStateFlow(emptyList())
        scenes = MutableStateFlow(emptyList())
        allScenes = MutableStateFlow(emptyList())
        settings = MutableStateFlow(GenerationSettings(apiKey = "test-key"))
        conversationSessions = MutableStateFlow(emptyList())
        pendingApprovals = MutableStateFlow(emptyList())
        toolCallAudits = MutableStateFlow(emptyList())
        generationCoordinator = GenerationCoordinator(TestScope(dispatcher))

        every { novelRepository.observeNovels() } returns novels
        every { novelRepository.observeChapters(any()) } returns chapters
        every { novelRepository.observeScenes(any()) } returns scenes
        every { novelRepository.observeScenesForNovel(any()) } returns allScenes
        every { novelRepository.observeRevisionSnapshots(any()) } returns flowOf(emptyList())
        every { novelRepository.observeTokenUsage(any()) } returns flowOf(emptyList())
        every { novelRepository.observeConversationSessions() } returns conversationSessions
        every { novelRepository.observePendingApprovals() } returns pendingApprovals
        every { novelRepository.observeToolCallAudits(any()) } returns toolCallAudits
        every { settingsRepository.settings } returns settings
        coEvery { novelRepository.resetInterruptedScenes() } returns 0
        coEvery { novelRepository.getChapters(any()) } returns emptyList()
        coEvery { novelRepository.getScenes(any()) } returns emptyList()
        coEvery { novelRepository.getNovel(any()) } answers { novels.value.firstOrNull { it.id == firstArg<String>() } }
        coEvery { novelRepository.saveConversationSession(any()) } answers {
            val record = firstArg<ConversationSessionRecord>()
            conversationSessions.value = listOf(record)
        }
        coEvery { novelRepository.savePendingApproval(any()) } answers {
            val record = firstArg<PendingApprovalRecord>()
            pendingApprovals.value = listOf(record)
        }
        coEvery { novelRepository.deletePendingApproval(any()) } answers {
            val id = firstArg<String>()
            pendingApprovals.value = pendingApprovals.value.filterNot { it.id == id }
        }
        coEvery { novelRepository.saveToolCallAudit(any()) } answers {
            val record = firstArg<ToolCallAuditRecord>()
            toolCallAudits.value = toolCallAudits.value + record
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selection follows selected novel chapter and scene`() = runTestWithMain {
        val novel = sampleNovel(outline = sampleOutline())
        val chapter = sampleChapter(novel.id, synopsis = sampleSynopsis())
        val scene = sampleScene(novel.id, chapter.id)
        novels.value = listOf(novel)
        chapters.value = listOf(chapter)
        scenes.value = listOf(scene)
        allScenes.value = listOf(scene)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItemsUntil { it.selectedNovel?.id == novel.id }
            viewModel.selectChapter(chapter.id)
            skipItemsUntil { it.selectedChapter?.id == chapter.id }
            viewModel.selectScene(scene.id)
            val selected = skipItemsUntil { it.selectedScene?.id == scene.id }

            assertEquals(novel.id, selected.selectedNovel?.id)
            assertEquals(chapter.id, selected.selectedChapter?.id)
            assertEquals(scene.id, selected.selectedScene?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save scene text marks user edit and recalculates word count`() = runTestWithMain {
        val novel = sampleNovel(outline = sampleOutline())
        val chapter = sampleChapter(novel.id, synopsis = sampleSynopsis())
        val scene = sampleScene(novel.id, chapter.id).copy(
            text = "old generated text",
            textSource = SceneTextSource.GENERATED,
            status = SceneStatus.REVIEWED,
        )
        novels.value = listOf(novel)
        chapters.value = listOf(chapter)
        scenes.value = listOf(scene)
        allScenes.value = listOf(scene)
        val savedScene = slot<Scene>()
        coEvery { novelRepository.upsertScene(capture(savedScene)) } answers {
            scenes.value = listOf(savedScene.captured)
        }
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItemsUntil { it.selectedScene?.id == scene.id }
            viewModel.saveSceneText("one two three")
            val saved = skipItemsUntil { it.selectedScene?.text == "one two three" }.selectedScene

            assertEquals(SceneStatus.GENERATED, saved?.status)
            assertEquals(SceneTextSource.EDITED, saved?.textSource)
            assertEquals(3, saved?.wordCount)
            coVerify { novelRepository.saveRevisionSnapshot(novel.id, "scene", scene.id, "Scene before edit", any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `generate synopsis saves review and scene shells`() = runTestWithMain {
        val outline = sampleOutline()
        val novel = sampleNovel(outline = outline)
        val chapter = sampleChapter(novel.id, synopsis = null)
        val synopsis = sampleSynopsis()
        novels.value = listOf(novel)
        chapters.value = listOf(chapter)
        scenes.value = emptyList()
        allScenes.value = emptyList()
        coEvery { novelRepository.getChapters(novel.id) } returns listOf(chapter)
        coEvery { pipeline.generateChapterSynopsis(novel, chapter, listOf(chapter), reviewFeedback = null) } returns ChapterSynopsisPipelineResult(
            synopsis = synopsis,
            review = AgentResult.ReviewResult(
                score = 8,
                issues = listOf("minor drift"),
                suggestedFixes = listOf("tighten scene goal"),
                passed = true,
            ),
        )
        val savedChapter = slot<Chapter>()
        val savedScenes = mutableListOf<Scene>()
        coEvery { novelRepository.upsertChapter(capture(savedChapter)) } answers {
            chapters.value = listOf(savedChapter.captured)
        }
        coEvery { novelRepository.upsertScene(capture(savedScenes)) } answers {
            scenes.value = savedScenes.toList()
            allScenes.value = savedScenes.toList()
        }
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItemsUntil { it.selectedChapter?.id == chapter.id }
            viewModel.generateSelectedSynopsis()
            val generated = skipItemsUntil { it.selectedChapter?.synopsis != null && !it.workflow.isBusy }

            assertEquals(ChapterStatus.SYNOPSIS_GENERATED, generated.selectedChapter?.status)
            assertEquals(ReviewStatus.PENDING_DECISION, generated.selectedChapter?.reviewReport?.status)
            assertEquals(1, generated.scenes.size)
            assertEquals("Scene one", generated.scenes.single().synopsis)
            assertNull(generated.workflow.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `generate scene recovers original scene when pipeline fails`() = runTestWithMain {
        val novel = sampleNovel(outline = sampleOutline())
        val chapter = sampleChapter(novel.id, synopsis = sampleSynopsis())
        val scene = sampleScene(novel.id, chapter.id).copy(text = "user draft", textSource = SceneTextSource.EDITED)
        novels.value = listOf(novel)
        chapters.value = listOf(chapter)
        scenes.value = listOf(scene)
        allScenes.value = listOf(scene)
        coEvery { novelRepository.getChapters(novel.id) } returns listOf(chapter)
        coEvery { novelRepository.getScenes(chapter.id) } returns listOf(scene)
        coEvery { pipeline.streamSceneEvents(novel, chapter, scene, any()) } returns flow {
            throw IllegalStateException("provider exploded")
        }
        val savedScenes = mutableListOf<Scene>()
        coEvery { novelRepository.upsertScene(capture(savedScenes)) } answers {
            scenes.value = listOf(savedScenes.last())
            allScenes.value = listOf(savedScenes.last())
        }
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItemsUntil { it.selectedScene?.id == scene.id }
            viewModel.generateSelectedScene()
            skipItemsUntil { it.workflow.overwriteConfirmation?.target == OverwriteTarget.SCENE }
            viewModel.confirmOverwrite()
            val failed = skipItemsUntil { it.workflow.error == "provider exploded" && !it.workflow.isBusy }

            assertEquals("user draft", failed.selectedScene?.text)
            assertEquals(SceneTextSource.EDITED, failed.selectedScene?.textSource)
            assertEquals(SceneStatus.GENERATED, failed.selectedScene?.status)
            assertTrue(savedScenes.any { it.status == SceneStatus.GENERATING })
            assertEquals(scene, savedScenes.last())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `regenerate scene requires overwrite confirmation before touching pipeline`() = runTestWithMain {
        val novel = sampleNovel(outline = sampleOutline())
        val chapter = sampleChapter(novel.id, synopsis = sampleSynopsis())
        val scene = sampleScene(novel.id, chapter.id).copy(text = "user draft", textSource = SceneTextSource.EDITED)
        novels.value = listOf(novel)
        chapters.value = listOf(chapter)
        scenes.value = listOf(scene)
        allScenes.value = listOf(scene)
        coEvery { novelRepository.getChapters(novel.id) } returns listOf(chapter)
        coEvery { novelRepository.getScenes(chapter.id) } returns listOf(scene)
        coEvery { pipeline.streamSceneEvents(any(), any(), any(), any()) } returns flowOf(PipelineEvent.SceneTextDelta("new prose"))
        coEvery { pipeline.finalizeSceneText(any(), any(), any(), any()) } returns flowOf()
        val savedScenes = mutableListOf<Scene>()
        coEvery { novelRepository.upsertScene(capture(savedScenes)) } answers {
            scenes.value = listOf(savedScenes.last())
            allScenes.value = listOf(savedScenes.last())
        }
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItemsUntil { it.selectedScene?.id == scene.id }
            viewModel.generateSelectedScene()
            val pending = skipItemsUntil { it.workflow.overwriteConfirmation?.target == OverwriteTarget.SCENE }

            assertFalse(pending.workflow.isBusy)
            coVerify(exactly = 0) { pipeline.streamSceneEvents(any(), any(), any(), any()) }

            viewModel.confirmOverwrite()
            val generated = skipItemsUntil { it.selectedScene?.text == "new prose" && !it.workflow.isBusy }

            assertNull(generated.workflow.overwriteConfirmation)
            coVerify { pipeline.streamSceneEvents(novel, chapter, scene, any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancel overwrite leaves existing scene untouched`() = runTestWithMain {
        val novel = sampleNovel(outline = sampleOutline())
        val chapter = sampleChapter(novel.id, synopsis = sampleSynopsis())
        val scene = sampleScene(novel.id, chapter.id).copy(text = "user draft", textSource = SceneTextSource.EDITED)
        novels.value = listOf(novel)
        chapters.value = listOf(chapter)
        scenes.value = listOf(scene)
        allScenes.value = listOf(scene)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItemsUntil { it.selectedScene?.id == scene.id }
            viewModel.generateSelectedScene()
            skipItemsUntil { it.workflow.overwriteConfirmation?.target == OverwriteTarget.SCENE }
            viewModel.cancelOverwrite()
            val cancelled = skipItemsUntil { it.workflow.overwriteConfirmation == null && it.workflow.events.any { event -> event.contains("Overwrite cancelled") } }

            assertEquals("user draft", cancelled.selectedScene?.text)
            coVerify(exactly = 0) { pipeline.streamSceneEvents(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `import from chat creates novel outline bible and edited prose`() = runTestWithMain {
        val savedNovels = mutableListOf<Novel>()
        val savedChapters = mutableListOf<Chapter>()
        val savedScenes = mutableListOf<Scene>()
        coEvery { novelRepository.upsertNovel(capture(savedNovels)) } answers {
            novels.value = savedNovels.toList()
        }
        coEvery { novelRepository.upsertChapter(capture(savedChapters)) } answers {
            chapters.value = savedChapters.toList()
            savedChapters.last()
        }
        coEvery { novelRepository.upsertScene(capture(savedScenes)) } answers {
            scenes.value = savedScenes.toList()
            allScenes.value = savedScenes.toList()
        }
        coEvery { novelRepository.getScenes(any()) } answers { savedScenes.filter { it.chapterId == firstArg<String>() } }
        val viewModel = createViewModel()
        val rawImport = """
            {
              "title": "Ash Ledger",
              "genre": "FANTASY",
              "concept": "A tax clerk inherits a ledger that records debts owed by the dead.",
              "themes": ["memory"],
              "styleGuide": {"narrativeVoice": "first person", "tense": "present", "proseStyle": "minimalist"},
              "outline": {
                "premise": "A clerk must settle supernatural debts.",
                "majorPlotPoints": [{"name": "Inciting Incident", "description": "The ledger wakes.", "position": 0.12}],
                "chapters": [{"index": 1, "title": "The Ledger Wakes", "plotBeats": "Ira opens it.", "purposeInStory": "Start", "synopsis": {"chapterGoal": "Accept the ledger.", "chapterEnding": "A knock.", "scenes": [{"index": 1, "synopsis": "Ira audits a ghost.", "targetWordCount": 1200, "prose": "The ledger breathed ash."}]}}]
              },
              "bible": {"characters": [{"name": "Ira", "description": "A clerk."}], "locations": [], "timelineEvents": [], "worldRules": [], "themes": []}
            }
        """.trimIndent()

        viewModel.uiState.test {
            viewModel.importFromChat(rawImport)
            val imported = skipItemsUntil {
                it.selectedNovel?.title == "Ash Ledger" &&
                    it.chapters.isNotEmpty() &&
                    it.scenes.isNotEmpty()
            }

            assertEquals(NovelStatus.OUTLINE_COMPLETE, imported.selectedNovel?.status)
            assertEquals("Ira", imported.selectedNovel?.bible?.characters?.single()?.name)
            assertEquals("The Ledger Wakes", imported.chapters.single().title)
            assertEquals("The ledger breathed ash.", imported.scenes.single().text)
            assertEquals(SceneTextSource.EDITED, imported.scenes.single().textSource)
            assertEquals(SceneStatus.GENERATED, imported.scenes.single().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `conversation scene proposal waits for approval before saving scene and bible`() = runTestWithMain {
        val novel = sampleNovel(outline = sampleOutline())
        val chapter = sampleChapter(novel.id, synopsis = sampleSynopsis())
        val scene = sampleScene(novel.id, chapter.id)
        val proposedBible = NovelBible(characters = listOf(CharacterEntry(name = "Mira", currentState = "awake")))
        novels.value = listOf(novel)
        chapters.value = listOf(chapter)
        scenes.value = listOf(scene)
        allScenes.value = listOf(scene)
        coEvery { novelRepository.getChapters(novel.id) } returns listOf(chapter)
        coEvery { novelRepository.getScenes(chapter.id) } returns listOf(scene)
        coEvery { pipeline.planConversationTool(any()) } returns (
            WorkflowToolCall("tool-1", "generateSceneTextProposal", "{}") to emptyList()
        )
        coEvery {
            pipeline.generateScene(
                novel = novel,
                chapter = chapter,
                scene = scene,
                previousSceneEnding = null,
                reviewFeedback = null,
                referenceProseStyle = null,
            )
        } returns flowOf(
            PipelineEvent.SceneTextComplete("new prose", 2),
            PipelineEvent.ReviewComplete(
                AgentResult.ReviewResult(
                    score = 8,
                    issues = emptyList(),
                    suggestedFixes = emptyList(),
                    passed = true,
                ),
            ),
            PipelineEvent.BibleUpdated(proposedBible),
        )
        val savedScenes = mutableListOf<Scene>()
        val savedNovels = mutableListOf<Novel>()
        val savedApprovals = mutableListOf<PendingApprovalRecord>()
        val deletedApprovalIds = mutableListOf<String>()
        val savedAudits = mutableListOf<ToolCallAuditRecord>()
        coEvery { novelRepository.upsertScene(capture(savedScenes)) } answers {
            scenes.value = listOf(savedScenes.last())
        }
        coEvery { novelRepository.upsertNovel(capture(savedNovels)) } answers {
            novels.value = listOf(savedNovels.last())
        }
        coEvery { novelRepository.savePendingApproval(capture(savedApprovals)) } answers {
            pendingApprovals.value = listOf(savedApprovals.last())
        }
        coEvery { novelRepository.deletePendingApproval(capture(deletedApprovalIds)) } answers {
            pendingApprovals.value = pendingApprovals.value.filterNot { it.id == deletedApprovalIds.last() }
        }
        coEvery { novelRepository.saveToolCallAudit(capture(savedAudits)) } answers {
            toolCallAudits.value = toolCallAudits.value + savedAudits.last()
        }
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItemsUntil { it.selectedScene?.id == scene.id }
            viewModel.submitConversationMessage("draft scene one")
            val proposed = skipItemsUntil { it.conversation.pendingApproval?.targetType == ApprovalTargetType.SCENE_TEXT && !it.workflow.isBusy }

            assertEquals("new prose", proposed.conversation.pendingApproval?.previewText?.substringAfter("Scene Draft\n")?.substringBefore("\n\nReview"))
            assertTrue(savedScenes.isEmpty())
            assertTrue(savedNovels.isEmpty())

            viewModel.acceptPendingApproval()
            val canonPending = skipItemsUntil { it.conversation.pendingApproval?.targetType == ApprovalTargetType.BIBLE_UPDATE }

            assertEquals("new prose", savedScenes.single().text)
            assertEquals(SceneStatus.REVIEWED, savedScenes.single().status)
            assertTrue(savedNovels.isEmpty())
            assertEquals(ApprovalTargetType.BIBLE_UPDATE, canonPending.conversation.pendingApproval?.targetType)
            assertEquals(ApprovalTargetType.BIBLE_UPDATE.name, savedApprovals.last().targetType)
            assertTrue(deletedApprovalIds.contains(proposed.conversation.pendingApproval?.id))
            assertTrue(savedAudits.any { it.functionName == "generateSceneTextProposal" && it.resultStatus == "planned" })
            assertTrue(canonPending.conversation.toolCallAudits.any { it.functionName == "generateSceneTextProposal" && it.resultStatus == "planned" })

            viewModel.acceptPendingApproval()
            skipItemsUntil { it.conversation.pendingApproval == null && savedNovels.isNotEmpty() }

            assertTrue(pendingApprovals.value.isEmpty())
            assertEquals("Mira", savedNovels.single().bible.characters.single().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restores persisted conversation approval on startup`() = runTestWithMain {
        val novel = sampleNovel(outline = sampleOutline())
        novels.value = listOf(novel)
        val chapter = sampleChapter(novel.id, synopsis = sampleSynopsis())
        val scene = sampleScene(novel.id, chapter.id)
        chapters.value = listOf(chapter)
        scenes.value = listOf(scene)
        allScenes.value = listOf(scene)
        val approval = PendingApproval(
            novelId = novel.id,
            targetType = ApprovalTargetType.SCENE_TEXT,
            targetId = scene.id,
            actionName = "acceptSceneTextProposal",
            previewTitle = "Scene 1: Chapter 1",
            previewText = "Scene Draft\nrestored prose",
            riskLevel = ApprovalRiskLevel.MEDIUM,
            requiredBeforeCommit = true,
            payload = ApprovalPayload.SceneText(
                novelId = novel.id,
                chapterId = chapter.id,
                sceneId = scene.id,
                chapterOrder = 1,
                sceneOrder = 1,
                text = "restored prose",
                wordCount = 2,
                reviewReport = null,
                proposedBible = null,
            ),
        )
        pendingApprovals.value = listOf(
            PendingApprovalRecord(
                id = approval.id,
                novelId = approval.novelId,
                targetType = approval.targetType.name,
                targetId = approval.targetId,
                actionName = approval.actionName,
                previewTitle = approval.previewTitle,
                previewText = approval.previewText,
                proposedPayloadJson = savedApprovalJson(approval),
                riskLevel = approval.riskLevel.name,
                requiredBeforeCommit = approval.requiredBeforeCommit,
                createdAt = approval.createdAt,
            ),
        )
        conversationSessions.value = listOf(
            ConversationSessionRecord(
                id = "session-1",
                novelId = novel.id,
                messagesJson = savedMessagesJson(),
                activeToolCallJson = savedToolCallJson(),
            ),
        )
        toolCallAudits.value = listOf(
            ToolCallAuditRecord(
                sessionId = "session-1",
                novelId = novel.id,
                functionName = "generateSceneTextProposal",
                argumentSummary = "{}",
                resultStatus = "planned",
                resultMessage = "Conversation tool selected",
            ),
        )
        val restored = createViewModel()

        restored.uiState.test {
            advanceUntilIdle()
            val state = skipItemsUntil {
                it.conversation.pendingApproval?.id == approval.id &&
                    it.selectedChapter?.id == chapter.id &&
                    it.selectedScene?.id == scene.id
            }

            assertEquals("session-1", state.conversation.sessionId)
            assertEquals("restore me", state.conversation.messages.single().text)
            assertEquals("generateSceneTextProposal", state.conversation.activeToolCall?.name)
            assertEquals("restored prose", (state.conversation.pendingApproval?.payload as ApprovalPayload.SceneText).text)
            assertEquals(chapter.id, state.selectedChapter?.id)
            assertEquals(scene.id, state.selectedScene?.id)
            assertTrue(state.conversation.toolCallAudits.any { it.functionName == "generateSceneTextProposal" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `generation without api key reports error and clears busy state`() = runTestWithMain {
        settings.value = GenerationSettings(apiKey = "")
        val novel = sampleNovel(outline = sampleOutline())
        novels.value = listOf(novel)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItemsUntil { it.selectedNovel?.id == novel.id }
            viewModel.generateOutline()
            val failed = skipItemsUntil { it.workflow.error == "API key is required before generation" }

            assertFalse(failed.workflow.isBusy)
            assertNull(failed.workflow.overwriteConfirmation)
            assertNull(failed.conversation.pendingApproval)
            coVerify(exactly = 0) { pipeline.generateOutline(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createViewModel(): NovelWorkspaceViewModel {
        return NovelWorkspaceViewModel(
            novelRepository = novelRepository,
            settingsRepository = settingsRepository,
            pipelineFactory = { pipeline },
            generationCoordinator = generationCoordinator,
        )
    }

    private fun runTestWithMain(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest(dispatcher, testBody = block)

    private suspend fun app.cash.turbine.ReceiveTurbine<NovelWorkspaceUiState>.skipItemsUntil(
        predicate: (NovelWorkspaceUiState) -> Boolean,
    ): NovelWorkspaceUiState {
        repeat(40) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
        throw AssertionError("Expected UI state was not emitted")
    }

    private fun savedMessagesJson(): String {
        return Json.encodeToString(listOf(ConversationMessage(ConversationRole.USER, "restore me")))
    }

    private fun savedToolCallJson(): String {
        return Json.encodeToString(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("tool-1"),
                    "name" to JsonPrimitive("generateSceneTextProposal"),
                    "argumentsJson" to JsonPrimitive("{}"),
                ),
            ),
        )
    }

    private fun savedApprovalJson(approval: PendingApproval): String {
        val sceneText = approval.payload as ApprovalPayload.SceneText
        return Json.encodeToString(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("SCENE_TEXT"),
                    "background" to JsonNull,
                    "outline" to JsonNull,
                    "chapterSynopsis" to JsonNull,
                    "sceneText" to JsonObject(
                        mapOf(
                            "novelId" to JsonPrimitive(sceneText.novelId),
                            "chapterId" to JsonPrimitive(sceneText.chapterId),
                            "sceneId" to JsonPrimitive(sceneText.sceneId),
                            "chapterOrder" to JsonPrimitive(sceneText.chapterOrder),
                            "sceneOrder" to JsonPrimitive(sceneText.sceneOrder),
                            "text" to JsonPrimitive(sceneText.text),
                            "wordCount" to JsonPrimitive(sceneText.wordCount),
                            "reviewReport" to JsonNull,
                            "proposedBible" to JsonNull,
                        ),
                    ),
                    "bibleUpdate" to JsonNull,
                ),
            ),
        )
    }

    private fun sampleNovel(outline: NovelOutline? = null): Novel {
        return Novel(
            id = "novel-1",
            title = "Test Novel",
            genre = Genre.FANTASY,
            concept = "A test concept",
            outline = outline,
            createdAt = Instant.fromEpochMilliseconds(100),
            updatedAt = Instant.fromEpochMilliseconds(100),
        )
    }

    private fun sampleOutline(): NovelOutline {
        return NovelOutline(
            premise = "A premise",
            majorPlotPoints = listOf(PlotPoint("Start", "Things begin", 0.1f)),
            chapterCount = 1,
            chapterBriefs = listOf(
                ChapterBrief(
                    chapterIndex = 1,
                    title = "Chapter One",
                    plotBeats = "A first beat",
                    purposeInStory = "Open the story",
                ),
            ),
        )
    }

    private fun sampleChapter(novelId: String, synopsis: ChapterSynopsis?): Chapter {
        return Chapter(
            id = "chapter-1",
            novelId = novelId,
            order = 1,
            title = "Chapter One",
            synopsis = synopsis,
            status = if (synopsis == null) ChapterStatus.PENDING else ChapterStatus.SYNOPSIS_GENERATED,
        )
    }

    private fun sampleSynopsis(): ChapterSynopsis {
        return ChapterSynopsis(
            chapterGoal = "Reach the first door",
            sceneBreakdowns = listOf(SceneBreakdown(sceneIndex = 1, synopsis = "Scene one", targetWordCount = 500)),
            chapterEnding = "The door opens",
        )
    }

    private fun sampleScene(novelId: String, chapterId: String): Scene {
        return Scene(
            id = "scene-1",
            chapterId = chapterId,
            novelId = novelId,
            order = 1,
            synopsis = "Scene one",
            text = "",
            status = SceneStatus.GENERATED,
            wordCount = 0,
            textSource = SceneTextSource.EMPTY,
        )
    }
}
