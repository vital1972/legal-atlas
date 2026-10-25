package legalatlas.core

import java.util.UUID

/** Состояние области для карты. */
data class TopicMastery(
    val topic: Topic,
    val unlocked: Boolean,
    val total: Int,
    val introduced: Int,
    /** Средняя вероятность вспомнить по всем вопросам области (неизученные считаются за 0). */
    val mastery: Double,
    /** Средняя вероятность вспомнить по уже изученным вопросам. */
    val recall: Double,
    val due: Int,
)

object Progress {
    fun topics(state: AppState, now: Long): List<TopicMastery> {
        val fsrs = Fsrs(desiredRetention = state.settings.desiredRetention)
        val byTopic = state.items.values.filter { it.id !in state.flaggedItems }.groupBy { it.topicId }
        return state.topics.values.map { topic ->
            val items = byTopic[topic.id].orEmpty()
            val cards = items.mapNotNull { state.cards[it.id] }
            val rs = cards.map { fsrs.retrievability(it, now) }
            TopicMastery(
                topic = topic,
                unlocked = topic.id in state.unlockedTopics || cards.isNotEmpty(),
                total = items.size,
                introduced = cards.size,
                mastery = if (items.isEmpty()) 0.0 else rs.sum() / items.size,
                recall = if (rs.isEmpty()) 0.0 else rs.average(),
                due = cards.count { it.due <= now },
            )
        }
    }

    /**
     * Записывает ответ: обновляет состояние FSRS и журнал. Ответы при «разведке»
     * не влияют на расписание: это попытка до изучения, а не повторение.
     */
    fun record(
        state: AppState,
        item: Item,
        mode: Mode,
        rating: Rating,
        confidence: Confidence,
        elapsedMs: Long,
        now: Long,
        score: Double? = null,
        answer: String? = null,
    ): AppState {
        val fsrs = Fsrs(desiredRetention = state.settings.desiredRetention)
        val cards = if (mode == Mode.PRETEST) state.cards else {
            val old = state.cards[item.id]
            val next = if (old == null) fsrs.first(item.id, rating, now) else fsrs.review(old, rating, now)
            state.cards + (item.id to next)
        }
        val log = ReviewLog(
            id = UUID.randomUUID().toString(),
            itemId = item.id,
            ts = now,
            rating = rating.value,
            confidence = confidence,
            elapsedMs = elapsedMs,
            mode = mode.name,
            score = score,
            answer = answer,
        )
        return state.copy(cards = cards, logs = state.logs + log)
    }

    /** Уверенная ошибка: самый ценный для разбора случай (эффект гиперкоррекции). */
    fun isConfidentError(rating: Rating, confidence: Confidence) =
        rating == Rating.AGAIN && confidence == Confidence.SURE

    /** Оценка для вопроса с вариантами: верно или нет плюс уверенность. */
    fun choiceRating(correct: Boolean, confidence: Confidence): Rating = when {
        !correct -> Rating.AGAIN
        confidence == Confidence.GUESS -> Rating.HARD
        else -> Rating.GOOD
    }
}
