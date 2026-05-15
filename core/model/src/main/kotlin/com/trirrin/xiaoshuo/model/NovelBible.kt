package com.trirrin.xiaoshuo.model

import kotlinx.serialization.Serializable

@Serializable
data class NovelBible(
    val characters: List<CharacterEntry> = emptyList(),
    val locations: List<LocationEntry> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val worldRules: List<WorldRule> = emptyList(),
    val themes: List<ThemeEntry> = emptyList(),
)

@Serializable
data class CharacterEntry(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val personality: String = "",
    val relationships: List<Relationship> = emptyList(),
    val currentState: String = "",
    val firstAppearanceChapterId: String? = null,
    val lastUpdatedSceneId: String? = null,
)

@Serializable
data class Relationship(
    val targetCharacterName: String,
    val relationType: String,
)

@Serializable
data class LocationEntry(
    val name: String,
    val description: String = "",
    val significance: String = "",
)

@Serializable
data class TimelineEvent(
    val description: String,
    val chapterId: String? = null,
    val sceneId: String? = null,
    val chronologicalOrder: Int = 0,
)

@Serializable
data class WorldRule(
    val category: String,
    val rule: String,
    val details: String = "",
)

@Serializable
data class ThemeEntry(
    val name: String,
    val description: String = "",
    val motifSymbols: List<String> = emptyList(),
)
