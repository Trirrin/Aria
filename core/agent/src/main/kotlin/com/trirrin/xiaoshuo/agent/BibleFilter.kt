package com.trirrin.xiaoshuo.agent

import com.trirrin.xiaoshuo.model.BibleConflict
import com.trirrin.xiaoshuo.model.BibleConflictSection
import com.trirrin.xiaoshuo.model.BibleEntrySource
import com.trirrin.xiaoshuo.model.CharacterEntry
import com.trirrin.xiaoshuo.model.LocationEntry
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.model.Relationship
import com.trirrin.xiaoshuo.model.ThemeEntry
import com.trirrin.xiaoshuo.model.TimelineEvent
import com.trirrin.xiaoshuo.model.WorldRule
import com.trirrin.xiaoshuo.prompt.BibleDiff

private const val CHARS_PER_TOKEN = 4

data class BibleFilterContext(
    val chapterSynopsis: String? = null,
    val sceneSynopsis: String? = null,
    val characterNames: List<String> = emptyList(),
    val locationNames: List<String> = emptyList(),
)

class BibleFilter(private val tokenBudget: Int = 2000) {

    fun filterRelevant(bible: NovelBible, context: BibleFilterContext): NovelBible {
        if (bible.isEmpty()) return bible

        val contextText = listOfNotNull(context.chapterSynopsis, context.sceneSynopsis).joinToString(" ")
        val explicitNames = (context.characterNames + context.locationNames + extractNames(context))
            .map { it.normalized() }
            .toSet()
        val keywords = extractKeywords(contextText)

        val directCharacters = bible.characters.filter { character ->
            character.name.normalized() in explicitNames ||
                character.aliases.any { it.normalized() in explicitNames } ||
                character.matchesAny(keywords)
        }
        val relatedNames = directCharacters
            .flatMap { character -> character.relationships.map { it.targetCharacterName.normalized() } }
            .toSet()
        val relatedCharacters = bible.characters.filter { character ->
            character.name.normalized() in relatedNames && character !in directCharacters
        }
        val relevantCharacters = (directCharacters + relatedCharacters).distinctBy { it.name.normalized() }

        val relevantLocations = bible.locations.filter { location ->
            location.name.normalized() in explicitNames || location.matchesAny(keywords)
        }
        val relevantEvents = bible.timelineEvents.filter { it.matchesAny(keywords) }
            .ifEmpty { bible.timelineEvents.takeLast(5) }
        val relevantRules = bible.worldRules.filter { it.matchesAny(keywords) }
            .ifEmpty { bible.worldRules }
        val relevantThemes = bible.themes.filter { it.matchesAny(keywords) }
            .ifEmpty { bible.themes }

        return trimToBudget(
            characters = relevantCharacters,
            locations = relevantLocations,
            events = relevantEvents,
            rules = relevantRules,
            themes = relevantThemes,
        )
    }

    private fun NovelBible.isEmpty(): Boolean {
        return characters.isEmpty() && locations.isEmpty() && timelineEvents.isEmpty() &&
            worldRules.isEmpty() && themes.isEmpty()
    }

    private fun trimToBudget(
        characters: List<CharacterEntry>,
        locations: List<LocationEntry>,
        events: List<TimelineEvent>,
        rules: List<WorldRule>,
        themes: List<ThemeEntry>,
    ): NovelBible {
        var remaining = tokenBudget.coerceAtLeast(0)

        fun <T> takeWhileBudget(items: List<T>, render: (T) -> String): List<T> {
            val kept = mutableListOf<T>()
            for (item in items) {
                val cost = estimateTokens(render(item))
                if (cost > remaining) continue
                kept.add(item)
                remaining -= cost
            }
            return kept
        }

        val keptCharacters = takeWhileBudget(characters) { character ->
            listOf(character.name, character.description, character.personality, character.currentState)
                .joinToString(" ")
        }
        val keptLocations = takeWhileBudget(locations) { location ->
            listOf(location.name, location.description, location.significance).joinToString(" ")
        }
        val keptEvents = takeWhileBudget(events) { it.description }
        val keptRules = takeWhileBudget(rules) { rule ->
            listOf(rule.category, rule.rule, rule.details).joinToString(" ")
        }
        val keptThemes = takeWhileBudget(themes) { theme ->
            listOf(theme.name, theme.description, theme.motifSymbols.joinToString(" ")).joinToString(" ")
        }

        return NovelBible(
            characters = keptCharacters,
            locations = keptLocations,
            timelineEvents = keptEvents,
            worldRules = keptRules,
            themes = keptThemes,
        )
    }

    private fun extractNames(context: BibleFilterContext): Set<String> {
        val text = listOfNotNull(context.chapterSynopsis, context.sceneSynopsis).joinToString(" ")
        if (text.isBlank()) return emptySet()

        return text.split(Regex("[\\s.,!?;:\"'()\\[\\]{}\\-—–]+"))
            .filter { it.length > 2 && it.first().isUpperCase() }
            .toSet()
    }

    private fun extractKeywords(text: String): Set<String> {
        return text.split(Regex("[\\s.,!?;:\"'()\\[\\]{}\\-—–]+"))
            .map { it.normalized() }
            .filter { it.length > 2 }
            .toSet()
    }

    private fun estimateTokens(text: String): Int {
        return (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)
    }

    private fun CharacterEntry.matchesAny(keywords: Set<String>): Boolean {
        if (keywords.isEmpty()) return false
        return listOf(name, description, personality, currentState).any { it.containsAnyKeyword(keywords) } ||
            aliases.any { it.normalized() in keywords }
    }

    private fun LocationEntry.matchesAny(keywords: Set<String>): Boolean {
        if (keywords.isEmpty()) return false
        return listOf(name, description, significance).any { it.containsAnyKeyword(keywords) }
    }

    private fun TimelineEvent.matchesAny(keywords: Set<String>): Boolean {
        if (keywords.isEmpty()) return false
        return description.containsAnyKeyword(keywords)
    }

    private fun WorldRule.matchesAny(keywords: Set<String>): Boolean {
        if (keywords.isEmpty()) return false
        return listOf(category, rule, details).any { it.containsAnyKeyword(keywords) }
    }

    private fun ThemeEntry.matchesAny(keywords: Set<String>): Boolean {
        if (keywords.isEmpty()) return false
        return listOf(name, description, motifSymbols.joinToString(" ")).any { it.containsAnyKeyword(keywords) }
    }

    private fun String.containsAnyKeyword(keywords: Set<String>): Boolean {
        val normalized = normalized()
        return keywords.any { it in normalized }
    }

    private fun String.normalized(): String = trim().lowercase()
}

class BibleMerger {
    fun merge(existing: NovelBible, diff: BibleDiff, chapterId: String, sceneId: String): NovelBible {
        val updatedCharacters = existing.characters.toMutableList()
        val conflicts = existing.conflicts.toMutableList()

        for (toAdd in diff.charactersToAdd) {
            val existingIdx = updatedCharacters.indexOfFirst { it.name.equals(toAdd.name, ignoreCase = true) }
            if (existingIdx >= 0) {
                val existingChar = updatedCharacters[existingIdx]
                if (existingChar.source == BibleEntrySource.USER) {
                    conflicts.addAll(
                        characterConflicts(
                            existing = existingChar,
                            incomingDescription = toAdd.description,
                            incomingPersonality = toAdd.personality,
                            incomingState = toAdd.currentState,
                            chapterId = chapterId,
                            sceneId = sceneId,
                        ),
                    )
                } else {
                    updatedCharacters[existingIdx] = existingChar.copy(
                        description = toAdd.description.ifBlank { existingChar.description },
                        personality = toAdd.personality.ifBlank { existingChar.personality },
                        currentState = toAdd.currentState.ifBlank { existingChar.currentState },
                        lastUpdatedSceneId = sceneId,
                        sourceSceneId = sceneId,
                    )
                }
            } else {
                updatedCharacters.add(
                    CharacterEntry(
                        name = toAdd.name,
                        description = toAdd.description,
                        personality = toAdd.personality,
                        relationships = toAdd.relationships.map { Relationship(it.targetCharacterName, it.relationType) },
                        currentState = toAdd.currentState,
                        firstAppearanceChapterId = chapterId,
                        lastUpdatedSceneId = sceneId,
                        sourceChapterId = chapterId,
                        sourceSceneId = sceneId,
                    ),
                )
            }
        }

        for (toUpdate in diff.charactersToUpdate) {
            val idx = updatedCharacters.indexOfFirst { it.name.equals(toUpdate.name, ignoreCase = true) }
            if (idx >= 0) {
                val existingChar = updatedCharacters[idx]
                if (existingChar.source == BibleEntrySource.USER) {
                    conflicts.add(
                        BibleConflict(
                            section = BibleConflictSection.CHARACTERS,
                            entryId = existingChar.id,
                            title = existingChar.name,
                            field = "currentState",
                            existingValue = existingChar.currentState,
                            incomingValue = toUpdate.updatedState,
                            sourceChapterId = chapterId,
                            sourceSceneId = sceneId,
                        ),
                    )
                } else {
                    updatedCharacters[idx] = existingChar.copy(
                        currentState = toUpdate.updatedState,
                        lastUpdatedSceneId = sceneId,
                        sourceSceneId = sceneId,
                    )
                }
            }
        }

        val existingLocationNames = existing.locations.map { it.name.lowercase() }.toSet()
        diff.locationsToAdd.forEach { incoming ->
            val location = existing.locations.firstOrNull { it.name.equals(incoming.name, ignoreCase = true) }
            if (location?.source == BibleEntrySource.USER) {
                conflicts.addAll(
                    locationConflicts(
                        existing = location,
                        incomingDescription = incoming.description,
                        incomingSignificance = incoming.significance,
                        chapterId = chapterId,
                        sceneId = sceneId,
                    ),
                )
            }
        }
        val newLocations = diff.locationsToAdd
            .filter { it.name.lowercase() !in existingLocationNames }
            .map {
                LocationEntry(
                    name = it.name,
                    description = it.description,
                    significance = it.significance,
                    sourceChapterId = chapterId,
                    sourceSceneId = sceneId,
                )
            }

        val newEvents = diff.timelineEventsToAdd.mapIndexed { index, event ->
            TimelineEvent(event.description, chapterId, sceneId, existing.timelineEvents.size + index)
        }

        val existingRuleKeys = existing.worldRules.map { "${it.category}|${it.rule}".lowercase() }.toSet()
        val newRules = diff.worldRulesToAdd
            .filter { "${it.category}|${it.rule}".lowercase() !in existingRuleKeys }
            .map {
                WorldRule(
                    category = it.category,
                    rule = it.rule,
                    details = it.details,
                    sourceChapterId = chapterId,
                    sourceSceneId = sceneId,
                )
            }

        return existing.copy(
            characters = updatedCharacters,
            locations = existing.locations + newLocations,
            timelineEvents = existing.timelineEvents + newEvents,
            worldRules = existing.worldRules + newRules,
            conflicts = conflicts.distinctBy { listOf(it.section.name, it.entryId, it.field, it.incomingValue).joinToString("|") },
        )
    }

    private fun characterConflicts(
        existing: CharacterEntry,
        incomingDescription: String,
        incomingPersonality: String,
        incomingState: String,
        chapterId: String,
        sceneId: String,
    ): List<BibleConflict> {
        return listOfNotNull(
            conflictIfDifferent(existing.id, existing.name, "description", existing.description, incomingDescription, chapterId, sceneId, BibleConflictSection.CHARACTERS),
            conflictIfDifferent(existing.id, existing.name, "personality", existing.personality, incomingPersonality, chapterId, sceneId, BibleConflictSection.CHARACTERS),
            conflictIfDifferent(existing.id, existing.name, "currentState", existing.currentState, incomingState, chapterId, sceneId, BibleConflictSection.CHARACTERS),
        )
    }

    private fun locationConflicts(
        existing: LocationEntry,
        incomingDescription: String,
        incomingSignificance: String,
        chapterId: String,
        sceneId: String,
    ): List<BibleConflict> {
        return listOfNotNull(
            conflictIfDifferent(existing.id, existing.name, "description", existing.description, incomingDescription, chapterId, sceneId, BibleConflictSection.LOCATIONS),
            conflictIfDifferent(existing.id, existing.name, "significance", existing.significance, incomingSignificance, chapterId, sceneId, BibleConflictSection.LOCATIONS),
        )
    }

    private fun conflictIfDifferent(
        entryId: String,
        title: String,
        field: String,
        existingValue: String,
        incomingValue: String,
        chapterId: String,
        sceneId: String,
        section: BibleConflictSection,
    ): BibleConflict? {
        if (incomingValue.isBlank() || incomingValue == existingValue) return null
        return BibleConflict(
            section = section,
            entryId = entryId,
            title = title,
            field = field,
            existingValue = existingValue,
            incomingValue = incomingValue,
            sourceChapterId = chapterId,
            sourceSceneId = sceneId,
        )
    }
}
