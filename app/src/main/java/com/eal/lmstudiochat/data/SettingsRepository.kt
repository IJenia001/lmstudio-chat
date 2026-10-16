package com.eal.lmstudiochat.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "lm_settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs -> prefs.toSettings() }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[BASE_URL] = normalizeBaseUrl(settings.baseUrl)
            prefs[API_KEY] = settings.apiKey.trim()
            prefs[USER_NAME] = settings.userName.trim()
            prefs[INSTRUCTIONS] = settings.instructions.trim()
            prefs[MODEL] = settings.model.trim()
            prefs[SHOW_REASONING] = settings.showReasoning
            prefs[MAX_TOKENS] = settings.maxTokens.coerceIn(64, 32768)
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[BASE_URL] = normalizeBaseUrl(next.baseUrl)
            prefs[API_KEY] = next.apiKey.trim()
            prefs[USER_NAME] = next.userName.trim()
            prefs[INSTRUCTIONS] = next.instructions.trim()
            prefs[MODEL] = next.model.trim()
            prefs[SHOW_REASONING] = next.showReasoning
            prefs[MAX_TOKENS] = next.maxTokens.coerceIn(64, 32768)
        }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        baseUrl = this[BASE_URL] ?: AppSettings.DEFAULT_BASE_URL,
        apiKey = this[API_KEY] ?: AppSettings.DEFAULT_API_KEY,
        userName = this[USER_NAME] ?: "",
        instructions = this[INSTRUCTIONS] ?: "",
        model = this[MODEL] ?: "",
        showReasoning = this[SHOW_REASONING] ?: true,
        maxTokens = this[MAX_TOKENS] ?: AppSettings.DEFAULT_MAX_TOKENS,
    )

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val USER_NAME = stringPreferencesKey("user_name")
        val INSTRUCTIONS = stringPreferencesKey("instructions")
        val MODEL = stringPreferencesKey("model")
        val SHOW_REASONING = booleanPreferencesKey("show_reasoning")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
    }
}
