package com.eal.lmstudiochat.data

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val reasoning: String = "",
    val imagePath: String? = null,
    val error: Boolean = false,
    val truncated: Boolean = false,
)

enum class Role {
    User,
    Assistant,
}

data class RemoteModel(
    val id: String,
    val name: String,
    val vision: Boolean,
    val loaded: Boolean,
)
