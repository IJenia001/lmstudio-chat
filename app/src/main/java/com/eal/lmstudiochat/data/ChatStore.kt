package com.eal.lmstudiochat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ChatStore(private val context: Context) {
    private val libraryFile = File(context.filesDir, "chats.json")
    private val legacyFile = File(context.filesDir, "chat.json")
    private val imagesDir = File(context.filesDir, "images").apply { mkdirs() }

    fun loadLibrary(): List<StoredChat> {
        val fromLibrary = readLibrary()
        if (fromLibrary != null) return fromLibrary
        val legacy = readMessages(legacyFile)
        val migrated = if (legacy.isEmpty()) {
            listOf(StoredChat())
        } else {
            listOf(
                StoredChat(
                    title = chatTitle(legacy),
                    updatedAt = System.currentTimeMillis(),
                    messages = legacy,
                ),
            )
        }
        writeLibrary(migrated)
        legacyFile.delete()
        return migrated
    }

    fun saveLibrary(chats: List<StoredChat>) {
        writeLibrary(chats)
    }

    fun importImage(uri: Uri): String {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val maxSide = 1280
            val width = info.size.width
            val height = info.size.height
            val largest = maxOf(width, height)
            if (largest > maxSide && largest > 0) {
                val scale = maxSide.toFloat() / largest
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        val file = File(imagesDir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)) {
                error("Не удалось сжать изображение")
            }
        }
        bitmap.recycle()
        return file.absolutePath
    }

    fun imageDataUrl(path: String): String {
        val file = File(path)
        if (!file.exists() || file.parentFile?.canonicalFile != imagesDir.canonicalFile) {
            error("Изображение не найдено")
        }
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }

    fun deleteImages(messages: List<ChatMessage>) {
        messages.mapNotNull { it.imagePath }.forEach { path ->
            runCatching { File(path).delete() }
        }
    }

    private fun readLibrary(): List<StoredChat>? {
        if (!libraryFile.exists()) return null
        return runCatching {
            val root = JSONObject(libraryFile.readText())
            val array = root.optJSONArray("chats") ?: JSONArray()
            val chats = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        StoredChat(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            title = item.optString("title").ifBlank { "Новый чат" },
                            updatedAt = item.optLong("updatedAt"),
                            messages = messagesFrom(item.optJSONArray("messages") ?: JSONArray()),
                        ),
                    )
                }
            }
            chats.ifEmpty { listOf(StoredChat()) }
        }.getOrElse { listOf(StoredChat()) }
    }

    private fun writeLibrary(chats: List<StoredChat>) {
        val array = JSONArray()
        chats.forEach { chat ->
            array.put(
                JSONObject()
                    .put("id", chat.id)
                    .put("title", chat.title)
                    .put("updatedAt", chat.updatedAt)
                    .put("messages", messagesJson(chat.messages)),
            )
        }
        libraryFile.writeText(JSONObject().put("chats", array).toString())
    }

    private fun readMessages(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            messagesFrom(root.optJSONArray("messages") ?: JSONArray())
        }.getOrDefault(emptyList())
    }

    private fun messagesFrom(array: JSONArray): List<ChatMessage> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val role = when (item.optString("role")) {
                "user" -> Role.User
                else -> Role.Assistant
            }
            add(
                ChatMessage(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    role = role,
                    text = item.optString("text"),
                    reasoning = item.optString("reasoning"),
                    imagePath = item.optString("imagePath").ifBlank { null },
                    error = item.optBoolean("error"),
                    truncated = item.optBoolean("truncated"),
                ),
            )
        }
    }

    private fun messagesJson(messages: List<ChatMessage>): JSONArray {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("role", if (message.role == Role.User) "user" else "assistant")
                    .put("text", message.text)
                    .put("reasoning", message.reasoning)
                    .put("imagePath", message.imagePath ?: "")
                    .put("error", message.error)
                    .put("truncated", message.truncated),
            )
        }
        return array
    }
}

fun chatTitle(messages: List<ChatMessage>): String {
    val firstUser = messages.firstOrNull { it.role == Role.User } ?: return "Новый чат"
    val text = firstUser.text.trim().replace(Regex("\\s+"), " ")
    if (text.isNotEmpty()) return text.take(48)
    if (firstUser.imagePath != null) return "Изображение"
    return "Новый чат"
}

fun chatPreview(messages: List<ChatMessage>): String {
    val last = messages.lastOrNull { it.text.isNotBlank() } ?: return "Пустой чат"
    return last.text.trim().replace(Regex("\\s+"), " ").take(80)
}
