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
    private val chatFile = File(context.filesDir, "chat.json")
    private val imagesDir = File(context.filesDir, "images").apply { mkdirs() }

    fun load(): List<ChatMessage> {
        if (!chatFile.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(chatFile.readText())
            val array = root.optJSONArray("messages") ?: JSONArray()
            buildList {
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
        }.getOrDefault(emptyList())
    }

    fun save(messages: List<ChatMessage>) {
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
        chatFile.writeText(JSONObject().put("messages", array).toString())
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

    fun clear(messages: List<ChatMessage>) {
        messages.mapNotNull { it.imagePath }.forEach { path ->
            runCatching { File(path).delete() }
        }
        chatFile.delete()
    }
}
