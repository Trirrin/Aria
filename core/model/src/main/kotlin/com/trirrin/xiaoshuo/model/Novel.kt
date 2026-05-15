package com.trirrin.xiaoshuo.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Novel(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val genre: Genre,
    val concept: String,
    val themes: List<String> = emptyList(),
    val styleGuide: StyleGuide = StyleGuide(),
    val outline: NovelOutline? = null,
    val bible: NovelBible = NovelBible(),
    val status: NovelStatus = NovelStatus.DRAFTING_OUTLINE,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
)

enum class NovelStatus {
    DRAFTING_OUTLINE,
    OUTLINE_COMPLETE,
    DRAFTING_CHAPTERS,
    COMPLETE,
}

enum class Genre(val label: String) {
    FANTASY("Fantasy"),
    SCI_FI("Science Fiction"),
    ROMANCE("Romance"),
    MYSTERY("Mystery"),
    THRILLER("Thriller"),
    LITERARY("Literary Fiction"),
    HISTORICAL("Historical Fiction"),
    HORROR("Horror"),
    OTHER("Other"),
}
