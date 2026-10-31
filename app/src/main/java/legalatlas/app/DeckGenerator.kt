package legalatlas.app

import java.io.IOException
import kotlin.math.ceil
import kotlin.math.roundToInt
import legalatlas.core.AtlasJson
import legalatlas.core.DocText
import legalatlas.core.GenBatch
import legalatlas.core.GenTopic

/**
 * Создание вопросов по документу через DeepSeek. Документ делится на части; каждая часть —
 * отдельный запрос, в который передаются уже существующие книги, чтобы модель раскладывала
 * вопросы по ним, а новую книгу заводила только для новой темы.
 */
class DeckGenerator(apiKey: String) {
    private val client = DeepSeek(apiKey)

    data class Plan(val parts: List<String>, val perPart: Int, val truncated: Boolean)

    suspend fun generate(
        plan: Plan,
        docTitle: String,
        focus: String,
        existing: List<GenTopic>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<GenBatch> {
        val known = existing.toMutableList()
        val out = mutableListOf<GenBatch>()
        plan.parts.forEachIndexed { i, part ->
            onProgress(i, plan.parts.size)
            val content = client.json(
                system = SYSTEM,
                user = userPrompt(docTitle, focus, part, i + 1, plan.parts.size, plan.perPart, known),
                maxTokens = 8000,
                timeoutMs = 180_000,
            )
            val batch = runCatching { AtlasJson.decodeFromString(GenBatch.serializer(), content) }
                .getOrElse { throw IOException("DeepSeek вернул ответ не по схеме в части ${i + 1}") }
            out += batch
            // Новые книги из этой части доступны следующим частям под теми же id.
            known += batch.newTopics.filter { t -> known.none { it.id == t.id } }
        }
        onProgress(plan.parts.size, plan.parts.size)
        return out
    }

    companion object {
        private const val PART_CHARS = 15_000
        private const val MAX_PARTS = 12
        private const val MAX_PER_PART = 8

        /** Сколько частей и вопросов на часть: длинный документ режется, большой объём — тоже. */
        fun plan(text: String, total: Int): Plan {
            val byLength = ceil(text.length / PART_CHARS.toDouble()).toInt()
            val byCount = ceil(total / MAX_PER_PART.toDouble()).toInt()
            val parts = maxOf(1, byLength, byCount).coerceAtMost(MAX_PARTS)
            val limit = parts * PART_CHARS * 2
            val truncated = text.length > limit
            val used = if (truncated) text.take(limit) else text
            val perPart = (total / parts.toDouble()).roundToInt().coerceIn(2, MAX_PER_PART)
            return Plan(DocText.split(used, parts), perPart, truncated)
        }

        private val SYSTEM = """
            Ты готовишь вопросы для тренажёра интервального повторения опытного российского юриста.
            Источник вопросов — только присланный фрагмент документа. Не добавляй сведения, которых в нём нет.

            Правила:
            1. Один вопрос — одна мысль. Не спрашивай азы, но проверяй точное знание норм, сроков, условий,
               исключений и позиций судов из фрагмента.
            2. Смесь: около половины free_recall (свободный ответ), остальное self_check, cloze и choice.
               Для 1–2 сложных мест можно case_scenario (короткая ситуация).
            3. Для free_recall и case_scenario обязательно 2–5 key_points: {"id","text","weight" 1–2,"required"}.
            4. rationale_quote — ДОСЛОВНАЯ цитата из фрагмента (одно-два предложения, до 300 знаков), на которой
               держится ответ. Копируй символ в символ, не пересказывай. Без такой цитаты вопрос не создавай.
            5. rationale — почему ответ именно такой: 2–3 предложения своими словами по фрагменту.
            6. why_prompt — короткий вопрос «почему…» к free_recall и case_scenario.
            7. locator — где во фрагменте: статья, пункт, номер позиции обзора. Только если это видно в тексте.
            8. Книги (темы). Тебе дан список существующих книг. Клади вопрос в подходящую существующую книгу
               (topic_id = её id). Новую книгу заводи в new_topics только для темы, которой нет в списке:
               короткое название до 6 слов, world — отрасль права («Гражданское право», «Арбитражный процесс» и т. п.).
            9. pretest: true у 1–2 вопросов на каждую новую книгу: простые, но неочевидные.
            10. choice: 3–4 правдоподобных варианта, correct_option — индекс верного с нуля.
        """.trimIndent()

        private val SHAPE = """
            Ответь только JSON-объектом:
            {"new_topics": [{"id": "n1", "title": "Название", "world": "Отрасль"}],
             "items": [{"topic_id": "id книги", "level": "fact|understand|apply|distinguish",
               "format": "free_recall|self_check|cloze|choice|case_scenario", "difficulty": 3,
               "prompt": "Вопрос", "answer": "Эталонный ответ со ссылкой на норму из фрагмента",
               "key_points": [{"id": "kp1", "text": "Тезис", "weight": 2, "required": true}],
               "common_errors": ["..."], "why_prompt": "Почему…?", "rationale": "...",
               "rationale_quote": "дословно из фрагмента", "locator": "ст. 740, п. 1",
               "norms": ["ГК РФ ст. 740"], "options": [], "correct_option": null, "pretest": false}]}
        """.trimIndent()

        private fun userPrompt(
            docTitle: String,
            focus: String,
            part: String,
            index: Int,
            total: Int,
            count: Int,
            topics: List<GenTopic>,
        ): String = listOfNotNull(
            "Документ: «$docTitle», часть $index из $total.",
            focus.takeIf { it.isNotBlank() }?.let { "На чём сделать акцент: $it" },
            "Сколько вопросов: около $count (меньше, если во фрагменте мало содержательного).",
            "Существующие книги:\n" + topics.joinToString("\n") { "- ${it.id}: ${it.title} (${it.world ?: "—"})" }
                .ifEmpty { "- (пока нет)" },
            "Фрагмент:\n<document>\n$part\n</document>",
            SHAPE,
        ).joinToString("\n\n")
    }
}
