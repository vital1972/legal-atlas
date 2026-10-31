package legalatlas.core

import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsrsTest {
    private val fsrs = Fsrs()
    private val t0 = 1_760_000_000_000L

    @Test
    fun retrievabilityIsNinetyPercentAfterStabilityDays() {
        assertEquals(0.9, fsrs.retrievability(10.0, 10.0), 1e-9)
    }

    @Test
    fun intervalEqualsStabilityAtNinetyPercent() {
        assertEquals(10.0, fsrs.intervalDays(10.0), 1e-6)
    }

    @Test
    fun betterRatingsGiveLongerIntervals() {
        val hard = fsrs.first("a", Rating.HARD, t0)
        val good = fsrs.first("a", Rating.GOOD, t0)
        val easy = fsrs.first("a", Rating.EASY, t0)
        assertTrue(hard.due <= good.due && good.due < easy.due)
        assertTrue(easy.difficulty < hard.difficulty)
    }

    @Test
    fun successfulReviewOnTimeGrowsStability() {
        val card = fsrs.first("a", Rating.GOOD, t0)
        val next = fsrs.review(card, Rating.GOOD, card.due)
        assertTrue(next.stability > card.stability * 2, "stability ${card.stability} -> ${next.stability}")
        assertTrue(next.due > card.due)
    }

    @Test
    fun lapseShrinksStabilityAndReturnsSoon() {
        var card = fsrs.first("a", Rating.GOOD, t0)
        card = fsrs.review(card, Rating.GOOD, card.due)
        val lapsed = fsrs.review(card, Rating.AGAIN, card.due)
        assertTrue(lapsed.stability < card.stability)
        assertEquals(lapsed.lastReview + 10 * Fsrs.MINUTE, lapsed.due)
        assertEquals(1, lapsed.lapses)
    }
}

class DecksTest {
    private val sample = File("../examples/sample-deck.json").readText()

    @Test
    fun sampleDeckIsValid() {
        val check = Decks.parse(sample)
        assertTrue(check.ok, check.errors.joinToString())
        assertTrue(check.warnings.isEmpty(), check.warnings.joinToString())
        assertEquals(10, check.deck!!.items.size)
    }

    @Test
    fun brokenReferencesAreReported() {
        val bad = """{"deck":{"id":"d","title":"t","world":"w"},"topics":[{"id":"t1","title":"T"}],
            "items":[{"id":"i1","topic_id":"nope","prompt":"p","answer":"a"},
                     {"id":"i2","topic_id":"t1","format":"choice","prompt":"p","answer":"a","options":["x"],"correct_option":3}]}"""
        val check = Decks.parse(bad)
        assertTrue(!check.ok)
        assertTrue(check.errors.any { "nope" in it })
        assertTrue(check.errors.any { "два варианта" in it })
    }

    @Test
    fun notJsonIsAnError() {
        assertTrue(!Decks.parse("hello").ok)
    }

    @Test
    fun reimportKeepsProgressAndDropsRemovedItems() {
        val deck = Decks.parse(sample).deck!!
        var state = Decks.import(AppState(), deck).state
        val item = deck.items.first()
        state = Progress.record(state, item, Mode.NEW, Rating.GOOD, Confidence.SURE, 1000, 1_760_000_000_000L)
        val trimmed = deck.copy(items = deck.items.drop(1).map { if (it.id == deck.items[1].id) it.copy(prompt = "new text") else it })
        val result = Decks.import(state, trimmed)
        assertEquals(1, result.removed)
        assertEquals(9, result.updated)
        assertTrue(item.id !in result.state.cards)
        assertEquals("new text", result.state.items[deck.items[1].id]!!.prompt)
    }
}

class PlannerTest {
    private val deck = Decks.parse(File("../examples/sample-deck.json").readText()).deck!!
    private val planner = Planner(ZoneOffset.UTC)
    private val now = 1_760_000_000_000L

    @Test
    fun lockedTopicsGiveNoNewItems() {
        val state = Decks.import(AppState(), deck).state
        assertTrue(planner.plan(state, now, 30).isEmpty())
    }

    @Test
    fun newItemsRespectDailyLimitAndBudget() {
        val state = Decks.import(AppState(), deck).state.let {
            it.copy(unlockedTopics = deck.topics.map { t -> t.id }.toSet(), settings = it.settings.copy(newPerDay = 3))
        }
        assertEquals(3, planner.plan(state, now, 30).size)
        val short = planner.plan(state.copy(settings = state.settings.copy(newPerDay = 50)), now, 2)
        assertTrue(short.sumOf { Planner.secondsFor(it.item) } <= 120.0)
    }

    @Test
    fun dueReviewsComeFirstAndWeakestFirst() {
        var state = Decks.import(AppState(), deck).state.copy(unlockedTopics = deck.topics.map { it.id }.toSet())
        val (a, b) = deck.items.take(2)
        state = Progress.record(state, a, Mode.NEW, Rating.GOOD, Confidence.SURE, 1000, now)
        state = Progress.record(state, b, Mode.NEW, Rating.HARD, Confidence.THINK, 1000, now)
        val later = now + 5 * Fsrs.DAY
        val plan = planner.plan(state, later, 30)
        val reviews = plan.filter { it.mode == Mode.REVIEW }.map { it.item.id }
        assertEquals(setOf(a.id, b.id), reviews.toSet())
        assertEquals(Mode.REVIEW, plan.first().mode)
    }

    @Test
    fun feynmanItemsTakeAtMostAThirdOfTheSession() {
        val state = Decks.import(AppState(), deck).state.let {
            it.copy(unlockedTopics = deck.topics.map { t -> t.id }.toSet(), settings = it.settings.copy(newPerDay = 50))
        }
        val plan = planner.plan(state, now, 30)
        val why = plan.filter { it.feynman }
        assertTrue(why.isNotEmpty(), "в примере есть развёрнутые вопросы")
        assertTrue(why.all { Feynman.eligible(it.item) })
        assertTrue(why.size * Planner.FEYNMAN_SECONDS <= 30 * 60 / 3.0)
        val off = planner.plan(state.copy(settings = state.settings.copy(feynman = false)), now, 30)
        assertTrue(off.none { it.feynman })
    }

    @Test
    fun interleaveAvoidsSameTopicInARow() {
        val items = deck.items.map { PlannedItem(it, Mode.NEW) }
        val out = planner.interleave(items)
        val repeats = out.zipWithNext().count { (x, y) -> x.item.topicId == y.item.topicId }
        val before = items.zipWithNext().count { (x, y) -> x.item.topicId == y.item.topicId }
        assertTrue(repeats < before)
        assertEquals(items.size, out.size)
    }

    @Test
    fun pretestPrefersMarkedItems() {
        val state = Decks.import(AppState(), deck).state
        val pre = planner.pretest(state, "limitation-general", 2)
        assertTrue(pre.all { it.item.pretest && it.mode == Mode.PRETEST })
    }

    @Test
    fun pretestDoesNotSchedule() {
        val state = Decks.import(AppState(), deck).state
        val next = Progress.record(state, deck.items.first(), Mode.PRETEST, Rating.AGAIN, Confidence.GUESS, 1000, now)
        assertTrue(next.cards.isEmpty())
        assertEquals(1, next.logs.size)
    }
}

class HexLayoutTest {
    @Test
    fun neighborsArePlacedAdjacentAndCellsAreUnique() {
        val topics = listOf(
            Topic("a", "A", neighbors = listOf("b", "c")),
            Topic("b", "B"),
            Topic("c", "C"),
            Topic("d", "D", neighbors = listOf("c")),
            Topic("e", "E"),
        )
        val layout = HexLayout.layout(topics)
        assertEquals(topics.size, layout.values.toSet().size)
        assertEquals(1, layout["a"]!!.distance(layout["b"]!!))
        assertEquals(1, layout["c"]!!.distance(layout["d"]!!))
    }
}

class ProgressTest {
    @Test
    fun masteryCountsUnstudiedAsZero() {
        val deck = Decks.parse(File("../examples/sample-deck.json").readText()).deck!!
        var state = Decks.import(AppState(), deck).state
        val now = 1_760_000_000_000L
        state = Progress.record(state, deck.items.first(), Mode.NEW, Rating.GOOD, Confidence.SURE, 1000, now)
        val general = Progress.topics(state, now).first { it.topic.id == "limitation-general" }
        assertTrue(general.unlocked)
        assertEquals(1, general.introduced)
        assertEquals(1.0 / general.total, general.mastery, 1e-6)
        assertEquals("Гражданское право", general.topic.world)
    }
}

class FeynmanTest {
    @Test
    fun reasoningCapsTheRating() {
        assertEquals(Rating.EASY, Feynman.cap(Rating.EASY, Feynman.CORRECT))
        assertEquals(Rating.GOOD, Feynman.cap(Rating.EASY, Feynman.PARTIAL))
        assertEquals(Rating.HARD, Feynman.cap(Rating.GOOD, Feynman.WRONG))
        assertEquals(Rating.HARD, Feynman.cap(Rating.EASY, Feynman.MISSING))
        assertEquals(Rating.AGAIN, Feynman.cap(Rating.AGAIN, Feynman.CORRECT))
        assertEquals(Rating.GOOD, Feynman.cap(Rating.GOOD, null))
    }

    @Test
    fun referencesGoFromQuoteToOwn() {
        val item = Item(id = "i", topicId = "t", prompt = "?", answer = "!", rationale = "потому что", rationaleQuote = "«ст. 1»")
        val state = AppState(myRationales = mapOf("i" to "моё"))
        assertEquals(
            listOf(Feynman.Kind.QUOTE, Feynman.Kind.DECK, Feynman.Kind.MINE),
            Feynman.references(item, state).map { it.kind },
        )
        assertTrue(Feynman.references(item.copy(rationale = null, rationaleQuote = null), AppState()).isEmpty())
    }
}
