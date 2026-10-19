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
import com.eal.lmstudiochat.data.ChatSummary
import com.eal.lmstudiochat.data.RemoteModel
import com.eal.lmstudiochat.data.Role
import com.eal.lmstudiochat.data.StoredChat
import com.eal.lmstudiochat.data.chatPreview
import com.eal.lmstudiochat.data.chatTitle
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

    private val _chats = MutableStateFlow<List<StoredChat>>(emptyList())
    private val _chatList = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chatList: StateFlow<List<ChatSummary>> = _chatList

    private val _activeChatId = MutableStateFlow("")
    val activeChatId: StateFlow<String> = _activeChatId

    private val _models = MutableStateFlow<List<RemoteModel>>(emptyList())
    val models: StateFlow<List<RemoteModel>> = _models

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private var generation: Job? = null

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { chatStore.loadLibrary() }
            val newest = loaded.maxByOrNull { it.updatedAt } ?: StoredChat()
            _chats.value = loaded
            _activeChatId.value = newest.id
            _messages.value = newest.messages
            publishChatList()
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
        if (_activeChatId.value.isBlank()) return
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
            setMessages(history + assistant)
            persist()

            try {
                var accText = ""
                var accReason = ""
                var truncated = false
                var lastFlush = 0L
                suspend fun flush(force: Boolean) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!force && now - lastFlush < 80) return
                    lastFlush = now
                    val textSnapshot = accText
                    val reasonSnapshot = accReason
                    val truncatedSnapshot = truncated
                    withContext(Dispatchers.Main) {
                        replace(assistant.id) {
                            it.copy(
                                text = textSnapshot,
                                reasoning = reasonSnapshot,
                                truncated = truncatedSnapshot,
                            )
                        }
                    }
                }
                client.streamChat(
                    settings = settings,
                    history = history,
                    imageDataUrl = chatStore::imageDataUrl,
                ) { piece ->
                    accText += piece.content
                    accReason += piece.reasoning
                    if (piece.finishReason == "length") truncated = true
                    flush(piece.finishReason != null)
                }
                flush(true)
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

    fun newChat() {
        viewModelScope.launch {
            finishGeneration()
            if (_messages.value.isEmpty()) return@launch
            val chat = StoredChat()
            _chats.value = listOf(chat) + _chats.value
            _activeChatId.value = chat.id
            setMessages(emptyList())
            persist()
        }
    }

    fun openChat(id: String) {
        if (id == _activeChatId.value) return
        viewModelScope.launch {
            finishGeneration()
            val chat = _chats.value.firstOrNull { it.id == id } ?: return@launch
            _activeChatId.value = chat.id
            setMessages(chat.messages)
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            if (id == _activeChatId.value) finishGeneration()
            val target = _chats.value.firstOrNull { it.id == id } ?: return@launch
            withContext(Dispatchers.IO) { chatStore.deleteImages(target.messages) }
            val remaining = _chats.value.filterNot { it.id == id }
            if (remaining.isEmpty()) {
                val fresh = StoredChat()
                _chats.value = listOf(fresh)
                _activeChatId.value = fresh.id
                setMessages(emptyList())
            } else {
                _chats.value = remaining
                if (_activeChatId.value == id) {
                    val next = remaining.maxBy { it.updatedAt }
                    _activeChatId.value = next.id
                    setMessages(next.messages)
                } else {
                    publishChatList()
                }
            }
            persist()
        }
    }

    fun dismissStatus() {
        _status.value = null
    }

    private suspend fun finishGeneration() {
        val job = generation ?: return
        job.cancel()
        job.join()
    }

    private fun replace(id: String, transform: (ChatMessage) -> ChatMessage) {
        setMessages(_messages.value.map { if (it.id == id) transform(it) else it })
    }

    private fun setMessages(messages: List<ChatMessage>) {
        _messages.value = messages
        val id = _activeChatId.value
        if (id.isBlank()) return
        _chats.value = _chats.value.map { chat ->
            if (chat.id != id) {
                chat
            } else {
                chat.copy(
                    messages = messages,
                    title = chatTitle(messages),
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        publishChatList()
    }

    private fun publishChatList() {
        _chatList.value = _chats.value
            .sortedByDescending { it.updatedAt }
            .map { chat ->
                ChatSummary(
                    id = chat.id,
                    title = chat.title,
                    updatedAt = chat.updatedAt,
                    preview = chatPreview(chat.messages),
                )
            }
    }

    private suspend fun persist() {
        val snapshot = _chats.value
        withContext(Dispatchers.IO) { chatStore.saveLibrary(snapshot) }
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
