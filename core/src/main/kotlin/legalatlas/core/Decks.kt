package legalatlas.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

val AtlasJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

data class DeckCheck(val deck: DeckFile?, val errors: List<String>, val warnings: List<String>) {
    val ok get() = deck != null && errors.isEmpty()
}

data class ImportResult(val state: AppState, val added: Int, val updated: Int, val removed: Int)

object Decks {
    private val knownFormats = setOf(
        Formats.FREE_RECALL, Formats.SELF_CHECK, Formats.CLOZE, Formats.CHOICE, Formats.CASE_SCENARIO,
    )

    /** Разбирает и проверяет файл. Ошибки блокируют загрузку, предупреждения — нет. */
    fun parse(text: String): DeckCheck {
        val deck = try {
            AtlasJson.decodeFromString(DeckFile.serializer(), text)
        } catch (e: SerializationException) {
            return DeckCheck(null, listOf("Файл не соответствует формату: ${e.message}"), emptyList())
        } catch (e: IllegalArgumentException) {
            return DeckCheck(null, listOf("Файл не соответствует формату: ${e.message}"), emptyList())
        }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val topicIds = deck.topics.map { it.id }.toSet()

        if (deck.deck.id.isBlank()) errors += "Не указан deck.id"
        if (deck.items.isEmpty()) errors += "В файле нет вопросов"
        deck.topics.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { errors += "Тема $it повторяется" }
        deck.items.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { errors += "Вопрос $it повторяется" }

        for (t in deck.topics) {
            t.neighbors.filter { it !in topicIds }.forEach { warnings += "Тема ${t.id}: сосед $it не найден в этом файле" }
        }
        for (item in deck.items) {
            val where = "Вопрос ${item.id}"
            if (item.topicId !in topicIds) errors += "$where: тема ${item.topicId} не описана в topics"
            if (item.prompt.isBlank()) errors += "$where: пустой prompt"
            if (item.answer.isBlank()) errors += "$where: пустой answer"
            if (item.format !in knownFormats) warnings += "$where: формат ${item.format} не поддерживается, будет показан как self_check"
            if (item.format == Formats.CHOICE) {
                val correct = item.correctOption
                if (item.options.size < 2) errors += "$where: для choice нужно минимум два варианта"
                if (correct == null || correct !in item.options.indices) errors += "$where: correct_option вне списка вариантов"
            }
            if (Formats.isOpen(item.format) && item.keyPoints.isEmpty()) {
                warnings += "$where: нет key_points, оценка свободного ответа будет менее точной"
            }
        }
        return DeckCheck(deck, errors, warnings)
    }

    /**
     * Добавляет или заменяет колоду. История ответов привязана к id вопроса, поэтому
     * при обновлении текста прогресс сохраняется. Состояние удалённых вопросов стирается.
     */
    fun import(state: AppState, deck: DeckFile): ImportResult {
        val old = state.decks[deck.deck.id]
        val oldIds = old?.items?.map { it.id }?.toSet() ?: emptySet()
        val newIds = deck.items.map { it.id }.toSet()
        val removedIds = oldIds - newIds
        val next = state.copy(
            decks = state.decks + (deck.deck.id to deck),
            cards = state.cards - removedIds,
        )
        return ImportResult(
            state = next,
            added = (newIds - oldIds).size,
            updated = (newIds intersect oldIds).size,
            removed = removedIds.size,
        )
    }
}
