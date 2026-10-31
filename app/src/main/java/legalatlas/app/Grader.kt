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
import legalatlas.core.Item

/** Оценка развёрнутого ответа прямо с телефона, без сервера: DeepSeek по ключу из настроек. */
class Grader(private val apiKey: String, private val model: String = "deepseek-chat") {

    suspend fun grade(item: Item, answer: String): GradeResult {
        val body = AtlasJson.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                maxTokens = 2000,
                responseFormat = ResponseFormat("json_object"),
                messages = listOf(
                    ChatMessage("system", "$SYSTEM\n\n$JSON_SHAPE"),
                    ChatMessage("user", userPrompt(item, answer.take(8000))),
                ),
            ),
        )
        val root = AtlasJson.parseToJsonElement(post(body)).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
        val result = runCatching { AtlasJson.decodeFromString(GradeResult.serializer(), content) }
            .getOrElse { throw IOException("DeepSeek вернул ответ не по схеме") }
        return result.copy(score = result.score.coerceIn(0.0, 1.0), rating = result.rating.coerceIn(1, 4))
    }

    private suspend fun post(body: String): String = withContext(Dispatchers.IO) {
        val conn = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
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

    companion object {
        // Та же рубрика, что на сервере (server/src/grade.ts): оценки не зависят от того, кто вызывает модель.
        private val SYSTEM = """
            Ты проверяешь ответ опытного российского юриста на тренировочный вопрос.
            Сравни ответ с эталоном и ключевыми тезисами. Оценивай смысл, а не формулировки: синонимы,
            другой порядок и сокращения засчитываются. Ссылку на норму засчитывай, если номер статьи верный.

            Для каждого ключевого тезиса поставь статус: covered (раскрыт), partial (упомянут неточно или
            неполно), missing (отсутствует). В errors перечисли только фактические юридические ошибки в ответе
            (неверный срок, не та норма, перепутанные последствия); пропуск тезиса ошибкой не считается.

            score: доля набранного веса тезисов от 0 до 1 (covered = полный вес, partial = половина).
            rating по шкале интервального повторения:
            1 — не раскрыт хотя бы один обязательный тезис или есть фактическая ошибка;
            2 — обязательные тезисы есть, но score ниже 0.7;
            3 — score от 0.7 до 0.95;
            4 — score выше 0.95 и ответ точный.
            feedback: 1–3 коротких предложения по-русски, обращение на «вы»: что упущено или неверно и как запомнить.
        """.trimIndent()

        private val JSON_SHAPE = """
            Ответь только JSON-объектом такого вида:
            {"key_points": [{"id": "kp1", "status": "covered"}], "errors": ["..."], "score": 0.8, "rating": 3, "feedback": "..."}
        """.trimIndent()

        private fun userPrompt(item: Item, answer: String): String {
            val points = item.keyPoints.joinToString("\n") { kp ->
                "- [${kp.id}] ${kp.text} (вес ${kp.weight}${if (kp.required) ", обязательный" else ""})"
            }
            return listOfNotNull(
                "Вопрос:\n${item.prompt}",
                "Эталонный ответ:\n${item.answer}",
                "Ключевые тезисы:\n${points.ifBlank { "- (не заданы: сравнивай с эталоном целиком)" }}",
                item.commonErrors.takeIf { it.isNotEmpty() }?.let { "Типичные ошибки:\n" + it.joinToString("\n") { e -> "- $e" } },
                "Ответ пользователя:\n<answer>\n$answer\n</answer>",
            ).joinToString("\n\n")
        }
    }
}
