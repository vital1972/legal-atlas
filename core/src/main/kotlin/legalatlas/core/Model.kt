package legalatlas.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Файл с вопросами, который готовит Claude. Формат описан в docs/question-format.md. */
@Serializable
data class DeckFile(
    @SerialName("schema_version") val schemaVersion: String = "1.0",
    val deck: DeckInfo,
    val topics: List<Topic>,
    val items: List<Item>,
)

@Serializable
data class DeckInfo(
    val id: String,
    val title: String,
    val world: String,
    val description: String? = null,
)

/** Область на карте. Соседи задают, какие шестиугольники окажутся рядом. */
@Serializable
data class Topic(
    val id: String,
    val title: String,
    val world: String? = null,
    val neighbors: List<String> = emptyList(),
)

@Serializable
data class KeyPoint(
    val id: String,
    val text: String,
    val weight: Int = 1,
    val required: Boolean = false,
)

@Serializable
data class Source(
    val title: String? = null,
    val locator: String? = null,
    val url: String? = null,
)

@Serializable
data class Item(
    val id: String,
    @SerialName("topic_id") val topicId: String,
    val level: String = "fact",
    val format: String = Formats.SELF_CHECK,
    val difficulty: Int = 3,
    val prompt: String,
    val answer: String,
    @SerialName("key_points") val keyPoints: List<KeyPoint> = emptyList(),
    @SerialName("common_errors") val commonErrors: List<String> = emptyList(),
    val explanation: String? = null,
    @SerialName("why_prompt") val whyPrompt: String? = null,
    /** Эталонное обоснование: почему ответ именно такой (норма, принцип, логика). */
    val rationale: String? = null,
    /** Дословная цитата из источника, на которой держится ответ. */
    @SerialName("rationale_quote") val rationaleQuote: String? = null,
    val hints: List<String> = emptyList(),
    val options: List<String> = emptyList(),
    @SerialName("correct_option") val correctOption: Int? = null,
    val source: Source? = null,
    val norms: List<String> = emptyList(),
    @SerialName("related_ids") val relatedIds: List<String> = emptyList(),
    val pretest: Boolean = false,
    @SerialName("valid_as_of") val validAsOf: String? = null,
)

object Formats {
    const val FREE_RECALL = "free_recall"
    const val SELF_CHECK = "self_check"
    const val CLOZE = "cloze"
    const val CHOICE = "choice"
    const val CASE_SCENARIO = "case_scenario"

    /** Ответ оценивает LLM; без сети — самооценка по эталону. */
    fun isOpen(format: String) = format == FREE_RECALL || format == CASE_SCENARIO
}

/** Оценка ответа по шкале FSRS. */
enum class Rating(val value: Int) {
    AGAIN(1), HARD(2), GOOD(3), EASY(4);

    companion object {
        fun of(value: Int) = entries.first { it.value == value.coerceIn(1, 4) }
    }
}

/** Насколько вы были уверены до того, как увидели правильный ответ. */
enum class Confidence { SURE, THINK, GUESS }

/** Состояние памяти по одному вопросу. Время везде в миллисекундах с эпохи. */
@Serializable
data class CardState(
    val itemId: String,
    val stability: Double,
    val difficulty: Double,
    val lastReview: Long,
    val due: Long,
    val reps: Int = 1,
    val lapses: Int = 0,
)

@Serializable
data class ReviewLog(
    val id: String,
    val itemId: String,
    val ts: Long,
    val rating: Int,
    val confidence: Confidence,
    val elapsedMs: Long,
    val mode: String,
    val score: Double? = null,
    val answer: String? = null,
    /** Обоснование по методу Фейнмана и его оценка: correct, partial, wrong, missing. */
    val reasoning: String? = null,
    val reasoningStatus: String? = null,
)

@Serializable
data class Settings(
    val serverUrl: String = "",
    val token: String = "",
    val newPerDay: Int = 12,
    val desiredRetention: Double = 0.9,
    val weeklyGoalMinutes: Int = 120,
    /** Просить обоснование «почему» у части развёрнутых вопросов. */
    val feynman: Boolean = true,
)

/** Всё, что приложение хранит на телефоне. */
@Serializable
data class AppState(
    val decks: Map<String, DeckFile> = emptyMap(),
    val cards: Map<String, CardState> = emptyMap(),
    val logs: List<ReviewLog> = emptyList(),
    val unlockedTopics: Set<String> = emptySet(),
    val flaggedItems: Set<String> = emptySet(),
    /** Обоснования, которые пользователь сохранил как свой эталон, по id вопроса. */
    val myRationales: Map<String, String> = emptyMap(),
    val settings: Settings = Settings(),
    val lastDeckSync: Long = 0,
    val syncedLogCount: Int = 0,
) {
    val items: Map<String, Item> by lazy {
        decks.values.flatMap { it.items }.associateBy { it.id }
    }
    val topics: Map<String, Topic> by lazy {
        decks.values.flatMap { d -> d.topics.map { it.copy(world = it.world ?: d.deck.world) } }
            .associateBy { it.id }
    }
}
