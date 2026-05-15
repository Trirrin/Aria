package com.trirrin.xiaoshuo.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.trirrin.xiaoshuo.model.BibleConflict
import com.trirrin.xiaoshuo.model.BibleConflictSection
import com.trirrin.xiaoshuo.model.BibleEntrySource
import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.ChapterBrief
import com.trirrin.xiaoshuo.model.ChapterStatus
import com.trirrin.xiaoshuo.model.ChapterSynopsis
import com.trirrin.xiaoshuo.model.CharacterEntry
import com.trirrin.xiaoshuo.model.LocationEntry
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.NarrativeTense
import com.trirrin.xiaoshuo.model.NarrativeVoice
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.model.NovelStatus
import com.trirrin.xiaoshuo.model.PlotPoint
import com.trirrin.xiaoshuo.model.ProseStyle
import com.trirrin.xiaoshuo.model.ReviewReport
import com.trirrin.xiaoshuo.model.ReviewStatus
import com.trirrin.xiaoshuo.model.Scene
import com.trirrin.xiaoshuo.model.SceneBreakdown
import com.trirrin.xiaoshuo.model.SceneStatus
import com.trirrin.xiaoshuo.model.SceneTextSource
import com.trirrin.xiaoshuo.model.StyleGuide
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NovelRepositoryTest {
    private lateinit var database: XiaoShuoDatabase
    private lateinit var repository: NovelRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, XiaoShuoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NovelRepository(database.novelDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `novel chapter and scene roundtrip preserves structured fields`() = runTest {
        val novel = sampleNovel()
        val chapter = sampleChapter(novel.id)
        val scene = sampleScene(novel.id, chapter.id)

        repository.upsertNovel(novel)
        repository.upsertChapter(chapter)
        repository.upsertScene(scene)

        val savedNovel = repository.getNovel(novel.id)
        val savedChapter = repository.getChapters(novel.id).single()
        val savedScene = repository.getScenes(chapter.id).single()

        assertEquals(novel, savedNovel)
        assertEquals(chapter, savedChapter)
        assertEquals(scene, savedScene)
        assertEquals(ReviewStatus.NEEDS_RETRY, savedChapter.reviewReport?.status)
        assertEquals(SceneTextSource.EDITED, savedScene.textSource)
        assertEquals(SceneStatus.REVIEWED, savedScene.status)
    }

    @Test
    fun `reset interrupted scenes never marks partial drafts generated`() = runTest {
        val novel = sampleNovel()
        val chapter = sampleChapter(novel.id)
        repository.upsertNovel(novel)
        repository.upsertChapter(chapter)
        repository.upsertScene(
            sampleScene(novel.id, chapter.id).copy(
                id = "blank-generating-scene",
                order = 1,
                text = "",
                status = SceneStatus.GENERATING,
                textSource = SceneTextSource.EMPTY,
            ),
        )
        repository.upsertScene(
            sampleScene(novel.id, chapter.id).copy(
                id = "draft-generating-scene",
                order = 2,
                text = "Saved draft text",
                status = SceneStatus.GENERATING,
                textSource = SceneTextSource.GENERATED,
            ),
        )
        repository.upsertScene(
            sampleScene(novel.id, chapter.id).copy(
                id = "approved-scene",
                order = 3,
                status = SceneStatus.APPROVED,
            ),
        )

        val changedRows = repository.resetInterruptedScenes()
        val scenesById = repository.getScenes(chapter.id).associateBy { it.id }

        assertEquals(2, changedRows)
        assertEquals(SceneStatus.PENDING, scenesById.getValue("blank-generating-scene").status)
        assertEquals(SceneStatus.PENDING, scenesById.getValue("draft-generating-scene").status)
        assertEquals("Saved draft text", scenesById.getValue("draft-generating-scene").text)
        assertEquals(SceneStatus.APPROVED, scenesById.getValue("approved-scene").status)
    }

    @Test
    fun `revision snapshots and token usage persist through repository boundary`() = runTest {
        val novel = sampleNovel()
        repository.upsertNovel(novel)
        repository.saveRevisionSnapshot(
            novelId = novel.id,
            targetType = "scene",
            targetId = "scene-1",
            label = "before retry",
            contentJson = "{\"text\":\"old\"}",
        )
        repository.saveTokenUsage(
            TokenUsageRecord(
                id = "usage-1",
                novelId = novel.id,
                agentName = "scene",
                provider = "OPENAI",
                model = "gpt-test",
                inputTokens = 10,
                outputTokens = 20,
                estimatedCostUsd = 0.03,
                createdAt = Instant.fromEpochMilliseconds(1_000),
            ),
        )

        val snapshot = repository.observeRevisionSnapshots(novel.id).first().single()
        val usage = repository.observeTokenUsage(novel.id).first().single()

        assertNotNull(snapshot.id)
        assertEquals("before retry", snapshot.label)
        assertEquals("{\"text\":\"old\"}", snapshot.contentJson)
        assertEquals("usage-1", usage.id)
        assertEquals(10, usage.inputTokens)
        assertEquals(20, usage.outputTokens)
        assertEquals(0.03, usage.estimatedCostUsd, 0.0001)
    }

    @Test
    fun `novel bible roundtrip preserves user canon and conflict metadata`() = runTest {
        val novel = sampleNovel().copy(
            bible = NovelBible(
                characters = listOf(
                    CharacterEntry(
                        id = "char-1",
                        name = "Mara",
                        description = "User-authored lead.",
                        currentState = "unhurt",
                        source = BibleEntrySource.USER,
                        sourceChapterId = "chapter-1",
                        sourceSceneId = "scene-1",
                    ),
                ),
                locations = listOf(
                    LocationEntry(
                        id = "loc-1",
                        name = "Archive",
                        description = "A locked public memory vault.",
                        source = BibleEntrySource.USER,
                    ),
                ),
                conflicts = listOf(
                    BibleConflict(
                        id = "conflict-1",
                        section = BibleConflictSection.CHARACTERS,
                        entryId = "char-1",
                        title = "Mara",
                        field = "currentState",
                        existingValue = "unhurt",
                        incomingValue = "wounded",
                        sourceChapterId = "chapter-1",
                        sourceSceneId = "scene-2",
                    ),
                ),
            ),
        )

        repository.upsertNovel(novel)

        val savedBible = repository.getNovel(novel.id)?.bible
        assertEquals(BibleEntrySource.USER, savedBible?.characters?.single()?.source)
        assertEquals("scene-1", savedBible?.characters?.single()?.sourceSceneId)
        assertEquals("Archive", savedBible?.locations?.single()?.name)
        assertEquals("conflict-1", savedBible?.conflicts?.single()?.id)
        assertEquals("wounded", savedBible?.conflicts?.single()?.incomingValue)
    }

    private fun sampleNovel(): Novel {
        return Novel(
            id = "novel-1",
            title = "The Small Door",
            genre = Genre.FANTASY,
            concept = "A locksmith opens a door that remembers every hand that touched it.",
            themes = listOf("memory", "debt"),
            styleGuide = StyleGuide(
                narrativeVoice = NarrativeVoice.FIRST_PERSON,
                tense = NarrativeTense.PRESENT,
                proseStyle = ProseStyle.MINIMALIST,
                targetSceneWordCountMin = 500,
                targetSceneWordCountMax = 900,
                additionalNotes = "Keep sentences clean.",
            ),
            outline = NovelOutline(
                premise = "A debt collector must forgive herself before the city forgets her.",
                majorPlotPoints = listOf(PlotPoint("Inciting", "The first door speaks.", 0.1f)),
                characterArcs = listOf("Mara stops treating memory as currency."),
                thematicStructure = "Memory versus ownership.",
                chapterCount = 1,
                chapterBriefs = listOf(
                    ChapterBrief(
                        chapterIndex = 1,
                        title = "The Door",
                        plotBeats = "Mara finds the speaking lock.",
                        purposeInStory = "Start the bargain.",
                    ),
                ),
            ),
            bible = NovelBible(
                characters = listOf(
                    CharacterEntry(
                        id = "char-1",
                        name = "Mara",
                        description = "A locksmith with a ledger of debts.",
                        source = BibleEntrySource.USER,
                        sourceChapterId = "chapter-1",
                    ),
                ),
            ),
            status = NovelStatus.DRAFTING_CHAPTERS,
            createdAt = Instant.fromEpochMilliseconds(100),
            updatedAt = Instant.fromEpochMilliseconds(200),
        )
    }

    private fun sampleChapter(novelId: String): Chapter {
        return Chapter(
            id = "chapter-1",
            novelId = novelId,
            order = 1,
            title = "The Door",
            synopsis = ChapterSynopsis(
                chapterGoal = "Mara accepts the first impossible job.",
                sceneBreakdowns = listOf(
                    SceneBreakdown(sceneIndex = 1, synopsis = "The lock repeats her name.", targetWordCount = 700),
                ),
                chapterEnding = "The door opens inward.",
                transitionNotes = "Move to the archive.",
            ),
            reviewNotes = "Needs sharper ending.",
            reviewReport = ReviewReport(
                score = 62,
                issues = listOf("Ending is soft"),
                suggestedFixes = listOf("Make the choice irreversible"),
                passed = false,
                retryCount = 1,
                status = ReviewStatus.NEEDS_RETRY,
            ),
            status = ChapterStatus.SYNOPSIS_GENERATED,
        )
    }

    private fun sampleScene(novelId: String, chapterId: String): Scene {
        return Scene(
            id = "scene-1",
            chapterId = chapterId,
            novelId = novelId,
            order = 1,
            synopsis = "The lock repeats Mara's name.",
            text = "Mara hears her name from the brass mouth of the lock.",
            reviewNotes = "Keep this beat.",
            reviewReport = ReviewReport(
                score = 88,
                issues = emptyList(),
                suggestedFixes = listOf("Trim one line"),
                passed = true,
                retryCount = 0,
                status = ReviewStatus.ACCEPTED,
            ),
            status = SceneStatus.REVIEWED,
            wordCount = 11,
            textSource = SceneTextSource.EDITED,
        )
    }
}
