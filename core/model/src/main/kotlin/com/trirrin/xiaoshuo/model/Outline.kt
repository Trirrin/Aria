package com.trirrin.xiaoshuo.model

import kotlinx.serialization.Serializable

@Serializable
data class NovelOutline(
    val premise: String,
    val majorPlotPoints: List<PlotPoint>,
    val characterArcs: List<String> = emptyList(),
    val thematicStructure: String = "",
    val chapterCount: Int,
    val chapterBriefs: List<ChapterBrief>,
)

@Serializable
data class PlotPoint(
    val name: String,
    val description: String,
    val position: Float,
)

@Serializable
data class ChapterBrief(
    val chapterIndex: Int,
    val title: String,
    val plotBeats: String,
    val purposeInStory: String,
)

@Serializable
data class ChapterSynopsis(
    val chapterGoal: String,
    val sceneBreakdowns: List<SceneBreakdown>,
    val chapterEnding: String,
    val transitionNotes: String = "",
)

@Serializable
data class SceneBreakdown(
    val sceneIndex: Int,
    val synopsis: String,
    val targetWordCount: Int = 2500,
)
