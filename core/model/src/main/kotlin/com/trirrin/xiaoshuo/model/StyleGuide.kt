package com.trirrin.xiaoshuo.model

import kotlinx.serialization.Serializable

@Serializable
data class StyleGuide(
    val narrativeVoice: NarrativeVoice = NarrativeVoice.THIRD_PERSON_LIMITED,
    val tense: NarrativeTense = NarrativeTense.PAST,
    val proseStyle: ProseStyle = ProseStyle.LITERARY,
    val targetSceneWordCountMin: Int = 2000,
    val targetSceneWordCountMax: Int = 3000,
    val additionalNotes: String = "",
)

enum class NarrativeVoice { FIRST_PERSON, THIRD_PERSON_LIMITED, THIRD_PERSON_OMNISCIENT, SECOND_PERSON }
enum class NarrativeTense { PAST, PRESENT }
enum class ProseStyle { MINIMALIST, LITERARY, PURPLE, CONVERSATIONAL }
