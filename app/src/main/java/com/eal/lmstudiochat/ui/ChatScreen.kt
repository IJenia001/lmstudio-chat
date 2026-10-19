package com.eal.lmstudiochat.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.eal.lmstudiochat.data.ChatMessage
import com.eal.lmstudiochat.data.ChatSummary
import com.eal.lmstudiochat.data.RemoteModel
import com.eal.lmstudiochat.data.Role
import com.eal.lmstudiochat.ui.theme.AssistantBubble
import com.eal.lmstudiochat.ui.theme.Danger
import com.eal.lmstudiochat.ui.theme.Muted
import com.eal.lmstudiochat.ui.theme.UserBubble
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val chats by viewModel.chatList.collectAsState()
    val activeChatId by viewModel.activeChatId.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val models by viewModel.models.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val status by viewModel.status.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var followTail by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    val listState = remember(activeChatId) { LazyListState() }
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !programmaticScroll) {
                    followTail = false
                }
                return Offset.Zero
            }
        }
    }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) pendingImage = uri
    }

    LaunchedEffect(status) {
        val text = status ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.dismissStatus()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LM Chat")
                        TextButton(onClick = { modelMenu = true }, modifier = Modifier.height(28.dp)) {
                            val current = models.firstOrNull { it.id == settings.model }
                            val label = current?.name ?: settings.model.ifBlank { "Модель не выбрана" }
                            Text(
                                label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                color = Muted,
                            )
                        }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            if (models.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Список пуст — проверьте сервер") },
                                    onClick = {
                                        modelMenu = false
                                        viewModel.refreshModels()
                                    },
                                )
                            }
                            models.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(modelLabel(model)) },
                                    onClick = {
                                        viewModel.selectModel(model.id)
                                        modelMenu = false
                                    },
                                )
                            }
                        }
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::newChat) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Новый чат")
                    }
                    IconButton(onClick = { historyOpen = true }) {
                        Icon(Icons.Filled.History, contentDescription = "История чатов")
                    }
                    IconButton(onClick = viewModel::refreshModels) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить модели")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                draft = draft,
                onDraft = { draft = it },
                pendingImage = pendingImage,
                onClearImage = { pendingImage = null },
                busy = busy,
                onAttach = {
                    picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                },
                onSend = {
                    followTail = true
                    viewModel.send(draft, pendingImage)
                    draft = ""
                    pendingImage = null
                },
                onStop = viewModel::stop,
            )
        },
    ) { padding ->
        val tail = messages.lastOrNull()
        LaunchedEffect(activeChatId) { followTail = true }
        LaunchedEffect(listState) {
            snapshotFlow {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                val atEnd = last != null &&
                    last.index == info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + 48
                atEnd to listState.isScrollInProgress
            }.distinctUntilChanged().collect { (atEnd, scrolling) ->
                if (atEnd && !scrolling) followTail = true
            }
        }
        LaunchedEffect(activeChatId, messages.size, tail?.text, tail?.reasoning) {
            if (messages.isEmpty() || !followTail) return@LaunchedEffect
            programmaticScroll = true
            try {
                listState.revealEnd()
            } finally {
                programmaticScroll = false
            }
        }
        if (historyOpen) {
            HistorySheet(
                chats = chats,
                activeId = activeChatId,
                onDismiss = { historyOpen = false },
                onOpen = { id ->
                    viewModel.openChat(id)
                    historyOpen = false
                },
                onDelete = viewModel::deleteChat,
            )
        }
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Напишите сообщение или прикрепите изображение. Имя, инструкции, адрес и API-key — в настройках.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(userScroll),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    val waiting = busy &&
                        message.id == tail?.id &&
                        message.role == Role.Assistant &&
                        message.text.isBlank() &&
                        message.reasoning.isBlank()
                    MessageBubble(
                        message = message,
                        userName = settings.userName,
                        showReasoning = settings.showReasoning,
                        waiting = waiting,
                        onCopy = {
                            scope.launch { snackbar.showSnackbar("Скопировано") }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    userName: String,
    showReasoning: Boolean,
    waiting: Boolean,
    onCopy: () -> Unit,
) {
    val mine = message.role == Role.User
    val clipboard = LocalClipboardManager.current
    val label = if (mine) userName.ifBlank { "Вы" } else "Модель"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Surface(
            color = when {
                message.error -> Danger.copy(alpha = 0.18f)
                mine -> UserBubble
                else -> AssistantBubble
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .then(if (mine) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth())
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val text = message.text.ifBlank { message.reasoning }
                        if (text.isNotBlank()) {
                            clipboard.setText(AnnotatedString(text))
                            onCopy()
                        }
                    },
                ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message.imagePath?.let { path ->
                    Image(
                        painter = rememberAsyncImagePainter(File(path)),
                        contentDescription = "Вложенное изображение",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (!mine && showReasoning && message.reasoning.isNotBlank()) {
                    Text(
                        message.reasoning,
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val body = message.text
                if (body.isNotBlank()) {
                    if (mine || message.error) {
                        Text(body, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Markdown(
                            content = body,
                            modifier = Modifier.fillMaxWidth(),
                            colors = markdownColor(
                                text = Color(0xFFE7EADF),
                                codeBackground = Color(0xFF161914),
                                inlineCodeBackground = Color(0xFF161914),
                            ),
                        )
                    }
                } else if (waiting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                if (message.truncated) {
                    Text(
                        "Ответ обрезан по лимиту токенов",
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    pendingImage: Uri?,
    onClearImage: () -> Unit,
    busy: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pendingImage != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = rememberAsyncImagePainter(pendingImage),
                        contentDescription = "Выбранное изображение",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        "Изображение будет отправлено с сообщением",
                        modifier = Modifier.padding(start = 12.dp).weight(1f),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(onClick = onClearImage) {
                        Icon(Icons.Filled.Close, contentDescription = "Убрать изображение")
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, enabled = !busy) {
                    Icon(Icons.Filled.Image, contentDescription = "Прикрепить изображение")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    maxLines = 6,
                )
                if (busy) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Остановить")
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = draft.isNotBlank() || pendingImage != null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }
        }
    }
}

private suspend fun LazyListState.revealEnd() {
    val count = layoutInfo.totalItemsCount
    if (count == 0) return
    val last = count - 1
    if (layoutInfo.visibleItemsInfo.none { it.index == last }) {
        scrollToItem(last)
        withFrameNanos { }
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.lastOrNull { it.index == last } ?: return
    val delta = item.offset + item.size - info.viewportEndOffset
    if (delta > 1) {
        scroll { scrollBy(delta.toFloat()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    chats: List<ChatSummary>,
    activeId: String,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val formatter = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "История чатов",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        if (chats.isEmpty()) {
            Text(
                "Пока нет сохранённых чатов",
                modifier = Modifier.padding(20.dp),
                color = Muted,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(chats, key = { it.id }) { chat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(chat.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            chat.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (chat.id == activeId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            buildString {
                                if (chat.updatedAt > 0) {
                                    append(formatter.format(Date(chat.updatedAt)))
                                    append(" · ")
                                }
                                append(chat.preview)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { onDelete(chat.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить чат")
                    }
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun modelLabel(model: RemoteModel): String {
    val marks = buildList {
        if (model.loaded) add("загружена")
        if (model.vision) add("зрение")
    }
    return if (marks.isEmpty()) model.name else "${model.name} · ${marks.joinToString(", ")}"
}
