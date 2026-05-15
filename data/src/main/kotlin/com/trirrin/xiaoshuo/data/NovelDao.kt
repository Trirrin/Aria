package com.trirrin.xiaoshuo.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelDao {
    @Query("SELECT * FROM novels ORDER BY updatedAtEpochMillis DESC")
    fun observeNovels(): Flow<List<NovelEntity>>

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun getNovel(id: String): NovelEntity?

    @Upsert
    suspend fun upsertNovel(novel: NovelEntity)

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun deleteNovel(id: String)

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY `order` ASC")
    fun observeChapters(novelId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY `order` ASC")
    suspend fun getChapters(novelId: String): List<ChapterEntity>

    @Upsert
    suspend fun upsertChapter(chapter: ChapterEntity)

    @Query("SELECT * FROM scenes WHERE chapterId = :chapterId ORDER BY `order` ASC")
    fun observeScenes(chapterId: String): Flow<List<SceneEntity>>

    @Query("SELECT * FROM scenes WHERE chapterId = :chapterId ORDER BY `order` ASC")
    suspend fun getScenes(chapterId: String): List<SceneEntity>

    @Upsert
    suspend fun upsertScene(scene: SceneEntity)

    @Transaction
    suspend fun upsertNovelGraph(novel: NovelEntity, chapters: List<ChapterEntity>, scenes: List<SceneEntity>) {
        upsertNovel(novel)
        chapters.forEach { upsertChapter(it) }
        scenes.forEach { upsertScene(it) }
    }
}
