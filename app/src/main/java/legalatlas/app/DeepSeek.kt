package legalatlas.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import legalatlas.core.AtlasJson

/** Запрос к DeepSeek (OpenAI-совместимый API) с ответом в виде JSON-объекта. */
class DeepSeek(private val apiKey: String, private val model: String = "deepseek-chat") {

    /** Возвращает текст ответа модели (JSON-объект строкой). */
    suspend fun json(system: String, user: String, maxTokens: Int, timeoutMs: Int = 60_000): String {
        val body = AtlasJson.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                maxTokens = maxTokens,
                responseFormat = ResponseFormat("json_object"),
                messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
            ),
        )
        val root = AtlasJson.parseToJsonElement(post(body, timeoutMs)).jsonObject
        return root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
    }

    private suspend fun post(body: String, timeoutMs: Int): String = withContext(Dispatchers.IO) {
        val conn = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401) throw IOException("DeepSeek не принял ключ. Проверьте его в настройках")
            if (code == 402) throw IOException("На счёте DeepSeek закончились деньги")
            if (code !in 200..299) throw IOException("DeepSeek ответил $code: ${text.take(200)}")
            text
        } finally {
            conn.disconnect()
        }
    }

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ResponseFormat(val type: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        @SerialName("response_format") val responseFormat: ResponseFormat,
        val messages: List<ChatMessage>,
    )
}
