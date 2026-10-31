package legalatlas.core

/**
 * Книга на полке: сколько закладок она заслужила.
 * 0 — закрыта, разведки не было; 1 — разведка пройдена; 2 — каждый вопрос пройден хотя бы раз;
 * 3 — вспомните не меньше 70%; 4 — не меньше 85% и обоснования в основном верные;
 * 5 — не меньше 90% и у большинства вопросов интервал больше месяца (книга прочитана).
 * Последняя закладка бледнеет, когда до порога осталось меньше 5 пунктов: тема начинает забываться.
 */
data class Book(
    val mastery: TopicMastery,
    val tabs: Int,
    val fading: Boolean,
) {
    val locked get() = tabs == 0
    val read get() = tabs == 5
}

object Bookmarks {
    private val thresholds = mapOf(3 to 0.70, 4 to 0.85, 5 to 0.90)
    private const val FADE_MARGIN = 0.05
    private const val LONG_INTERVAL_DAYS = 30.0
    private const val GOOD_REASONING_SHARE = 0.6

    fun books(state: AppState, now: Long): List<Book> {
        val logsByItem = state.logs.groupBy { it.itemId }
        return Progress.topics(state, now).map { tm -> book(tm, state, logsByItem, now) }
    }

    private fun book(tm: TopicMastery, state: AppState, logsByItem: Map<String, List<ReviewLog>>, now: Long): Book {
        if (!tm.unlocked || tm.total == 0) return Book(tm, 0, false)
        val items = state.items.values.filter { it.topicId == tm.topic.id && it.id !in state.flaggedItems }
        val cards = items.mapNotNull { state.cards[it.id] }
        val m = tm.mastery

        val reasoning = items.flatMap { logsByItem[it.id].orEmpty() }.mapNotNull { it.reasoningStatus }.takeLast(10)
        val reasoningOk = reasoning.isEmpty() || reasoning.count { it == Feynman.CORRECT } >= reasoning.size * GOOD_REASONING_SHARE
        val longIntervals = cards.count { (it.due - it.lastReview) / Fsrs.DAY.toDouble() > LONG_INTERVAL_DAYS }

        var tabs = 1
        if (tm.introduced >= tm.total) tabs = 2
        if (tabs == 2 && m >= thresholds.getValue(3)) tabs = 3
        if (tabs == 3 && m >= thresholds.getValue(4) && reasoningOk) tabs = 4
        if (tabs == 4 && m >= thresholds.getValue(5) && longIntervals * 3 >= cards.size * 2) tabs = 5
        val fading = tabs >= 3 && m < thresholds.getValue(tabs) + FADE_MARGIN
        return Book(tm, tabs, fading)
    }
}
