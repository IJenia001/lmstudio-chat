package com.eal.lmstudiochat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.eal.lmstudiochat.data.AppSettings
import com.eal.lmstudiochat.ui.theme.Muted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: (AppSettings) -> Unit,
) {
    val loaded by viewModel.settings.collectAsState()
    val testing by viewModel.testing.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val initial = loaded
    val leave = remember { mutableStateOf<(() -> Unit)?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(
                        onClick = { leave.value?.invoke() },
                        enabled = leave.value != null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        if (initial == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        SettingsForm(
            initial = initial,
            testing = testing,
            notice = notice,
            modifier = Modifier.padding(padding),
            onBack = onBack,
            onSave = viewModel::save,
            onTest = viewModel::test,
            onBindBack = { leave.value = it },
        )
    }
}

@Composable
private fun SettingsForm(
    initial: AppSettings,
    testing: Boolean,
    notice: String?,
    modifier: Modifier,
    onBack: (AppSettings) -> Unit,
    onSave: (AppSettings) -> Unit,
    onTest: (AppSettings) -> Unit,
    onBindBack: (() -> Unit) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(initial.apiKey) }
    var userName by rememberSaveable { mutableStateOf(initial.userName) }
    var instructions by rememberSaveable { mutableStateOf(initial.instructions) }
    var showReasoning by rememberSaveable { mutableStateOf(initial.showReasoning) }
    var maxTokens by rememberSaveable { mutableStateOf(initial.maxTokens.toString()) }

    fun draft(): AppSettings = initial.copy(
        baseUrl = baseUrl,
        apiKey = apiKey,
        userName = userName,
        instructions = instructions,
        showReasoning = showReasoning,
        maxTokens = maxTokens.toIntOrNull() ?: AppSettings.DEFAULT_MAX_TOKENS,
    )

    val goBack = { onBack(draft()) }
    SideEffect { onBindBack(goBack) }
    BackHandler(onBack = goBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Чат ходит в LM Studio по адресу сервера: POST /v1/chat/completions.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Адрес сервера") },
            placeholder = { Text("http://192.168.100.32:1234") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API-key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = userName,
            onValueChange = { userName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Имя пользователя") },
            singleLine = true,
        )
        OutlinedTextField(
            value = instructions,
            onValueChange = { instructions = it },
            modifier = Modifier.fillMaxWidth().height(160.dp),
            label = { Text("Пользовательские инструкции") },
            placeholder = { Text("Как модели отвечать, тон, ограничения") },
        )
        OutlinedTextField(
            value = maxTokens,
            onValueChange = { maxTokens = it.filter(Char::isDigit).take(5) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Максимум токенов ответа") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Показывать рассуждения")
                Text(
                    "Если выключить, модель отвечает сразу, без цепочки мыслей.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = showReasoning, onCheckedChange = { showReasoning = it })
        }
        notice?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSave(draft()) }, enabled = !testing) {
                Text("Сохранить")
            }
            OutlinedButton(onClick = { onTest(draft()) }, enabled = !testing) {
                Text(if (testing) "Проверка…" else "Проверить связь")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onBack(draft()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Назад")
        }
        Spacer(Modifier.height(24.dp))
    }
}
