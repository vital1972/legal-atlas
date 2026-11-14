package legalatlas.core

/** Насколько точно вспомнили ответ: оценивает сам пользователь, без ИИ. */
enum class Accuracy { NONE, PARTIAL, FULL }

/** Насколько легко далось вспоминание. */
enum class Ease { HARD, NORMAL, EASY }

/**
 * Лайт-режим: ИИ только создаёт вопросы, а дальше два простых занятия.
 * Ознакомление — прочитать вопрос с ответом; вспоминание — вспомнить про себя
 * и оценить точность и лёгкость. Дату следующего повторения считает FSRS.
 */
object Lite {
    /**
     * Точность и лёгкость → оценка FSRS.
     * Не вспомнил → «снова»; частично или полностью, но с трудом → «трудно»;
     * полностью и нормально → «хорошо»; полностью и легко → «легко».
     */
    fun rating(accuracy: Accuracy, ease: Ease? = null): Rating = when (accuracy) {
        Accuracy.NONE -> Rating.AGAIN
        Accuracy.PARTIAL -> Rating.HARD
        Accuracy.FULL -> when (ease ?: Ease.NORMAL) {
            Ease.HARD -> Rating.HARD
            Ease.NORMAL -> Rating.GOOD
            Ease.EASY -> Rating.EASY
        }
    }

    /** Ознакомление: «Понятно» — вспомнить завтра; «Уже знаю» — сразу длинный интервал. */
    fun readRating(alreadyKnown: Boolean): Rating = if (alreadyKnown) Rating.EASY else Rating.HARD

    private fun usable(state: AppState, item: Item) =
        item.id !in state.flaggedItems && item.id !in state.retiredItems

    private fun inDeck(state: AppState, deckId: String?): List<Item> =
        if (deckId == null) state.decks.values.sortedBy { it.deck.title }.flatMap { it.items }
        else state.decks[deckId]?.items.orEmpty()

    /** Вопросы, которых ещё не видели: в порядке документа. */
    fun unread(state: AppState, deckId: String? = null): List<Item> =
        inDeck(state, deckId).filter { usable(state, it) && it.id !in state.cards }

    /** Вопросы, которые пора вспомнить: сначала самые забытые. */
    fun due(state: AppState, now: Long, deckId: String? = null): List<Item> {
        val fsrs = Fsrs(desiredRetention = state.settings.desiredRetention)
        return inDeck(state, deckId)
            .filter { usable(state, it) }
            .mapNotNull { item -> state.cards[item.id]?.takeIf { it.due <= now }?.let { item to it } }
            .sortedBy { fsrs.retrievability(it.second, now) }
            .map { it.first }
    }

    /** Когда ближайшее повторение, если сейчас вспоминать нечего. */
    fun nextDue(state: AppState, now: Long, deckId: String? = null): Long? =
        inDeck(state, deckId).filter { usable(state, it) }
            .mapNotNull { state.cards[it.id]?.due }
            .filter { it > now }
            .minOrNull()

    /** Через сколько дней вопрос вернётся, по-русски: «через 10 мин», «завтра», «через 5 дн.». */
    fun inWords(fromMs: Long, toMs: Long): String {
        val minutes = (toMs - fromMs) / Fsrs.MINUTE
        val days = Math.round((toMs - fromMs).toDouble() / Fsrs.DAY)
        return when {
            minutes < 60 -> "через ${minutes.coerceAtLeast(1)} мин"
            days <= 1 -> "завтра"
            days < 31 -> "через $days дн."
            days < 365 -> "через ${Math.round(days / 30.0)} мес."
            else -> "через год"
        }
    }
}
