package com.trirrin.xiaoshuo.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class GenerationSettings(
    val provider: String = "ANTHROPIC",
    val apiKey: String = "",
    val baseUrl: String = "",
    val outlineModel: String = "claude-sonnet-4-20250514",
    val synopsisModel: String = "claude-sonnet-4-20250514",
    val textModel: String = "claude-sonnet-4-20250514",
    val reviewModel: String = "claude-haiku-4-20250414",
    val continuityModel: String = "claude-haiku-4-20250414",
)

private val Context.generationSettingsDataStore by preferencesDataStore(name = "generation_settings")

class GenerationSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.generationSettingsDataStore

    val settings: Flow<GenerationSettings> = dataStore.data.map { preferences ->
        GenerationSettings(
            provider = preferences[Keys.provider] ?: "ANTHROPIC",
            apiKey = preferences[Keys.apiKey] ?: "",
            baseUrl = preferences[Keys.baseUrl] ?: "",
            outlineModel = preferences[Keys.outlineModel] ?: "claude-sonnet-4-20250514",
            synopsisModel = preferences[Keys.synopsisModel] ?: "claude-sonnet-4-20250514",
            textModel = preferences[Keys.textModel] ?: "claude-sonnet-4-20250514",
            reviewModel = preferences[Keys.reviewModel] ?: "claude-haiku-4-20250414",
            continuityModel = preferences[Keys.continuityModel] ?: "claude-haiku-4-20250414",
        )
    }

    suspend fun save(settings: GenerationSettings) {
        dataStore.edit { preferences ->
            preferences[Keys.provider] = settings.provider
            preferences[Keys.apiKey] = settings.apiKey
            preferences[Keys.baseUrl] = settings.baseUrl
            preferences[Keys.outlineModel] = settings.outlineModel
            preferences[Keys.synopsisModel] = settings.synopsisModel
            preferences[Keys.textModel] = settings.textModel
            preferences[Keys.reviewModel] = settings.reviewModel
            preferences[Keys.continuityModel] = settings.continuityModel
        }
    }

    private object Keys {
        val provider = stringPreferencesKey("provider")
        val apiKey = stringPreferencesKey("api_key")
        val baseUrl = stringPreferencesKey("base_url")
        val outlineModel = stringPreferencesKey("outline_model")
        val synopsisModel = stringPreferencesKey("synopsis_model")
        val textModel = stringPreferencesKey("text_model")
        val reviewModel = stringPreferencesKey("review_model")
        val continuityModel = stringPreferencesKey("continuity_model")
    }
}
