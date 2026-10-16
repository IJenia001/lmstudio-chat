package com.eal.lmstudiochat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.eal.lmstudiochat.LmApplication
import com.eal.lmstudiochat.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LmApplication

    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    init {
        viewModelScope.launch {
            _settings.value = app.settingsRepository.settings.first()
        }
    }

    fun save(draft: AppSettings) {
        viewModelScope.launch {
            app.settingsRepository.save(draft)
            _settings.value = app.settingsRepository.settings.first()
            _notice.value = "Сохранено"
        }
    }

    fun test(draft: AppSettings) {
        viewModelScope.launch {
            _testing.value = true
            _notice.value = null
            app.settingsRepository.save(draft)
            runCatching {
                withContext(Dispatchers.IO) {
                    app.client.listModels(draft.baseUrl, draft.apiKey)
                }
            }.onSuccess { models ->
                val loaded = models.count { it.loaded }
                val vision = models.count { it.vision }
                _notice.value = "Связь есть: ${models.size} моделей, загружено $loaded, со зрением $vision"
                _settings.value = app.settingsRepository.settings.first()
            }.onFailure { error ->
                _notice.value = error.message ?: "Нет связи с сервером"
            }
            _testing.value = false
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T {
                    @Suppress("UNCHECKED_CAST")
                    return SettingsViewModel(application) as T
                }
            }
    }
}
