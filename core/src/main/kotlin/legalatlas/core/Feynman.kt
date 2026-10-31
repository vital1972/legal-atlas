package legalatlas.core

/**
 * Метод Фейнмана: после ответа пользователь объясняет, почему ответ именно такой.
 * Ответ и обоснование оцениваются отдельно, итоговая оценка — меньшая из двух.
 */
object Feynman {
    const val CORRECT = "correct"
    const val PARTIAL = "partial"
    const val WRONG = "wrong"
    const val MISSING = "missing"

    /** Обоснование ограничивает оценку сверху: верный ответ с неверным «почему» — это ещё не знание. */
    fun cap(answer: Rating, reasoningStatus: String?): Rating {
        val ceiling = when (reasoningStatus) {
            WRONG, MISSING -> Rating.HARD
            PARTIAL -> Rating.GOOD
            else -> Rating.EASY
        }
        return if (answer.value <= ceiling.value) answer else ceiling
    }

    /** Откуда взято верное обоснование, которое показывается после ответа. */
    enum class Kind { QUOTE, DECK, MINE }

    data class Reference(val kind: Kind, val text: String)

    /**
     * Эталоны по убыванию надёжности: цитата из источника, обоснование из колоды,
     * обоснование, которое пользователь сам сохранил. Объяснение модели сюда не входит:
     * оно приходит вместе с оценкой и помечается отдельно.
     */
    fun references(item: Item, state: AppState): List<Reference> = listOfNotNull(
        item.rationaleQuote?.takeIf { it.isNotBlank() }?.let { Reference(Kind.QUOTE, it) },
        item.rationale?.takeIf { it.isNotBlank() }?.let { Reference(Kind.DECK, it) },
        state.myRationales[item.id]?.takeIf { it.isNotBlank() }?.let { Reference(Kind.MINE, it) },
    )

    fun eligible(item: Item) = Formats.isOpen(item.format)
}
