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
        val explicitNames = (context.characterNames + context.locationNames + NameExtractor.extract(contextText))
            .map { it.normalizedName() }
            .toSet()
        val contextTokens = extractScoredTokens(contextText)

        val directCharacters = bible.characters
            .mapNotNull { character ->
                val score = character.nameScore(explicitNames) + character.textScore(contextTokens)
                if (score <= 0) null else Scored(score, character)
            }
            .sorted()
            .map { it.item }
        val relatedNames = directCharacters
            .flatMap { character -> character.relationships.map { it.targetCharacterName.normalizedName() } }
            .toSet()
        val relatedCharacters = bible.characters.filter { character ->
            character.name.normalizedName() in relatedNames && character !in directCharacters
        }
        val relevantCharacters = (directCharacters + relatedCharacters).distinctBy { it.name.normalizedName() }

        val relevantLocations = bible.locations.scoredBy(
            explicitNames = explicitNames,
            contextTokens = contextTokens,
            nameValues = { listOf(name) },
            textValues = { listOf(name, description, significance) },
        )
        val relevantEvents = bible.timelineEvents.scoredBy(
            explicitNames = emptySet(),
            contextTokens = contextTokens,
            nameValues = { emptyList() },
            textValues = { listOf(description) },
        ).ifEmpty { bible.timelineEvents.takeLast(3) }
        val relevantRules = bible.worldRules.scoredBy(
            explicitNames = emptySet(),
            contextTokens = contextTokens,
            nameValues = { emptyList() },
            textValues = { listOf(category, rule, details) },
        )
        val relevantThemes = bible.themes.scoredBy(
            explicitNames = emptySet(),
            contextTokens = contextTokens,
            nameValues = { listOf(name) },
            textValues = { listOf(name, description, motifSymbols.joinToString(" ")) },
        )

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
            listOf(
                character.name,
                character.aliases.joinToString(" "),
                character.description,
                character.personality,
                character.currentState,
                character.relationships.joinToString(" ") { relationship ->
                    "${relationship.targetCharacterName} ${relationship.relationType}"
                },
            ).joinToString(" ")
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

    private fun extractScoredTokens(text: String): Map<String, Int> {
        return text.split(Regex("[^\\p{L}\\p{N}'-]+"))
            .asSequence()
            .map { it.normalizedName() }
            .filter { it.length >= 4 && it !in stopWords }
            .groupingBy { it }
            .eachCount()
    }

    private fun estimateTokens(text: String): Int {
        return (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)
    }

    private fun CharacterEntry.nameScore(explicitNames: Set<String>): Int {
        val names = (listOf(name) + aliases).map { it.normalizedName() }
        return if (names.any { it in explicitNames }) 100 else 0
    }

    private fun CharacterEntry.textScore(contextTokens: Map<String, Int>): Int {
        return listOf(name, aliases.joinToString(" "), description, personality, currentState)
            .tokenOverlapScore(contextTokens)
    }

    private fun <T> List<T>.scoredBy(
        explicitNames: Set<String>,
        contextTokens: Map<String, Int>,
        nameValues: T.() -> List<String>,
        textValues: T.() -> List<String>,
    ): List<T> {
        return mapNotNull { item ->
            val nameScore = if (item.nameValues().map { it.normalizedName() }.any { it in explicitNames }) 100 else 0
            val textScore = item.textValues().tokenOverlapScore(contextTokens)
            val score = nameScore + textScore
            if (score <= 0) null else Scored(score, item)
        }.sorted().map { it.item }
    }

    private fun List<String>.tokenOverlapScore(contextTokens: Map<String, Int>): Int {
        if (contextTokens.isEmpty()) return 0
        val itemTokens = flatMap { text -> extractScoredTokens(text).keys }.toSet()
        return itemTokens.sumOf { contextTokens[it] ?: 0 }
    }

    private data class Scored<T>(val score: Int, val item: T) : Comparable<Scored<T>> {
        override fun compareTo(other: Scored<T>): Int = other.score.compareTo(score)
    }

    private companion object {
        private val stopWords = setOf(
            "about", "after", "again", "against", "along", "also", "away", "back", "been", "before",
            "being", "between", "both", "chapter", "could", "down", "each", "from", "have", "into",
            "just", "like", "more", "most", "only", "over", "scene", "should", "some", "than",
            "that", "their", "them", "then", "there", "they", "this", "through", "under", "until",
            "when", "where", "which", "while", "with", "would",
        )
    }
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
