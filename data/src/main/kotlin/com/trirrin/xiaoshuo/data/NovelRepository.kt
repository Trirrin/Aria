package com.trirrin.xiaoshuo.data

import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.ChapterStatus
import com.trirrin.xiaoshuo.model.ChapterSynopsis
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.model.NovelOutline
import com.trirrin.xiaoshuo.model.NovelStatus
import com.trirrin.xiaoshuo.model.ReviewReport
import com.trirrin.xiaoshuo.model.Scene
import com.trirrin.xiaoshuo.model.SceneStatus
import com.trirrin.xiaoshuo.model.StyleGuide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NovelRepository(
    private val dao: NovelDao,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun observeNovels(): Flow<List<Novel>> {
        return dao.observeNovels().map { novels -> novels.map { it.toModel(json) } }
    }

    suspend fun getNovel(id: String): Novel? {
        return dao.getNovel(id)?.toModel(json)
    }

    suspend fun upsertNovel(novel: Novel) {
        dao.upsertNovel(novel.toEntity(json))
    }

    suspend fun deleteNovel(id: String) {
        dao.deleteNovel(id)
    }

    fun observeChapters(novelId: String): Flow<List<Chapter>> {
        return dao.observeChapters(novelId).map { chapters -> chapters.map { it.toModel(json) } }
    }

    suspend fun getChapters(novelId: String): List<Chapter> {
        return dao.getChapters(novelId).map { it.toModel(json) }
    }

    suspend fun upsertChapter(chapter: Chapter) {
        dao.upsertChapter(chapter.toEntity(json))
    }

    fun observeScenes(chapterId: String): Flow<List<Scene>> {
        return dao.observeScenes(chapterId).map { scenes -> scenes.map { it.toModel(json) } }
    }

    suspend fun getScenes(chapterId: String): List<Scene> {
        return dao.getScenes(chapterId).map { it.toModel(json) }
    }

    suspend fun upsertScene(scene: Scene) {
        dao.upsertScene(scene.toEntity(json))
    }
}

private fun Novel.toEntity(json: Json): NovelEntity {
    return NovelEntity(
        id = id,
        title = title,
        genre = genre.name,
        concept = concept,
        themesJson = json.encodeToString(themes),
        styleGuideJson = json.encodeToString(styleGuide),
        outlineJson = outline?.let { json.encodeToString(it) },
        bibleJson = json.encodeToString(bible),
        status = status.name,
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
        updatedAtEpochMillis = updatedAt.toEpochMilliseconds(),
    )
}

private fun NovelEntity.toModel(json: Json): Novel {
    return Novel(
        id = id,
        title = title,
        genre = enumValueOf<Genre>(genre),
        concept = concept,
        themes = json.decodeFromString<List<String>>(themesJson),
        styleGuide = json.decodeFromString<StyleGuide>(styleGuideJson),
        outline = outlineJson?.let { json.decodeFromString<NovelOutline>(it) },
        bible = json.decodeFromString<NovelBible>(bibleJson),
        status = enumValueOf<NovelStatus>(status),
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMillis),
        updatedAt = Instant.fromEpochMilliseconds(updatedAtEpochMillis),
    )
}

private fun Chapter.toEntity(json: Json): ChapterEntity {
    return ChapterEntity(
        id = id,
        novelId = novelId,
        order = order,
        title = title,
        synopsisJson = synopsis?.let { json.encodeToString(it) },
        reviewNotes = reviewNotes,
        reviewReportJson = reviewReport?.let { json.encodeToString(it) },
        status = status.name,
    )
}

private fun ChapterEntity.toModel(json: Json): Chapter {
    return Chapter(
        id = id,
        novelId = novelId,
        order = order,
        title = title,
        synopsis = synopsisJson?.let { json.decodeFromString<ChapterSynopsis>(it) },
        reviewNotes = reviewNotes,
        reviewReport = reviewReportJson?.let { json.decodeFromString<ReviewReport>(it) },
        status = enumValueOf<ChapterStatus>(status),
    )
}

private fun Scene.toEntity(json: Json): SceneEntity {
    return SceneEntity(
        id = id,
        chapterId = chapterId,
        novelId = novelId,
        order = order,
        synopsis = synopsis,
        text = text,
        reviewNotes = reviewNotes,
        reviewReportJson = reviewReport?.let { json.encodeToString(it) },
        status = status.name,
        wordCount = wordCount,
    )
}

private fun SceneEntity.toModel(json: Json): Scene {
    return Scene(
        id = id,
        chapterId = chapterId,
        novelId = novelId,
        order = order,
        synopsis = synopsis,
        text = text,
        reviewNotes = reviewNotes,
        reviewReport = reviewReportJson?.let { json.decodeFromString<ReviewReport>(it) },
        status = enumValueOf<SceneStatus>(status),
        wordCount = wordCount,
    )
}
