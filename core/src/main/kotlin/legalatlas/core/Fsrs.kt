package legalatlas.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Планировщик FSRS-5 (https://github.com/open-spaced-repetition/fsrs4anki/wiki/The-Algorithm).
 * Для каждого вопроса хранит стабильность S (через сколько дней вероятность вспомнить
 * упадёт до 90%) и сложность D (1..10). Следующий показ назначается на момент,
 * когда вероятность вспомнить опустится до desiredRetention.
 */
class Fsrs(
    private val w: DoubleArray = DEFAULT_WEIGHTS,
    private val desiredRetention: Double = 0.9,
    private val maxIntervalDays: Double = 365.0,
) {
    init {
        require(w.size == 19) { "FSRS-5 needs 19 weights" }
    }

    /** Вероятность вспомнить через [elapsedDays] при стабильности [stability]. */
    fun retrievability(elapsedDays: Double, stability: Double): Double =
        (1 + FACTOR * max(elapsedDays, 0.0) / stability).pow(DECAY)

    fun retrievability(card: CardState, now: Long): Double =
        retrievability(daysBetween(card.lastReview, now), card.stability)

    fun intervalDays(stability: Double): Double {
        val raw = stability / FACTOR * (desiredRetention.pow(1 / DECAY) - 1)
        return raw.coerceIn(1.0, maxIntervalDays)
    }

    /** Первое знакомство с вопросом. */
    fun first(itemId: String, rating: Rating, now: Long): CardState {
        val s = initialStability(rating)
        return CardState(
            itemId = itemId,
            stability = s,
            difficulty = initialDifficulty(rating),
            lastReview = now,
            due = now + scheduleMs(s, rating),
            reps = 1,
            lapses = if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    fun review(card: CardState, rating: Rating, now: Long): CardState {
        val elapsed = daysBetween(card.lastReview, now)
        val d = nextDifficulty(card.difficulty, rating)
        val s = when {
            elapsed < 1.0 -> shortTermStability(card.stability, rating)
            rating == Rating.AGAIN -> forgetStability(card.difficulty, card.stability, retrievability(elapsed, card.stability))
            else -> recallStability(card.difficulty, card.stability, retrievability(elapsed, card.stability), rating)
        }
        return card.copy(
            stability = s,
            difficulty = d,
            lastReview = now,
            due = now + scheduleMs(s, rating),
            reps = card.reps + 1,
            lapses = card.lapses + if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    /** Провал или «трудно» в первый раз — вернуть вопрос в ту же или следующую сессию. */
    private fun scheduleMs(stability: Double, rating: Rating): Long = when (rating) {
        Rating.AGAIN -> 10 * MINUTE
        else -> (intervalDays(stability) * DAY).roundToLong()
    }

    private fun initialStability(r: Rating) = max(w[r.value - 1], 0.1)

    private fun initialDifficulty(r: Rating) =
        (w[4] - exp(w[5] * (r.value - 1)) + 1).coerceIn(1.0, 10.0)

    private fun nextDifficulty(d: Double, r: Rating): Double {
        val delta = -w[6] * (r.value - 3)
        val damped = d + delta * (10 - d) / 9
        val reverted = w[7] * initialDifficulty(Rating.EASY) + (1 - w[7]) * damped
        return reverted.coerceIn(1.0, 10.0)
    }

    private fun recallStability(d: Double, s: Double, r: Double, rating: Rating): Double {
        val hard = if (rating == Rating.HARD) w[15] else 1.0
        val easy = if (rating == Rating.EASY) w[16] else 1.0
        return s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp(w[10] * (1 - r)) - 1) * hard * easy)
    }

    private fun forgetStability(d: Double, s: Double, r: Double): Double {
        val sf = w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r))
        return min(max(sf, 0.1), s)
    }

    private fun shortTermStability(s: Double, rating: Rating) =
        max(s * exp(w[17] * (rating.value - 3 + w[18])), 0.1)

    companion object {
        const val DECAY = -0.5
        const val FACTOR = 19.0 / 81.0
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L

        val DEFAULT_WEIGHTS = doubleArrayOf(
            0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192,
            1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
        )

        fun daysBetween(from: Long, to: Long) = (to - from).toDouble() / DAY
    }
}
