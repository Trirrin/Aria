package com.trirrin.xiaoshuo.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class NovelBible(
    val characters: List<CharacterEntry> = emptyList(),
    val locations: List<LocationEntry> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val worldRules: List<WorldRule> = emptyList(),
    val themes: List<ThemeEntry> = emptyList(),
    val conflicts: List<BibleConflict> = emptyList(),
)

@Serializable
enum class BibleEntrySource {
    EXTRACTED,
    USER,
}

@Serializable
enum class BibleConflictSection {
    CHARACTERS,
    LOCATIONS,
    TIMELINE,
    WORLD_RULES,
    THEMES,
}

@Serializable
data class BibleConflict(
    val id: String = UUID.randomUUID().toString(),
    val section: BibleConflictSection,
    val entryId: String,
    val title: String,
    val field: String,
    val existingValue: String,
    val incomingValue: String,
    val sourceChapterId: String? = null,
    val sourceSceneId: String? = null,
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
    val id: String = UUID.randomUUID().toString(),
    val source: BibleEntrySource = BibleEntrySource.EXTRACTED,
    val sourceChapterId: String? = firstAppearanceChapterId,
    val sourceSceneId: String? = lastUpdatedSceneId,
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
    val id: String = UUID.randomUUID().toString(),
    val source: BibleEntrySource = BibleEntrySource.EXTRACTED,
    val sourceChapterId: String? = null,
    val sourceSceneId: String? = null,
)

@Serializable
data class TimelineEvent(
    val description: String,
    val chapterId: String? = null,
    val sceneId: String? = null,
    val chronologicalOrder: Int = 0,
    val id: String = UUID.randomUUID().toString(),
    val source: BibleEntrySource = BibleEntrySource.EXTRACTED,
)

@Serializable
data class WorldRule(
    val category: String,
    val rule: String,
    val details: String = "",
    val id: String = UUID.randomUUID().toString(),
    val source: BibleEntrySource = BibleEntrySource.EXTRACTED,
    val sourceChapterId: String? = null,
    val sourceSceneId: String? = null,
)

@Serializable
data class ThemeEntry(
    val name: String,
    val description: String = "",
    val motifSymbols: List<String> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
    val source: BibleEntrySource = BibleEntrySource.EXTRACTED,
    val sourceChapterId: String? = null,
    val sourceSceneId: String? = null,
)