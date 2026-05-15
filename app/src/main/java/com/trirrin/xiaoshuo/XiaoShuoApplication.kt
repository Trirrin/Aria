package com.trirrin.xiaoshuo

import android.app.Application
import com.trirrin.xiaoshuo.agent.AgentPipeline
import com.trirrin.xiaoshuo.agent.BibleFilter
import com.trirrin.xiaoshuo.agent.BibleMerger
import com.trirrin.xiaoshuo.agent.ChapterSynopsisAgent
import com.trirrin.xiaoshuo.agent.ContinuityAgent
import com.trirrin.xiaoshuo.agent.PipelineConfig
import com.trirrin.xiaoshuo.agent.OutlineAgent
import com.trirrin.xiaoshuo.agent.ReviewAgent
import com.trirrin.xiaoshuo.agent.SceneExpansionAgent
import com.trirrin.xiaoshuo.data.GenerationSettings
import com.trirrin.xiaoshuo.data.GenerationSettingsRepository
import com.trirrin.xiaoshuo.data.NovelRepository
import com.trirrin.xiaoshuo.data.XiaoShuoDatabase
import com.trirrin.xiaoshuo.llm.LlmClientConfig
import com.trirrin.xiaoshuo.llm.LlmClientFactory
import com.trirrin.xiaoshuo.llm.LlmProvider

class XiaoShuoApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val database = XiaoShuoDatabase.create(application)

    val novelRepository = NovelRepository(database.novelDao())
    val settingsRepository = GenerationSettingsRepository(application)

    fun createPipeline(settings: GenerationSettings): AgentPipeline {
        val provider = runCatching { LlmProvider.valueOf(settings.provider) }.getOrDefault(LlmProvider.ANTHROPIC)
        val llmClient = LlmClientFactory().create(
            LlmClientConfig(
                provider = provider,
                apiKey = settings.apiKey,
                baseUrl = settings.baseUrl.ifBlank { null },
                defaultModel = settings.textModel,
            ),
        )
        val bibleFilter = BibleFilter()
        return AgentPipeline(
            outlineAgent = OutlineAgent(llmClient, settings.outlineModel),
            chapterSynopsisAgent = ChapterSynopsisAgent(llmClient, settings.synopsisModel, bibleFilter),
            sceneExpansionAgent = SceneExpansionAgent(llmClient, settings.textModel, bibleFilter),
            reviewAgent = ReviewAgent(llmClient, settings.reviewModel),
            continuityAgent = ContinuityAgent(llmClient, settings.continuityModel),
            bibleMerger = BibleMerger(),
            config = PipelineConfig(
                outlineModel = settings.outlineModel,
                synopsisModel = settings.synopsisModel,
                textModel = settings.textModel,
                reviewModel = settings.reviewModel,
                continuityModel = settings.continuityModel,
            ),
        )
    }
}
