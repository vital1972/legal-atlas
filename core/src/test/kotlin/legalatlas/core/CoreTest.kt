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

class DocTextTest {
    @Test
    fun docxParagraphsAreExtracted() {
        val xml = """<?xml version="1.0"?><w:document><w:body>
            <w:p><w:r><w:t>Статья 740.</w:t></w:r><w:r><w:t xml:space="preserve"> Договор строительного подряда</w:t></w:r></w:p>
            <w:p><w:r><w:t>1. По договору &amp; подрядчик</w:t></w:r></w:p></w:body></w:document>"""
        val bytes = java.io.ByteArrayOutputStream().also { bos ->
            java.util.zip.ZipOutputStream(bos).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        assertEquals("Статья 740. Договор строительного подряда\n1. По договору & подрядчик", DocText.extract("гл37.docx", bytes))
    }

    @Test
    fun rtfCyrillicAndUnicodeAreDecoded() {
        val rtf = "{\\rtf1\\ansi\\ansicpg1251{\\fonttbl{\\f0 Times;}}{\\*\\generator x;}" +
            "\\pard \\'d1\\'f2\\'e0\\'f2\\'fc\\'ff 740.\\par \\u1055?\\u1086?\\u1076?\\u1088?\\u1103?\\u1076?}"
        assertEquals("Статья 740.\nПодряд", DocText.extract("doc.rtf", rtf.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun splitKeepsAllTextInRoughlyEqualParts() {
        val text = (1..40).joinToString("\n") { "Абзац $it " + "текст ".repeat(20) }
        val parts = DocText.split(text, 4)
        assertEquals(4, parts.size)
        assertEquals(text.replace("\n", "").replace(" ", ""), parts.joinToString("").replace("\n", "").replace(" ", ""))
    }
}

class DocDeckTest {
    private val doc = "Статья 740. Договор строительного подряда\n1. По договору строительного подряда подрядчик " +
        "обязуется в установленный договором срок построить по заданию заказчика определенный объект."

    @Test
    fun quotesAreVerifiedLooselyButNotInvented() {
        val norm = DocDeck.normalize(doc)
        assertTrue(DocDeck.quoteFound("«подрядчик обязуется в установленный договором  срок построить»", norm))
        assertTrue(DocDeck.quoteFound("подрядчик обязуется … построить по заданию заказчика", norm))
        assertTrue(!DocDeck.quoteFound("подрядчик обязуется в разумный срок построить объект", norm))
        assertTrue(!DocDeck.quoteFound("подряд", norm))
        assertTrue(!DocDeck.quoteFound(null, norm))
    }

    @Test
    fun candidatesGetIdsTopicsAndChecks() {
        val base = Decks.import(AppState(), Decks.parse(File("../examples/sample-deck.json").readText()).deck!!).state
        val existingTopic = base.topics.keys.first()
        val existingPrompt = base.items.values.first { it.topicId == existingTopic }.prompt
        val batches = listOf(
            GenBatch(
                newTopics = listOf(GenTopic("n1", "Строительный подряд", "Гражданское право")),
                items = listOf(
                    GenItem(topicId = "n1", prompt = "Что обязуется сделать подрядчик?", answer = "Построить объект",
                        rationaleQuote = "подрядчик обязуется в установленный договором срок построить"),
                    GenItem(topicId = existingTopic, prompt = existingPrompt, answer = "то же", rationaleQuote = "выдуманная цитата из головы модели"),
                    GenItem(topicId = "нет такой", prompt = "?", answer = "!"),
                ),
            ),
            GenBatch(newTopics = listOf(GenTopic("x", "строительный  подряд")), items = listOf(
                GenItem(topicId = "x", format = Formats.CHOICE, options = listOf("один"), prompt = "Срок?", answer = "Договорный"),
            )),
        )
        val (cands, topics) = DocDeck.candidates(base, "doc1", "Глава 37", doc, batches, "2026-10-31")
        assertEquals(3, cands.size)
        assertEquals(1, topics.size, "одинаковые названия сводятся в одну книгу")
        assertEquals(listOf("doc1-001", "doc1-002", "doc1-003"), cands.map { it.item.id })
        assertTrue(cands[0].recommended && cands[0].newTopic)
        assertTrue(!cands[1].quoteVerified && cands[1].item.rationaleQuote == null && cands[1].duplicateOf != null)
        assertEquals(topics[0].id, cands[2].item.topicId)
        assertEquals(Formats.SELF_CHECK, cands[2].item.format, "choice без вариантов становится self_check")

        val deck = DocDeck.build(base, "doc1", "Глава 37", cands.map { it.item }, topics)
        val check = Decks.parse(AtlasJson.encodeToString(DeckFile.serializer(), deck))
        assertTrue(check.ok, check.errors.joinToString())
        val merged = Decks.import(base, deck).state
        assertEquals(base.topics[existingTopic]!!.world, merged.topics[existingTopic]!!.world)
    }
}
