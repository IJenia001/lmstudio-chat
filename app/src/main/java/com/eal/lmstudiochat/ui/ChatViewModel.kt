package com.eal.lmstudiochat.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.eal.lmstudiochat.LmApplication
import com.eal.lmstudiochat.data.AppSettings
import com.eal.lmstudiochat.data.ChatMessage
import com.eal.lmstudiochat.data.RemoteModel
import com.eal.lmstudiochat.data.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LmApplication
    private val settingsRepository = app.settingsRepository
    private val chatStore = app.chatStore
    private val client = app.client

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _models = MutableStateFlow<List<RemoteModel>>(emptyList())
    val models: StateFlow<List<RemoteModel>> = _models

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private var generation: Job? = null

    init {
        viewModelScope.launch {
            _messages.value = withContext(Dispatchers.IO) { chatStore.load() }
            refreshModels()
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            val snapshot = settingsRepository.settings.first()
            runCatching {
                withContext(Dispatchers.IO) { client.listModels(snapshot.baseUrl, snapshot.apiKey) }
            }.onSuccess { list ->
                _models.value = list
                _status.value = null
                if (snapshot.model.isBlank() && list.isNotEmpty()) {
                    val preferred = list.firstOrNull { it.loaded } ?: list.first()
                    settingsRepository.update { it.copy(model = preferred.id) }
                }
            }.onFailure { error ->
                _status.value = error.message ?: "Не удалось получить список моделей"
            }
        }
    }

    fun selectModel(id: String) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(model = id) }
        }
    }

    fun send(text: String, imageUri: Uri?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imageUri == null) return
        if (generation?.isActive == true) return

        generation = viewModelScope.launch {
            _busy.value = true
            _status.value = null
            val settings = settingsRepository.settings.first()
            val selected = _models.value.firstOrNull { it.id == settings.model }
            if (imageUri != null && selected != null && !selected.vision) {
                _status.value = "У этой модели нет зрения — картинка может быть проигнорирована"
            }
            val imagePath = try {
                if (imageUri == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) { chatStore.importImage(imageUri) }
                }
            } catch (error: Exception) {
                _busy.value = false
                _status.value = error.message ?: "Не удалось прочитать изображение"
                return@launch
            }

            val userMessage = ChatMessage(
                role = Role.User,
                text = trimmed,
                imagePath = imagePath,
            )
            val assistant = ChatMessage(role = Role.Assistant, text = "")
            val history = _messages.value + userMessage
            _messages.value = history + assistant
            persist()

            try {
                client.streamChat(
                    settings = settings,
                    history = history,
                    imageDataUrl = chatStore::imageDataUrl,
                ) { piece ->
                    withContext(Dispatchers.Main) {
                        replace(assistant.id) {
                            it.copy(
                                text = it.text + piece.content,
                                reasoning = it.reasoning + piece.reasoning,
                                truncated = it.truncated || piece.finishReason == "length",
                            )
                        }
                    }
                }
                val last = _messages.value.lastOrNull { it.id == assistant.id }
                if (last != null && last.text.isBlank() && last.reasoning.isBlank()) {
                    replace(assistant.id) {
                        it.copy(text = "Модель вернула пустой ответ.", error = true)
                    }
                }
            } catch (error: CancellationException) {
                val last = _messages.value.lastOrNull { it.id == assistant.id }
                if (last != null && last.text.isBlank() && last.reasoning.isBlank()) {
                    replace(assistant.id) { it.copy(text = "Остановлено") }
                }
                throw error
            } catch (error: Exception) {
                if (!isActive) return@launch
                val detail = error.message ?: "Ошибка запроса"
                replace(assistant.id) {
                    it.copy(
                        text = if (it.text.isBlank()) detail else it.text,
                        error = it.text.isBlank(),
                    )
                }
            } finally {
                _busy.value = false
                persist()
            }
        }
    }

    fun stop() {
        generation?.cancel()
        _busy.value = false
        viewModelScope.launch { persist() }
    }

    fun clearChat() {
        generation?.cancel()
        viewModelScope.launch {
            val snapshot = _messages.value
            _messages.value = emptyList()
            _busy.value = false
            withContext(Dispatchers.IO) { chatStore.clear(snapshot) }
        }
    }

    fun dismissStatus() {
        _status.value = null
    }

    private fun replace(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) transform(it) else it }
    }

    private suspend fun persist() {
        val snapshot = _messages.value
        withContext(Dispatchers.IO) { chatStore.save(snapshot) }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T {
                    @Suppress("UNCHECKED_CAST")
                    return ChatViewModel(application) as T
                }
            }
    }
}
