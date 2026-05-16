package com.trirrin.xiaoshuo.model

import kotlinx.serialization.Serializable

@Serializable
data class NovelBackgroundProposal(
    val titleOptions: List<String> = emptyList(),
    val genre: Genre = Genre.OTHER,
    val tone: String = "",
    val premise: String = "",
    val worldSetup: String = "",
    val protagonistSeed: String = "",
    val coreCastSeeds: List<String> = emptyList(),
    val majorConflict: String = "",
    val themes: List<String> = emptyList(),
    val styleGuide: StyleGuide = StyleGuide(),
    val initialBibleCandidates: NovelBible = NovelBible(),
)
