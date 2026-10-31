package legalatlas.core

/**
 * Переработка колод по результатам: что даётся легко и можно убрать из повторений,
 * а что даётся трудно и требует новых вопросов с другой стороны.
 */
object Rework {

    data class Weak(
        val item: Item,
        /** Почему вопрос попал в трудные: для показа и для промпта. */
        val reasons: List<String>,
        /** Последние неверные ответы пользователя: модель увидит, в чём именно путаница. */
        val wrongAnswers: List<String>,
        val score: Double,
    )

    data class Analysis(val easy: List<Item>, val weak: List<Weak>)

    fun analyze(state: AppState, now: Long, maxWeak: Int = 15): Analysis {
        val fsrs = Fsrs(desiredRetention = state.settings.desiredRetention)
        val logsByItem = state.logs.filter { it.mode != Mode.PRETEST.name }.groupBy { it.itemId }
        val easy = mutableListOf<Item>()
        val weak = mutableListOf<Weak>()
        for (item in state.items.values) {
            if (item.id in state.flaggedItems || item.id in state.retiredItems) continue
            val card = state.cards[item.id] ?: continue
            val logs = logsByItem[item.id].orEmpty().sortedBy { it.ts }
            val last = logs.takeLast(3)

            if (card.reps >= EASY_MIN_REPS && card.lapses == 0 && card.stability >= EASY_STABILITY_DAYS &&
                last.size == 3 && last.all { it.rating >= Rating.GOOD.value } &&
                last.none { it.reasoningStatus == Feynman.WRONG || it.reasoningStatus == Feynman.MISSING }
            ) {
                easy += item
                continue
            }

            val reasons = mutableListOf<String>()
            var score = 0.0
            if (card.lapses >= 2) { reasons += "забывался ${card.lapses} раза"; score += card.lapses }
            val confident = logs.count { it.rating == Rating.AGAIN.value && it.confidence == Confidence.SURE }
            if (confident > 0) { reasons += "уверенная ошибка"; score += 2.0 * confident }
            val r = fsrs.retrievability(card, now)
            if (card.reps >= 3 && r < WEAK_RECALL) { reasons += "вспомните сейчас с вероятностью ${(r * 100).toInt()}%"; score += 1.0 }
            val badWhy = last.count { it.reasoningStatus == Feynman.WRONG || it.reasoningStatus == Feynman.MISSING }
            if (badWhy >= 2) { reasons += "не получается обосновать"; score += 1.5 }
            if (reasons.isNotEmpty()) {
                val wrong = logs.filter { it.rating <= Rating.HARD.value && !it.answer.isNullOrBlank() }
                    .takeLast(2).mapNotNull { it.answer }
                weak += Weak(item, reasons, wrong, score)
            }
        }
        return Analysis(easy = easy, weak = weak.sortedByDescending { it.score }.take(maxWeak))
    }

    private const val EASY_MIN_REPS = 4
    private const val EASY_STABILITY_DAYS = 60.0
    private const val WEAK_RECALL = 0.7
}
