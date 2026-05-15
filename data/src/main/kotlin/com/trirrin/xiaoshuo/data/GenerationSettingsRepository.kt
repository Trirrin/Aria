package com.trirrin.xiaoshuo.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

internal val Context.generationSettingsDataStore by preferencesDataStore(name = "generation_settings")

class GenerationSettingsRepository(
    context: Context,
    private val apiKeyCipher: ApiKeyCipher = AndroidKeyStoreApiKeyCipher(),
) {
    private val dataStore = context.applicationContext.generationSettingsDataStore

    val settings: Flow<GenerationSettings> = dataStore.data.map { preferences ->
        GenerationSettings(
            provider = preferences[Keys.provider] ?: "ANTHROPIC",
            apiKey = preferences[Keys.encryptedApiKey]?.let { apiKeyCipher.decrypt(it) } ?: preferences[Keys.apiKey] ?: "",
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
            if (settings.apiKey.isBlank()) {
                preferences.remove(Keys.encryptedApiKey)
            } else {
                preferences[Keys.encryptedApiKey] = apiKeyCipher.encrypt(settings.apiKey)
            }
            preferences.remove(Keys.apiKey)
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
        val encryptedApiKey = stringPreferencesKey("api_key_encrypted")
        val baseUrl = stringPreferencesKey("base_url")
        val outlineModel = stringPreferencesKey("outline_model")
        val synopsisModel = stringPreferencesKey("synopsis_model")
        val textModel = stringPreferencesKey("text_model")
        val reviewModel = stringPreferencesKey("review_model")
        val continuityModel = stringPreferencesKey("continuity_model")
    }
}

interface ApiKeyCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

private class AndroidKeyStoreApiKeyCipher : ApiKeyCipher {
    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.iv + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        return runCatching {
            val payload = Base64.decode(cipherText, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "xiao_shuo_generation_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
