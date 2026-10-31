package legalatlas.app

import java.io.IOException
import legalatlas.core.AtlasJson
import legalatlas.core.Feynman
import legalatlas.core.Item

/** Оценка развёрнутого ответа прямо с телефона, без сервера: DeepSeek по ключу из настроек. */
class Grader(private val apiKey: String, private val model: String = "deepseek-chat") {

    /**
     * reasoning — обоснование пользователя по Фейнману (или null, если его не просили);
     * references — эталонные обоснования из колоды и сохранённые пользователем.
     */
    suspend fun grade(
        item: Item,
        answer: String,
        reasoning: String? = null,
        references: List<Feynman.Reference> = emptyList(),
    ): GradeResult {
        val why = reasoning != null
        val content = DeepSeek(apiKey, model).json(
            system = if (why) "$SYSTEM\n\n$WHY_RULES\n\n$WHY_JSON_SHAPE" else "$SYSTEM\n\n$JSON_SHAPE",
            user = userPrompt(item, answer.take(8000), reasoning?.take(6000), references),
            maxTokens = 2000,
        )
        val result = runCatching { AtlasJson.decodeFromString(GradeResult.serializer(), content) }
            .getOrElse { throw IOException("DeepSeek вернул ответ не по схеме") }
        val reasoningGrade = if (!why) null else (result.reasoning ?: ReasoningGrade()).let {
            // Пустое обоснование — всегда missing, что бы ни ответила модель.
            if (reasoning.isNullOrBlank()) it.copy(status = Feynman.MISSING)
            else it.copy(status = it.status.takeIf { s -> s in STATUSES } ?: Feynman.PARTIAL)
        }
        return result.copy(
            score = result.score.coerceIn(0.0, 1.0),
            rating = result.rating.coerceIn(1, 4),
            reasoning = reasoningGrade,
        )
    }

    companion object {
        // Та же рубрика, что на сервере (server/src/grade.ts): оценки не зависят от того, кто вызывает модель.
        private val SYSTEM = """
            Ты проверяешь ответ опытного российского юриста на тренировочный вопрос.
            Сравни ответ с эталоном и ключевыми тезисами. Оценивай смысл, а не формулировки: синонимы,
            другой порядок и сокращения засчитываются. Ссылку на норму засчитывай, если номер статьи верный.

            Для каждого ключевого тезиса поставь статус: covered (раскрыт), partial (упомянут неточно или
            неполно), missing (отсутствует). В errors перечисли только фактические юридические ошибки в ответе
            (неверный срок, не та норма, перепутанные последствия); пропуск тезиса ошибкой не считается.

            score: доля набранного веса тезисов от 0 до 1 (covered = полный вес, partial = половина).
            rating по шкале интервального повторения:
            1 — не раскрыт хотя бы один обязательный тезис или есть фактическая ошибка;
            2 — обязательные тезисы есть, но score ниже 0.7;
            3 — score от 0.7 до 0.95;
            4 — score выше 0.95 и ответ точный.
            feedback: 1–3 коротких предложения по-русски, обращение на «вы»: что упущено или неверно и как запомнить.
        """.trimIndent()

        private val JSON_SHAPE = """
            Ответь только JSON-объектом такого вида:
            {"key_points": [{"id": "kp1", "status": "covered"}], "errors": ["..."], "score": 0.8, "rating": 3, "feedback": "..."}
        """.trimIndent()

        private val STATUSES = setOf(Feynman.CORRECT, Feynman.PARTIAL, Feynman.WRONG, Feynman.MISSING)

        private val WHY_RULES = """
            Кроме ответа пользователь дал обоснование: объяснил своими словами, почему ответ именно такой
            (метод Фейнмана). Оцени обоснование отдельно от ответа и не снижай за него rating ответа.
            Сравни обоснование с эталонным обоснованием, если оно дано; если нет — с нормами и логикой эталона.
            reasoning.status:
            correct — названа верная норма или принцип и правильно объяснено, как из них следует ответ;
            partial — направление верное, но объяснение неполное, общее или без ключевого звена;
            wrong — опора на не ту норму или принцип, либо логика неверна;
            missing — обоснования по сути нет (повторён ответ, «так написано в законе» без объяснения).
            reasoning.feedback: 1–2 предложения на «вы»: чего не хватает в объяснении.
            reasoning.model_rationale: верное обоснование в 2–4 предложениях. Если эталонное обоснование дано,
            опирайся на него. Не выдумывай номера статей и пунктов: если не уверен в номере, называй принцип без номера.
        """.trimIndent()

        private val WHY_JSON_SHAPE = """
            Ответь только JSON-объектом такого вида:
            {"key_points": [{"id": "kp1", "status": "covered"}], "errors": ["..."], "score": 0.8, "rating": 3, "feedback": "...",
             "reasoning": {"status": "partial", "feedback": "...", "model_rationale": "..."}}
        """.trimIndent()

        private fun userPrompt(
            item: Item,
            answer: String,
            reasoning: String? = null,
            references: List<Feynman.Reference> = emptyList(),
        ): String {
            val points = item.keyPoints.joinToString("\n") { kp ->
                "- [${kp.id}] ${kp.text} (вес ${kp.weight}${if (kp.required) ", обязательный" else ""})"
            }
            return listOfNotNull(
                "Вопрос:\n${item.prompt}",
                "Эталонный ответ:\n${item.answer}",
                "Ключевые тезисы:\n${points.ifBlank { "- (не заданы: сравнивай с эталоном целиком)" }}",
                item.commonErrors.takeIf { it.isNotEmpty() }?.let { "Типичные ошибки:\n" + it.joinToString("\n") { e -> "- $e" } },
                "Ответ пользователя:\n<answer>\n$answer\n</answer>",
                references.takeIf { reasoning != null && it.isNotEmpty() }?.let { refs ->
                    "Эталонное обоснование:\n" + refs.joinToString("\n") { r ->
                        when (r.kind) {
                            Feynman.Kind.QUOTE -> "- цитата из источника: ${r.text}"
                            Feynman.Kind.DECK -> "- из колоды: ${r.text}"
                            Feynman.Kind.MINE -> "- сохранено пользователем ранее: ${r.text}"
                        }
                    }
                },
                reasoning?.let { "Обоснование пользователя:\n<reasoning>\n$it\n</reasoning>" },
            ).joinToString("\n\n")
        }
    }
}
