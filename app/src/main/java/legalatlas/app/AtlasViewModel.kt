package legalatlas.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import java.time.LocalDate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import legalatlas.core.AppState
import legalatlas.core.AtlasJson
import legalatlas.core.Candidate
import legalatlas.core.Confidence
import legalatlas.core.DocDeck
import legalatlas.core.DocText
import legalatlas.core.Decks
import legalatlas.core.Feynman
import legalatlas.core.Formats
import legalatlas.core.Fsrs
import legalatlas.core.Item
import legalatlas.core.Mode
import legalatlas.core.PlannedItem
import legalatlas.core.Planner
import legalatlas.core.Progress
import legalatlas.core.Rating
import legalatlas.core.Rework
import legalatlas.core.ReviewLog
import legalatlas.core.Settings
import legalatlas.core.Topic

sealed interface Screen {
    data object Home : Screen
    data object Session : Screen
    data object Summary : Screen
    data object Settings : Screen
    data object Generate : Screen
    data object Rework : Screen
}

/** DOCUMENT — вопросы из документа; DEEPEN — новые вопросы к трудным при переработке. */
enum class GenMode { DOCUMENT, DEEPEN }

/** Создание колоды из документа: от выбора файла до предпросмотра вопросов. */
data class GenUi(
    val mode: GenMode = GenMode.DOCUMENT,
    val weak: List<Rework.Weak> = emptyList(),
    val docTitle: String,
    val text: String,
    val total: Int = 20,
    val focus: String = "",
    val running: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val truncated: Boolean = false,
    val deckId: String = "doc-" + System.currentTimeMillis().toString(36),
    val candidates: List<Candidate> = emptyList(),
    val newTopics: List<Topic> = emptyList(),
    val selected: Set<String> = emptySet(),
)

/** WHY — шаг Фейнмана: после ответа объяснить, почему он такой. */
enum class Phase { ANSWER, WHY, GRADING, FEEDBACK }

data class SessionUi(
    val items: List<PlannedItem>,
    val title: String,
    val index: Int = 0,
    val phase: Phase = Phase.ANSWER,
    val answerText: String = "",
    val reasoningText: String = "",
    val selectedOption: Int? = null,
    val confidence: Confidence? = null,
    val grade: GradeResult? = null,
    val gradeError: String? = null,
    /** Оценка, которую приложение предлагает (для вопросов с вариантами — окончательная). */
    val suggested: Rating? = null,
    val itemStartedAt: Long = System.currentTimeMillis(),
    val startedAt: Long = System.currentTimeMillis(),
    val answered: Int = 0,
    val correct: Int = 0,
    val confidentErrors: List<Item> = emptyList(),
    val pretestTopic: String? = null,
) {
    val current: PlannedItem? get() = items.getOrNull(index)
}

class AtlasViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app.filesDir)
    private val secrets = SecretStore(app)
    private val planner = Planner()

    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _session = MutableStateFlow<SessionUi?>(null)
    val session: StateFlow<SessionUi?> = _session.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _gen = MutableStateFlow<GenUi?>(null)
    val gen: StateFlow<GenUi?> = _gen.asStateFlow()

    private val _rework = MutableStateFlow<Rework.Analysis?>(null)
    val rework: StateFlow<Rework.Analysis?> = _rework.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Последние символы сохранённого ключа DeepSeek, чтобы было видно, какой ключ стоит. */
    private val _graderKeyHint = MutableStateFlow(secrets.get(SecretStore.DEEPSEEK)?.let(::keyHint))
    val graderKeyHint: StateFlow<String?> = _graderKeyHint.asStateFlow()

    /** Резервная копия, выбранная для восстановления: ждёт подтверждения. */
    private val _pendingRestore = MutableStateFlow<AppState?>(null)
    val pendingRestore: StateFlow<AppState?> = _pendingRestore.asStateFlow()

    init {
        if (_state.value.settings.serverUrl.isNotBlank()) sync(quiet = true)
    }

    fun go(screen: Screen) { _screen.value = screen }
    fun consumeMessage() { _message.value = null }
    fun notify(text: String) { _message.value = text }

    private fun update(block: (AppState) -> AppState) {
        _state.update(block)
        val snapshot = _state.value
        viewModelScope.launch { store.save(snapshot) }
    }

    // ---------- Колоды ----------

    fun importText(text: String, origin: String) {
        val check = Decks.parse(text)
        if (!check.ok) {
            _message.value = "Файл не загружен ($origin):\n" + check.errors.take(5).joinToString("\n")
            return
        }
        val deck = check.deck!!
        var summary = ""
        update { s ->
            val r = Decks.import(s, deck)
            summary = "«${deck.deck.title}»: новых ${r.added}, обновлено ${r.updated}, удалено ${r.removed}"
            r.state
        }
        val warn = if (check.warnings.isEmpty()) "" else "\nЗамечания: " + check.warnings.take(3).joinToString("; ")
        _message.value = "Колода загружена. $summary$warn"
    }

    /** Файл колоды (JSON) загружается сразу, любой другой документ открывает создание колоды. */
    fun importUri(uri: Uri) {
        val app = getApplication<Application>()
        val name = displayName(uri)
        val bytes = runCatching { app.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrElse {
            _message.value = "Не удалось прочитать файл: ${it.message}"
            return
        }
        val head = String(bytes.take(64).toByteArray(), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (name.lowercase().endsWith(".json") || head.startsWith("{")) {
            importText(DocText.decodeText(bytes), "файл")
            return
        }
        val isPdf = name.lowercase().endsWith(".pdf") || app.contentResolver.getType(uri) == "application/pdf" ||
            head.startsWith("%PDF")
        viewModelScope.launch {
            _busy.value = true
            val text = runCatching {
                withContext(Dispatchers.IO) { if (isPdf) PdfText.extract(app, bytes) else DocText.extract(name, bytes) }
            }
            _busy.value = false
            text.fold(
                onSuccess = { openDocumentText(name.substringBeforeLast('.').ifBlank { "Документ" }, it) },
                onFailure = { _message.value = "Не удалось извлечь текст из «$name»: ${it.message}" },
            )
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    // ---------- Колода из документа ----------

    fun openDocumentText(title: String, text: String) {
        if (text.length < MIN_DOC_CHARS) {
            _message.value = "В документе почти нет текста (${text.length} знаков). Если это скан, сохраните его с распознанным текстом или в Word."
            return
        }
        _gen.value = GenUi(docTitle = title, text = text, total = suggestedCount(text))
        _screen.value = Screen.Generate
    }

    private fun suggestedCount(text: String) = (text.length / 1500).coerceIn(10, 40)

    fun setGenTotal(n: Int) = _gen.update { it?.copy(total = n) }
    fun setGenFocus(f: String) = _gen.update { it?.copy(focus = f) }
    fun toggleCandidate(id: String) = _gen.update { g ->
        g?.copy(selected = if (id in g.selected) g.selected - id else g.selected + id)
    }

    fun generate() {
        val g = _gen.value ?: return
        val key = secrets.get(SecretStore.DEEPSEEK)
        if (key == null) {
            _message.value = "Для создания вопросов нужен ключ DeepSeek: Настройки → «Оценка ответов»."
            return
        }
        val plan = DeckGenerator.plan(g.text, g.total)
        _gen.value = g.copy(running = true, error = null, truncated = g.mode == GenMode.DOCUMENT && plan.truncated, candidates = emptyList(), selected = emptySet())
        viewModelScope.launch {
            val state = _state.value
            val generator = DeckGenerator(key)
            val result = runCatching {
                if (g.mode == GenMode.DEEPEN) {
                    generator.deepen(g.weak, state.topics.mapValues { it.value.title }) { done, total ->
                        _gen.update { it?.copy(progress = if (done < total) "Придумываю вопросы: ${done + 1} из $total…" else "Проверяю…") }
                    }
                } else {
                    generator.generate(plan, g.docTitle, g.focus, DocDeck.existingTopics(state)) { done, total ->
                        _gen.update { it?.copy(progress = if (done < total) "Читаю часть ${done + 1} из $total…" else "Проверяю цитаты…") }
                    }
                }
            }
            result.fold(
                onSuccess = { batches ->
                    val (cands, topics) = DocDeck.candidates(
                        state, g.deckId, g.docTitle, g.text, batches, LocalDate.now().toString(),
                        quoteRequired = g.mode == GenMode.DOCUMENT,
                    )
                    _gen.update {
                        it?.copy(
                            running = false, progress = null, candidates = cands, newTopics = topics,
                            selected = cands.filter { c -> c.recommended }.map { c -> c.item.id }.toSet(),
                            error = if (cands.isEmpty()) "Модель не предложила ни одного вопроса. Попробуйте другой фрагмент или уточните акцент." else null,
                        )
                    }
                },
                onFailure = { e -> _gen.update { it?.copy(running = false, progress = null, error = e.message ?: "Ошибка") } },
            )
        }
    }

    fun addSelected() {
        val g = _gen.value ?: return
        val chosen = g.candidates.filter { it.item.id in g.selected }.map { it.item }
        if (chosen.isEmpty()) return
        val deck = DocDeck.build(_state.value, g.deckId, g.docTitle, chosen, g.newTopics)
        val check = Decks.parse(AtlasJson.encodeToString(legalatlas.core.DeckFile.serializer(), deck))
        if (!check.ok) {
            _message.value = "Колода не прошла проверку: " + check.errors.take(3).joinToString("; ")
            return
        }
        val newBooks = deck.topics.count { t -> g.newTopics.any { it.id == t.id } }
        update { Decks.import(it, deck).state }
        // Текст документа хранится рядом: по нему проверяются цитаты при углублении трудных вопросов.
        if (g.mode == GenMode.DOCUMENT) runCatching { docFile(g.deckId).apply { parentFile?.mkdirs() }.writeText(g.text) }
        _gen.value = null
        _screen.value = Screen.Home
        _message.value = buildString {
            append("Добавлено вопросов: ${chosen.size}.")
            val intoExisting = chosen.count { c -> g.newTopics.none { it.id == c.topicId } }
            if (intoExisting > 0) append(" В существующие книги: $intoExisting.")
            if (newBooks > 0) append(" Новых книг: $newBooks. Они закрыты: начните с разведки.")
        }
    }

    fun closeGenerator() {
        val g = _gen.value
        if (g?.running == true) return
        _gen.value = null
        _screen.value = if (g?.mode == GenMode.DEEPEN) Screen.Rework else Screen.Home
    }

    private fun docFile(deckId: String) = java.io.File(getApplication<Application>().filesDir, "docs/$deckId.txt")

    // ---------- Переработка колод ----------

    fun openRework() {
        _rework.value = Rework.analyze(_state.value, System.currentTimeMillis())
        _screen.value = Screen.Rework
    }

    /** Лёгкие вопросы убираются из повторений; вернуть их можно в настройках. */
    fun retire(ids: Set<String>) {
        if (ids.isEmpty()) return
        update { it.copy(retiredItems = it.retiredItems + ids) }
        _rework.value = Rework.analyze(_state.value, System.currentTimeMillis())
        _message.value = "Убрано из повторений: ${ids.size}. Вернуть их можно в настройках."
    }

    fun restoreRetired() = update { it.copy(retiredItems = emptySet()) }

    /** Новые вопросы к трудным: генерация и тот же предпросмотр, что для документа. */
    fun deepen(ids: Set<String>) {
        val weak = _rework.value?.weak.orEmpty().filter { it.item.id in ids }
        if (weak.isEmpty()) return
        val state = _state.value
        val deckIds = state.decks.filterValues { d -> d.items.any { i -> weak.any { it.item.id == i.id } } }.keys
        val docs = deckIds.mapNotNull { id -> runCatching { docFile(id).takeIf { it.exists() }?.readText() }.getOrNull() }
        _gen.value = GenUi(
            mode = GenMode.DEEPEN,
            weak = weak,
            docTitle = "Углубление ${LocalDate.now()}",
            text = docs.joinToString("\n\n"),
            deckId = "deep-" + System.currentTimeMillis().toString(36),
        )
        _screen.value = Screen.Generate
        generate()
    }

    fun importSample() {
        val text = getApplication<Application>().assets.open("sample-deck.json").bufferedReader().use { it.readText() }
        importText(text, "пример")
    }

    companion object {
        private const val MIN_DOC_CHARS = 300
    }

    fun deleteDeck(deckId: String) = update { s ->
        runCatching { docFile(deckId).delete() }
        val ids = s.decks[deckId]?.items?.map { it.id }?.toSet().orEmpty()
        s.copy(decks = s.decks - deckId, cards = s.cards - ids)
    }

    // ---------- Настройки и синхронизация ----------

    fun saveSettings(settings: Settings) = update { it.copy(settings = settings) }

    fun saveGraderKey(key: String) {
        val value = key.trim().takeIf { it.isNotEmpty() }
        secrets.put(SecretStore.DEEPSEEK, value)
        _graderKeyHint.value = value?.let(::keyHint)
        _message.value = if (value == null) "Ключ DeepSeek удалён" else "Ключ DeepSeek сохранён. Развёрнутые ответы теперь оцениваются прямо с телефона."
    }

    private fun keyHint(key: String) = "…" + key.takeLast(4)

    /** Оценивать развёрнутые ответы можно по ключу на телефоне или через сервер. */
    private fun canGrade() = _graderKeyHint.value != null || _state.value.settings.serverUrl.isNotBlank()

    // ---------- Резервная копия ----------

    /** Полная копия: колоды, прогресс, журнал и настройки. Ключи API в неё не входят. */
    fun writeBackup(uri: Uri) {
        val json = AtlasJson.encodeToString(AppState.serializer(), _state.value)
        runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
        }.onSuccess { _message.value = "Копия сохранена" }
            .onFailure { _message.value = "Не удалось сохранить копию: ${it.message}" }
    }

    fun readBackup(uri: Uri) {
        runCatching {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            AtlasJson.decodeFromString(AppState.serializer(), text)
        }.onSuccess { restored ->
            if (restored.decks.isEmpty() && restored.logs.isEmpty()) _message.value = "В файле нет ни колод, ни ответов: это не копия «Атласа права»"
            else _pendingRestore.value = restored
        }.onFailure { _message.value = "Не удалось прочитать копию: ${it.message}" }
    }

    fun confirmRestore() {
        val restored = _pendingRestore.value ?: return
        _pendingRestore.value = null
        update { restored }
        _message.value = "Восстановлено: колод ${restored.decks.size}, ответов в журнале ${restored.logs.size}"
    }

    fun cancelRestore() { _pendingRestore.value = null }

    fun unflagAll() = update { it.copy(flaggedItems = emptySet()) }

    fun sync(quiet: Boolean = false) {
        val s = _state.value
        if (s.settings.serverUrl.isBlank()) {
            if (!quiet) _message.value = "Укажите адрес сервера в настройках"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                val api = Api(s.settings.serverUrl, s.settings.token)
                val remote = api.decksSince(s.lastDeckSync)
                remote.decks.forEach { importText(it.json, "сервер") }
                val fresh = _state.value
                val pending = fresh.logs.drop(fresh.syncedLogCount)
                if (pending.isNotEmpty()) api.uploadReviews(pending)
                update { it.copy(lastDeckSync = remote.serverTime, syncedLogCount = fresh.syncedLogCount + pending.size) }
                if (!quiet && remote.decks.isEmpty()) _message.value = "Синхронизировано. Новых колод нет, отправлено ответов: ${pending.size}"
            } catch (e: Exception) {
                if (!quiet) _message.value = "Сервер недоступен: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /** Отчёт для разбора в Claude: состояние каждого вопроса и журнал ответов. */
    fun exportJson(): String {
        val s = _state.value
        val now = System.currentTimeMillis()
        val fsrs = Fsrs(desiredRetention = s.settings.desiredRetention)
        val root = buildJsonObject {
            put("exported_at", now)
            put("decks", buildJsonArray {
                s.decks.values.forEach { d -> add(buildJsonObject { put("id", d.deck.id); put("title", d.deck.title) }) }
            })
            put("items", buildJsonArray {
                s.items.values.forEach { item ->
                    val card = s.cards[item.id]
                    add(buildJsonObject {
                        put("id", item.id)
                        put("topic_id", item.topicId)
                        put("prompt", item.prompt)
                        put("flagged", item.id in s.flaggedItems)
                        put("retired", item.id in s.retiredItems)
                        if (card != null) {
                            put("reps", card.reps)
                            put("lapses", card.lapses)
                            put("stability_days", card.stability)
                            put("difficulty", card.difficulty)
                            put("recall_probability_now", fsrs.retrievability(card, now))
                        }
                    })
                }
            })
            put("logs", AtlasJson.encodeToJsonElement(ListSerializer(ReviewLog.serializer()), s.logs))
        }
        return root.toString()
    }

    fun writeExport(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(exportJson().toByteArray()) }
        }.onSuccess { _message.value = "Отчёт сохранён" }
            .onFailure { _message.value = "Не удалось сохранить: ${it.message}" }
    }

    // ---------- Сессия ----------

    fun startSession(minutes: Int, topicId: String? = null) {
        val s = _state.value
        val source = if (topicId == null) s else s.copy(
            decks = s.decks.mapValues { (_, d) -> d.copy(items = d.items.filter { it.topicId == topicId }) },
        )
        val plan = planner.plan(source, System.currentTimeMillis(), minutes)
        if (plan.isEmpty()) {
            _message.value = if (s.unlockedTopics.isEmpty() && s.cards.isEmpty())
                "Сначала откройте область на карте: нажмите на неё и пройдите разведку."
            else "Сейчас повторять нечего. Откройте новую область или загрузите вопросы."
            return
        }
        val title = topicId?.let { s.topics[it]?.title } ?: "Сессия на $minutes мин"
        _session.value = SessionUi(items = plan, title = title)
        _screen.value = Screen.Session
    }

    fun startPretest(topicId: String) {
        val s = _state.value
        val plan = planner.pretest(s, topicId)
        if (plan.isEmpty()) {
            _message.value = "В этой области пока нет вопросов"
            return
        }
        _session.value = SessionUi(items = plan, title = "Разведка: ${s.topics[topicId]?.title}", pretestTopic = topicId)
        _screen.value = Screen.Session
    }

    fun setAnswerText(text: String) = _session.update { it?.copy(answerText = text) }
    fun setReasoningText(text: String) = _session.update { it?.copy(reasoningText = text) }
    fun selectOption(i: Int) = _session.update { it?.copy(selectedOption = i) }

    /** Ответ отправляется вместе с уверенностью: кнопки «уверен / думаю / наугад». */
    fun submit(confidence: Confidence) {
        val ui = _session.value ?: return
        val planned = ui.current ?: return
        val item = planned.item
        when {
            item.format == Formats.CHOICE -> {
                val correct = ui.selectedOption == item.correctOption
                _session.value = ui.copy(
                    confidence = confidence,
                    phase = Phase.FEEDBACK,
                    suggested = Progress.choiceRating(correct, confidence),
                )
            }
            planned.feynman && Formats.isOpen(item.format) && ui.answerText.isNotBlank() ->
                _session.value = ui.copy(confidence = confidence, phase = Phase.WHY)
            Formats.isOpen(item.format) && ui.answerText.isNotBlank() && canGrade() -> {
                _session.value = ui.copy(confidence = confidence, phase = Phase.GRADING)
                grade(item, ui.answerText, null)
            }
            else -> _session.value = ui.copy(confidence = confidence, phase = Phase.FEEDBACK)
        }
    }

    /** Обоснование отправлено (пустое — «не могу объяснить»): оцениваются ответ и обоснование вместе. */
    fun submitReasoning() {
        val ui = _session.value ?: return
        val item = ui.current?.item ?: return
        if (ui.phase != Phase.WHY) return
        if (canGrade()) {
            _session.value = ui.copy(phase = Phase.GRADING)
            grade(item, ui.answerText, ui.reasoningText.trim())
        } else {
            _session.value = ui.copy(phase = Phase.FEEDBACK)
        }
    }

    private fun grade(item: Item, answer: String, reasoning: String?) {
        val state = _state.value
        val settings = state.settings
        val key = secrets.get(SecretStore.DEEPSEEK)
        val references = Feynman.references(item, state)
        viewModelScope.launch {
            // Ключ на телефоне важнее сервера: так оценка работает и без него.
            // Сервер обоснования не проверяет: там остаётся сравнение с эталоном вручную.
            val result = runCatching {
                if (key != null) Grader(key).grade(item, answer, reasoning, references)
                else Api(settings.serverUrl, settings.token).grade(item, answer)
            }
            _session.update { ui ->
                if (ui == null || ui.current?.item?.id != item.id) return@update ui
                result.fold(
                    onSuccess = {
                        ui.copy(phase = Phase.FEEDBACK, grade = it, suggested = Feynman.cap(Rating.of(it.rating), it.reasoning?.status))
                    },
                    onFailure = { ui.copy(phase = Phase.FEEDBACK, gradeError = "Оценка недоступна (${it.message}). Сравните с эталоном и оцените сами.") },
                )
            }
        }
    }

    fun rate(rating: Rating) {
        val ui = _session.value ?: return
        val planned = ui.current ?: return
        val now = System.currentTimeMillis()
        val confidence = ui.confidence ?: Confidence.THINK
        update {
            Progress.record(
                it, planned.item, planned.mode, rating, confidence,
                elapsedMs = now - ui.itemStartedAt, now = now,
                score = ui.grade?.score,
                answer = ui.answerText.takeIf { a -> a.isNotBlank() }
                    ?: ui.selectedOption?.let { i -> planned.item.options.getOrNull(i) },
                reasoning = ui.reasoningText.takeIf { planned.feynman && ui.confidence != null && ui.answerText.isNotBlank() },
                reasoningStatus = ui.grade?.reasoning?.status,
            )
        }
        next(ui, rating, confidence)
    }

    /** Сохранить своё обоснование как эталон для этого вопроса (когда в колоде эталона нет). */
    fun saveMyRationale() {
        val ui = _session.value ?: return
        val item = ui.current?.item ?: return
        val text = ui.reasoningText.trim().takeIf { it.isNotEmpty() } ?: return
        update { it.copy(myRationales = it.myRationales + (item.id to text)) }
        _message.value = "Обоснование сохранено. В следующий раз ответ будут сравнивать и с ним."
    }

    /** «Вопрос неверен»: убрать из повторений до исправления колоды. */
    fun flagCurrent() {
        val ui = _session.value ?: return
        val item = ui.current?.item ?: return
        update { it.copy(flaggedItems = it.flaggedItems + item.id) }
        _message.value = "Вопрос отмечен и больше не будет показываться. Он попадёт в отчёт."
        next(ui, null, null)
    }

    private fun next(ui: SessionUi, rating: Rating?, confidence: Confidence?) {
        val isError = rating != null && confidence != null && Progress.isConfidentError(rating, confidence)
        val updated = ui.copy(
            index = ui.index + 1,
            phase = Phase.ANSWER,
            answerText = "",
            reasoningText = "",
            selectedOption = null,
            confidence = null,
            grade = null,
            gradeError = null,
            suggested = null,
            itemStartedAt = System.currentTimeMillis(),
            answered = ui.answered + if (rating != null) 1 else 0,
            correct = ui.correct + if (rating != null && rating.value >= Rating.GOOD.value) 1 else 0,
            confidentErrors = if (isError) ui.confidentErrors + ui.current!!.item else ui.confidentErrors,
        )
        _session.value = updated
        if (updated.current == null) finish()
    }

    fun finish() {
        val ui = _session.value ?: return
        ui.pretestTopic?.let { topic -> update { it.copy(unlockedTopics = it.unlockedTopics + topic) } }
        _screen.value = Screen.Summary
        if (_state.value.settings.serverUrl.isNotBlank()) sync(quiet = true)
    }

    fun closeSummary() {
        _session.value = null
        _screen.value = Screen.Home
    }
}
