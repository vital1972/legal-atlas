package legalatlas.core

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

enum class Mode { REVIEW, NEW, AHEAD, PRETEST }

data class PlannedItem(val item: Item, val mode: Mode)

/**
 * Собирает сессию под выбранное время: сначала то, что вот-вот забудется,
 * затем новое из открытых областей (не больше дневного лимита), а если время
 * осталось — досрочное повторение самых слабых вопросов.
 */
class Planner(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun plan(state: AppState, now: Long, budgetMinutes: Int): List<PlannedItem> {
        val fsrs = Fsrs(desiredRetention = state.settings.desiredRetention)
        val budget = budgetMinutes * 60.0
        var used = 0.0
        val picked = mutableListOf<PlannedItem>()
        val usable = state.items.values.filter { it.id !in state.flaggedItems }

        val withCards = usable.mapNotNull { item -> state.cards[item.id]?.let { item to it } }
        val due = withCards.filter { it.second.due <= now }
            .sortedBy { fsrs.retrievability(it.second, now) }
        for ((item, _) in due) {
            if (used + secondsFor(item) > budget * REVIEW_SHARE && picked.isNotEmpty()) break
            picked += PlannedItem(item, Mode.REVIEW)
            used += secondsFor(item)
        }

        val newLeft = state.settings.newPerDay - introducedToday(state, now)
        if (newLeft > 0) {
            val fresh = orderedItems(state)
                .filter { it.id !in state.cards && it.id !in state.flaggedItems && it.topicId in state.unlockedTopics }
            for (item in fresh.take(newLeft)) {
                if (used + secondsFor(item) > budget) break
                picked += PlannedItem(item, Mode.NEW)
                used += secondsFor(item)
            }
        }

        if (used < budget * AHEAD_THRESHOLD) {
            val pickedIds = picked.map { it.item.id }.toSet()
            val ahead = withCards
                .filter { it.first.id !in pickedIds && it.second.lastReview < now - MIN_AHEAD_GAP }
                .map { it to fsrs.retrievability(it.second, now) }
                .filter { it.second < AHEAD_MAX_R }
                .sortedBy { it.second }
            for ((pair, _) in ahead) {
                if (used + secondsFor(pair.first) > budget) break
                picked += PlannedItem(pair.first, Mode.AHEAD)
                used += secondsFor(pair.first)
            }
        }
        return interleave(picked)
    }

    /** Вопросы для «разведки» закрытой области: сначала помеченные pretest. */
    fun pretest(state: AppState, topicId: String, count: Int = 5): List<PlannedItem> =
        orderedItems(state).filter { it.topicId == topicId && it.id !in state.flaggedItems }
            .sortedByDescending { it.pretest }
            .take(count)
            .map { PlannedItem(it, Mode.PRETEST) }

    fun introducedToday(state: AppState, now: Long): Int {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return state.logs.count {
            it.mode == Mode.NEW.name && Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() == today
        }
    }

    fun minutesThisWeek(state: AppState, now: Long): Int {
        val date = Instant.ofEpochMilli(now).atZone(zone)
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val year = date.get(IsoFields.WEEK_BASED_YEAR)
        val ms = state.logs.filter {
            val d = Instant.ofEpochMilli(it.ts).atZone(zone)
            d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == week && d.get(IsoFields.WEEK_BASED_YEAR) == year
        }.sumOf { it.elapsedMs.coerceAtMost(MAX_COUNTED_MS) }
        return (ms / 60_000).toInt()
    }

    fun dueCount(state: AppState, now: Long): Int =
        state.cards.values.count { it.due <= now && it.itemId in state.items && it.itemId !in state.flaggedItems }

    private fun orderedItems(state: AppState): List<Item> =
        state.decks.values.sortedBy { it.deck.title }.flatMap { it.items }

    /** Соседние вопросы по возможности из разных тем — это и есть перемешивание. */
    internal fun interleave(items: List<PlannedItem>): List<PlannedItem> {
        val pool = items.toMutableList()
        val out = mutableListOf<PlannedItem>()
        while (pool.isNotEmpty()) {
            val lastTopic = out.lastOrNull()?.item?.topicId
            val idx = pool.indexOfFirst { it.item.topicId != lastTopic }.takeIf { it >= 0 } ?: 0
            out += pool.removeAt(idx)
        }
        return out
    }

    companion object {
        private const val REVIEW_SHARE = 0.8
        private const val AHEAD_THRESHOLD = 0.6
        private const val AHEAD_MAX_R = 0.95
        private val MIN_AHEAD_GAP = ChronoUnit.HOURS.duration.toMillis() * 12
        private const val MAX_COUNTED_MS = 5 * 60_000L

        fun secondsFor(item: Item): Double = when {
            Formats.isOpen(item.format) -> 100.0
            else -> 25.0
        }
    }
}
