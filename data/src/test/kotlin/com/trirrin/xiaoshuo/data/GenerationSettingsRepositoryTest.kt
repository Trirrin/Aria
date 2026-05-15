package com.trirrin.xiaoshuo.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GenerationSettingsRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking {
            context.generationSettingsDataStore.edit { it.clear() }
        }
    }

    @Test
    fun `save stores encrypted api key and reads decrypted setting`() = runTest {
        val cipher = FakeApiKeyCipher()
        val repository = GenerationSettingsRepository(context, cipher)

        repository.save(GenerationSettings(apiKey = "secret-key", baseUrl = "https://example.test"))

        val settings = repository.settings.first()
        val rawPreferences = context.generationSettingsDataStore.data.first()
        assertEquals("secret-key", settings.apiKey)
        assertEquals("https://example.test", settings.baseUrl)
        assertEquals("encrypted:secret-key", rawPreferences[stringPreferencesKey("api_key_encrypted")])
        assertEquals(null, rawPreferences[stringPreferencesKey("api_key")])
    }

    @Test
    fun `settings reads legacy plaintext api key before migration`() = runTest {
        val repository = GenerationSettingsRepository(context, FakeApiKeyCipher())
        context.generationSettingsDataStore.edit { preferences ->
            preferences[stringPreferencesKey("api_key")] = "legacy-key"
        }

        val settings = repository.settings.first()

        assertEquals("legacy-key", settings.apiKey)
    }

    private class FakeApiKeyCipher : ApiKeyCipher {
        override fun encrypt(plainText: String): String = "encrypted:$plainText"

        override fun decrypt(cipherText: String): String = cipherText.removePrefix("encrypted:")
    }
}
