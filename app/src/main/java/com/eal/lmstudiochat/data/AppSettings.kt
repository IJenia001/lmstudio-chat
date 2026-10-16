package com.eal.lmstudiochat.data

data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = DEFAULT_API_KEY,
    val userName: String = "",
    val instructions: String = "",
    val model: String = "",
    val showReasoning: Boolean = true,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    fun systemPrompt(): String? {
        val name = userName.trim()
        val rules = instructions.trim()
        if (name.isEmpty() && rules.isEmpty()) return null
        return buildString {
            if (name.isNotEmpty()) {
                append("Имя пользователя: ")
                append(name)
            }
            if (rules.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Пользовательские инструкции:\n")
                append(rules)
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.100.32:1234"
        const val DEFAULT_API_KEY = "sk-lm-shXELRfn:QzuqgHMXNQveRmCZhphe"
        const val DEFAULT_MAX_TOKENS = 2048
    }
}

fun normalizeBaseUrl(raw: String): String {
    var url = raw.trim()
    if (url.isEmpty()) return url
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        url = "http://$url"
    }
    url = url.trimEnd('/')
    for (suffix in listOf("/v1/chat/completions", "/api/v1/models", "/api/v1", "/v1")) {
        if (url.endsWith(suffix)) {
            url = url.removeSuffix(suffix)
            break
        }
    }
    return url.trimEnd('/')
}
