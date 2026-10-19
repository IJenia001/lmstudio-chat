package com.eal.lmstudiochat.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class LmException(message: String) : Exception(message)

data class StreamPiece(
    val content: String = "",
    val reasoning: String = "",
    val finishReason: String? = null,
)

class LmStudioClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val shortHttp = http.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    fun listModels(baseUrl: String, apiKey: String): List<RemoteModel> {
        val root = normalizeBaseUrl(baseUrl)
        val native = runCatching { getJson(root, apiKey, "/api/v1/models") }.getOrNull()
        if (native != null && native.has("models")) {
            return parseNativeModels(native)
        }
        val openai = getJson(root, apiKey, "/v1/models")
        return parseOpenAiModels(openai)
    }

    suspend fun streamChat(
        settings: AppSettings,
        history: List<ChatMessage>,
        imageDataUrl: (String) -> String,
        onPiece: suspend (StreamPiece) -> Unit,
    ) {
        val root = normalizeBaseUrl(settings.baseUrl)
        if (root.isBlank()) throw LmException("Укажите адрес сервера")
        if (settings.model.isBlank()) throw LmException("Выберите модель")

        val withImages = history.any { it.imagePath != null }
        var emitted = false
        try {
            postChat(root, settings, history, imageDataUrl, rawBase64 = false) { piece ->
                emitted = true
                onPiece(piece)
            }
        } catch (error: LmException) {
            val retry = !emitted && withImages &&
                error.message.orEmpty().contains("base64", ignoreCase = true)
            if (!retry) throw error
            postChat(root, settings, history, imageDataUrl, rawBase64 = true, onPiece)
        }
    }

    private suspend fun postChat(
        root: String,
        settings: AppSettings,
        history: List<ChatMessage>,
        imageDataUrl: (String) -> String,
        rawBase64: Boolean,
        onPiece: suspend (StreamPiece) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("model", settings.model)
            .put("stream", true)
            .put("temperature", 0.7)
            .put("max_tokens", settings.maxTokens.coerceIn(64, 32768))
            .put("messages", messagesJson(settings, history, imageDataUrl, rawBase64))
        if (!settings.showReasoning) {
            payload.put("reasoning_effort", "none")
        }

        val request = Request.Builder()
            .url("$root/v1/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val call = http.newCall(request)
        coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                val body = response.body ?: throw LmException("Пустой ответ сервера")
                if (!response.isSuccessful) {
                    throw LmException(errorMessage(body.string(), response.code))
                }
                val contentType = body.contentType()?.toString().orEmpty()
                if (!contentType.contains("event-stream") && !contentType.contains("text/plain")) {
                    val text = body.string()
                    val json = JSONObject(text)
                    if (json.has("error")) throw LmException(errorMessage(text, response.code))
                    onPiece(parsePiece(json))
                    return@use
                }
                val source = body.source()
                while (isActive) {
                    val line = source.readUtf8Line() ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    if (json.has("error")) throw LmException(errorMessage(data, response.code))
                    onPiece(parsePiece(json))
                }
            }
        } catch (error: IOException) {
            if (!isActive) throw CancellationException("Запрос остановлен")
            throw LmException(error.message ?: "Сеть недоступна")
        }
    }

    private fun getJson(root: String, apiKey: String, path: String): JSONObject {
        val request = Request.Builder()
            .url(root + path)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .get()
            .build()
        shortHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LmException(errorMessage(text, response.code))
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun messagesJson(
        settings: AppSettings,
        history: List<ChatMessage>,
        imageDataUrl: (String) -> String,
        rawBase64: Boolean,
    ): JSONArray {
        val messages = JSONArray()
        settings.systemPrompt()?.let { prompt ->
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", prompt),
            )
        }
        history.filter { !it.error }.forEach { message ->
            val role = if (message.role == Role.User) "user" else "assistant"
            val image = message.imagePath
            if (image != null && message.role == Role.User) {
                val dataUrl = imageDataUrl(image)
                val url = if (rawBase64) dataUrl.substringAfter("base64,", dataUrl) else dataUrl
                val parts = JSONArray()
                val text = message.text.ifBlank { "Посмотри на изображение." }
                parts.put(JSONObject().put("type", "text").put("text", text))
                parts.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", url)),
                )
                messages.put(JSONObject().put("role", role).put("content", parts))
            } else {
                val content = when (message.role) {
                    Role.User -> message.text
                    Role.Assistant -> message.text.ifBlank { message.reasoning }
                }
                if (content.isBlank()) return@forEach
                messages.put(
                    JSONObject()
                        .put("role", role)
                        .put("content", content),
                )
            }
        }
        return alternateRoles(messages)
    }

    /**
     * Gemma and similar chat templates reject a history where two user or two
     * assistant messages follow each other. Failed replies are omitted, so the
     * next user message has to be merged into the previous one.
     */
    private fun alternateRoles(messages: JSONArray): JSONArray {
        val out = JSONArray()
        val dialog = mutableListOf<JSONObject>()
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            val role = message.optString("role")
            if (role == "system" && dialog.isEmpty() && out.length() == 0) {
                out.put(message)
                continue
            }
            if (role != "user" && role != "assistant") continue
            if (dialog.isEmpty()) {
                if (role == "assistant") continue
                dialog += message
                continue
            }
            val previous = dialog.last()
            if (previous.optString("role") == role) {
                dialog[dialog.lastIndex] = mergeMessages(previous, message)
            } else {
                dialog += message
            }
        }
        dialog.forEach { out.put(it) }
        return out
    }

    private fun mergeMessages(left: JSONObject, right: JSONObject): JSONObject {
        val leftContent = left.get("content")
        val rightContent = right.get("content")
        val content = if (leftContent is String && rightContent is String) {
            leftContent.trimEnd() + "\n\n" + rightContent.trimStart()
        } else {
            val parts = JSONArray()
            appendContent(parts, leftContent)
            appendContent(parts, rightContent)
            parts
        }
        return JSONObject()
            .put("role", left.optString("role"))
            .put("content", content)
    }

    private fun appendContent(parts: JSONArray, content: Any) {
        when (content) {
            is JSONArray -> {
                for (i in 0 until content.length()) parts.put(content.get(i))
            }
            else -> parts.put(JSONObject().put("type", "text").put("text", content.toString()))
        }
    }

    private fun parseNativeModels(json: JSONObject): List<RemoteModel> {
        val array = json.optJSONArray("models") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("key")
                if (item.optString("type") != "llm" || id.isBlank()) continue
                val loaded = (item.optJSONArray("loaded_instances")?.length() ?: 0) > 0
                val vision = item.optJSONObject("capabilities")?.optBoolean("vision") == true
                add(
                    RemoteModel(
                        id = id,
                        name = item.optString("display_name").ifBlank { id },
                        vision = vision,
                        loaded = loaded,
                    ),
                )
            }
        }.sortedWith(compareByDescending<RemoteModel> { it.loaded }.thenBy { it.name })
    }

    private fun parseOpenAiModels(json: JSONObject): List<RemoteModel> {
        val array = json.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank() || id.contains("embed", ignoreCase = true)) continue
                add(RemoteModel(id = id, name = id, vision = false, loaded = false))
            }
        }
    }

    private fun parsePiece(json: JSONObject): StreamPiece {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val payload = choice?.optJSONObject("delta")
            ?: choice?.optJSONObject("message")
            ?: JSONObject()
        return StreamPiece(
            content = payload.stringOrEmpty("content"),
            reasoning = payload.stringOrEmpty("reasoning_content"),
            finishReason = choice?.stringOrEmpty("finish_reason")?.ifBlank { null },
        )
    }

    private fun errorMessage(body: String, code: Int): String {
        val fromJson = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.stringOrEmpty("message")
                ?: json.stringOrEmpty("message")
        }.getOrNull().orEmpty()
        if (fromJson.isNotBlank()) return readableError(fromJson)
        if (code == 401) return "Сервер отклонил API-key"
        return "Ошибка сервера ($code)"
    }

    private fun readableError(raw: String): String {
        val text = raw
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\t", " ")
        val errorLine = text.lineSequence()
            .map { it.trim().trim('"', '\'', ',', ' ') }
            .lastOrNull { it.startsWith("Error:") || it.contains("Exception:") }
            ?.removePrefix("Error:")
            ?.trim()
            .orEmpty()
        val message = errorLine.ifBlank { text.trim() }
        return message
            .replace(Regex("\\s+"), " ")
            .take(400)
    }

    private fun JSONObject.stringOrEmpty(key: String): String {
        if (!has(key) || isNull(key)) return ""
        return optString(key, "")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
