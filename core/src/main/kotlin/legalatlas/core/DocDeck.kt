package legalatlas.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ответ модели на одну часть документа. */
@Serializable
data class GenBatch(
    @SerialName("new_topics") val newTopics: List<GenTopic> = emptyList(),
    val items: List<GenItem> = emptyList(),
)

/** Новая книга, которую модель предлагает, если вопрос не подходит ни к одной из существующих. */
@Serializable
data class GenTopic(val id: String, val title: String, val world: String? = null)

@Serializable
data class GenItem(
    @SerialName("topic_id") val topicId: String = "",
    val level: String = "fact",
    val format: String = Formats.SELF_CHECK,
    val difficulty: Int = 3,
    val prompt: String = "",
    val answer: String = "",
    @SerialName("key_points") val keyPoints: List<KeyPoint> = emptyList(),
    @SerialName("common_errors") val commonErrors: List<String> = emptyList(),
    val explanation: String? = null,
    @SerialName("why_prompt") val whyPrompt: String? = null,
    val rationale: String? = null,
    @SerialName("rationale_quote") val rationaleQuote: String? = null,
    val locator: String? = null,
    val norms: List<String> = emptyList(),
    val options: List<String> = emptyList(),
    @SerialName("correct_option") val correctOption: Int? = null,
    val pretest: Boolean = false,
)

/** Вопрос-кандидат в предпросмотре: что проверено и добавлять ли его. */
data class Candidate(
    val item: Item,
    /** Книга, в которую он попадёт: существующая или новая. */
    val topicTitle: String,
    val newTopic: Boolean,
    /** Цитата найдена в документе дословно (с точностью до пробелов, кавычек и регистра). */
    val quoteVerified: Boolean,
    /** Похож на вопрос, который уже есть в колодах. */
    val duplicateOf: String? = null,
    /** Требовать подтверждённую цитату. Без исходного документа (углубление трудных вопросов) — нет. */
    val quoteRequired: Boolean = true,
) {
    val recommended get() = (quoteVerified || !quoteRequired) && duplicateOf == null
}

object DocDeck {

    // ---------- Проверка цитат ----------

    /** Нормализация для сравнения: регистр, ё, кавычки, тире, пробелы и переносы. */
    fun normalize(s: String): String = s.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[«»„“”\"'`’‘]"), "")
        .replace(Regex("[‑–—−-]"), "-")
        .replace("­", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Цитата считается подтверждённой, если она (после нормализации) есть в документе целиком.
     * Многоточие внутри цитаты допускается: тогда в документе должен найтись каждый кусок по порядку.
     */
    fun quoteFound(quote: String?, normalizedDoc: String): Boolean {
        val q = quote?.let(::normalize)?.trim(' ', '.', ',', ';') ?: return false
        val pieces = q.split(Regex("\\s*(\\.\\.\\.|…|\\[\\.\\.\\.\\])\\s*")).map { it.trim(' ', '.', ',', ';') }.filter { it.isNotEmpty() }
        if (pieces.sumOf { it.length } < MIN_QUOTE) return false
        var from = 0
        for (p in pieces) {
            val at = normalizedDoc.indexOf(p, from)
            if (at < 0) return false
            from = at + p.length
        }
        return true
    }

    // ---------- Похожие вопросы ----------

    private fun words(s: String) = normalize(s).split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length > 2 }
        .map { it.take(6) }.toSet()

    /** Доля общих слов (по основам из 6 букв): грубо, но без сети и без ошибок в сторону «всё дубли». */
    fun similarity(a: String, b: String): Double {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return (wa intersect wb).size.toDouble() / (wa union wb).size
    }

    // ---------- Сборка колоды ----------

    /** Описание существующих книг для модели: id, название, отрасль и сколько вопросов. */
    fun existingTopics(state: AppState): List<GenTopic> =
        state.topics.values.map { GenTopic(it.id, it.title, it.world) }

    /**
     * Превращает ответы модели в кандидатов: присваивает постоянные id, сводит новые книги,
     * проверяет цитаты и ищет похожие вопросы среди уже загруженных и уже созданных.
     */
    fun candidates(
        state: AppState,
        deckId: String,
        docTitle: String,
        docText: String,
        batches: List<GenBatch>,
        today: String,
        quoteRequired: Boolean = true,
    ): Pair<List<Candidate>, List<Topic>> {
        val normalizedDoc = normalize(docText)
        val existing = state.topics
        val newTopics = linkedMapOf<String, Topic>()
        // id новых книг от модели -> наш id; одинаковые названия сводятся в одну книгу.
        val topicMap = mutableMapOf<String, String>()
        val out = mutableListOf<Candidate>()
        val known = state.items.values.map { it.id to it.prompt }.toMutableList()
        var n = 0
        for (batch in batches) {
            for (t in batch.newTopics) {
                if (t.id in existing) { topicMap[t.id] = t.id; continue }
                val sameTitle = (existing.values + newTopics.values).firstOrNull { normalize(it.title) == normalize(t.title) }
                topicMap[t.id] = sameTitle?.id ?: run {
                    val id = "$deckId-t${newTopics.size + 1}"
                    newTopics[id] = Topic(id = id, title = t.title.trim().take(60), world = t.world?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_WORLD)
                    id
                }
            }
            for (g in batch.items) {
                if (g.prompt.isBlank() || g.answer.isBlank()) continue
                val topicId = topicMap[g.topicId] ?: g.topicId.takeIf { it in existing || it in newTopics } ?: continue
                val format = when {
                    g.format == Formats.CHOICE && (g.options.size < 2 || g.correctOption !in g.options.indices) -> Formats.SELF_CHECK
                    g.format in KNOWN_FORMATS -> g.format
                    else -> Formats.SELF_CHECK
                }
                n++
                val id = "$deckId-%03d".format(n)
                val verified = quoteFound(g.rationaleQuote, normalizedDoc)
                val item = Item(
                    id = id,
                    topicId = topicId,
                    level = g.level,
                    format = format,
                    difficulty = g.difficulty.coerceIn(1, 5),
                    prompt = g.prompt.trim(),
                    answer = g.answer.trim(),
                    keyPoints = g.keyPoints,
                    commonErrors = g.commonErrors,
                    explanation = g.explanation,
                    whyPrompt = g.whyPrompt,
                    rationale = g.rationale,
                    // Неподтверждённая цитата не показывается как цитата: это могла быть выдумка модели.
                    rationaleQuote = g.rationaleQuote?.trim()?.takeIf { verified },
                    options = if (format == Formats.CHOICE) g.options else emptyList(),
                    correctOption = if (format == Formats.CHOICE) g.correctOption else null,
                    source = Source(title = docTitle, locator = g.locator?.takeIf { it.isNotBlank() }),
                    norms = g.norms,
                    pretest = g.pretest,
                    validAsOf = today,
                )
                val dup = known.firstOrNull { similarity(it.second, item.prompt) >= DUPLICATE_SIMILARITY }?.first
                known += id to item.prompt
                out += Candidate(
                    item = item,
                    topicTitle = existing[topicId]?.title ?: newTopics[topicId]?.title ?: topicId,
                    newTopic = topicId in newTopics,
                    quoteVerified = verified,
                    duplicateOf = dup,
                    quoteRequired = quoteRequired,
                )
            }
        }
        return out to newTopics.values.toList()
    }

    /**
     * Колода из выбранных вопросов. Существующие книги, в которые попали вопросы, копируются
     * в неё с тем же id: так колода проходит проверку, а вопросы встают в ту же книгу.
     */
    fun build(
        state: AppState,
        deckId: String,
        docTitle: String,
        chosen: List<Item>,
        newTopics: List<Topic>,
    ): DeckFile {
        val used = chosen.map { it.topicId }.toSet()
        val copies = used.mapNotNull { state.topics[it] }
        val fresh = newTopics.filter { it.id in used }
        val worlds = (copies + fresh).mapNotNull { it.world }
        val world = worlds.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: DEFAULT_WORLD
        return DeckFile(
            deck = DeckInfo(id = deckId, title = docTitle.take(80), world = world, description = "Создано из документа «$docTitle»"),
            topics = copies + fresh,
            items = chosen,
        )
    }

    private val KNOWN_FORMATS = setOf(Formats.FREE_RECALL, Formats.SELF_CHECK, Formats.CLOZE, Formats.CHOICE, Formats.CASE_SCENARIO)
    const val DEFAULT_WORLD = "Из документов"
    private const val MIN_QUOTE = 25
    private const val DUPLICATE_SIMILARITY = 0.6
}
