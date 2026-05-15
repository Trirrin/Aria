package com.trirrin.xiaoshuo.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "novels")
data class NovelEntity(
    @PrimaryKey val id: String,
    val title: String,
    val genre: String,
    val concept: String,
    val themesJson: String,
    val styleGuideJson: String,
    val outlineJson: String?,
    val bibleJson: String,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("novelId"), Index(value = ["novelId", "order"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val novelId: String,
    val order: Int,
    val title: String,
    val synopsisJson: String?,
    val reviewNotes: String?,
    val status: String,
)

@Entity(
    tableName = "scenes",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("novelId"), Index("chapterId"), Index(value = ["chapterId", "order"], unique = true)],
)
data class SceneEntity(
    @PrimaryKey val id: String,
    val chapterId: String,
    val novelId: String,
    val order: Int,
    val synopsis: String,
    val text: String,
    val reviewNotes: String?,
    val status: String,
    val wordCount: Int,
)
