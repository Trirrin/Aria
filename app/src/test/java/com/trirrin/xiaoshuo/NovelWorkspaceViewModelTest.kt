package com.trirrin.xiaoshuo

import app.cash.turbine.test
import com.trirrin.xiaoshuo.agent.AgentPipeline
import com.trirrin.xiaoshuo.agent.AgentResult
import com.trirrin.xiaoshuo.agent.ChapterSynopsisPipelineResult
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
        generationCoordinator = GenerationCoordinator(TestScope(dispatcher))

        every { novelRepository.observeNovels() } returns novels
        every { novelRepository.observeChapters(any()) } returns chapters
        every { novelRepository.observeScenes(any()) } returns scenes
        every { novelRepository.observeScenesForNovel(any()) } returns allScenes
        every { novelRepository.observeRevisionSnapshots(any()) } returns flowOf(emptyList())
        every { novelRepository.observeTokenUsage(any()) } returns flowOf(emptyList())
        every { settingsRepository.settings } returns settings
        coEvery { novelRepository.resetInterruptedScenes() } returns 0
        coEvery { novelRepository.getChapters(any()) } returns emptyList()
        coEvery { novelRepository.getScenes(any()) } returns emptyList()
        coEvery { novelRepository.getNovel(any()) } answers { novels.value.firstOrNull { it.id == firstArg<String>() } }
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
    fun `generation without api key reports error and clears busy state`() = runTestWithMain {
        settings.value = GenerationSettings(apiKey = "")
        val novel = sampleNovel(outline = sampleOutline())
        novels.value = listOf(novel)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItemsUntil { it.selectedNovel?.id == novel.id }
            viewModel.generateOutline()
            skipItemsUntil { it.workflow.overwriteConfirmation?.target == OverwriteTarget.OUTLINE }
            viewModel.confirmOverwrite()
            val failed = skipItemsUntil { it.workflow.error == "API key is required before generation" }

            assertFalse(failed.workflow.isBusy)
            coVerify(exactly = 0) { pipeline.generateOutline(any()) }
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
