package legalatlas.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import legalatlas.core.AtlasJson
import legalatlas.core.Item
import legalatlas.core.KeyPoint
import legalatlas.core.ReviewLog

@Serializable
data class GradeItem(
    val prompt: String,
    val answer: String,
    @SerialName("key_points") val keyPoints: List<KeyPoint>,
    @SerialName("common_errors") val commonErrors: List<String>,
)

@Serializable
data class GradeRequest(val item: GradeItem, val answer: String)

@Serializable
data class KeyPointStatus(val id: String, val status: String)

@Serializable
data class GradeResult(
    @SerialName("key_points") val keyPoints: List<KeyPointStatus> = emptyList(),
    val errors: List<String> = emptyList(),
    val score: Double = 0.0,
    val rating: Int = 1,
    val feedback: String = "",
)

@Serializable
private data class ReviewsBody(val reviews: List<ReviewLog>)

/** Колода с сервера в виде исходного JSON-текста: проверка идёт тем же кодом, что и для файла. */
data class RemoteDeck(val id: String, val json: String)

data class DeckSync(val decks: List<RemoteDeck>, val serverTime: Long)

/** Клиент к серверу на Cloudflare (каталог server/). */
class Api(private val baseUrl: String, private val token: String) {

    suspend fun grade(item: Item, answer: String): GradeResult {
        val body = AtlasJson.encodeToString(
            GradeRequest.serializer(),
            GradeRequest(GradeItem(item.prompt, item.answer, item.keyPoints, item.commonErrors), answer),
        )
        return AtlasJson.decodeFromString(GradeResult.serializer(), request("POST", "/api/grade", body, 60_000))
    }

    suspend fun decksSince(since: Long): DeckSync {
        val root = AtlasJson.parseToJsonElement(request("GET", "/api/decks?since=$since", null)).jsonObject
        val decks = (root["decks"] as? JsonArray).orEmpty().map {
            val o = it.jsonObject
            RemoteDeck(o["id"]!!.jsonPrimitive.content, o["json"].toString())
        }
        return DeckSync(decks, root["now"]?.jsonPrimitive?.long ?: System.currentTimeMillis())
    }

    suspend fun uploadReviews(reviews: List<ReviewLog>) {
        request("POST", "/api/reviews", AtlasJson.encodeToString(ReviewsBody.serializer(), ReviewsBody(reviews)))
    }

    private suspend fun request(method: String, path: String, body: String?, timeoutMs: Int = 20_000): String =
        withContext(Dispatchers.IO) {
            val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IOException("Сервер ответил $code: ${text.take(200)}")
                text
            } finally {
                conn.disconnect()
            }
        }
}
