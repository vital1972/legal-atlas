package legalatlas.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import legalatlas.app.AtlasViewModel
import legalatlas.app.Phase
import legalatlas.app.SessionUi
import legalatlas.core.Confidence
import legalatlas.core.Feynman
import legalatlas.core.Formats
import legalatlas.core.Item
import legalatlas.core.Mode
import legalatlas.core.Progress
import legalatlas.core.Rating

@Composable
fun SessionScreen(vm: AtlasViewModel) {
    val ui by vm.session.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val session = ui ?: return
    val planned = session.current ?: return
    val item = planned.item
    val references = remember(item.id, state.myRationales[item.id]) { Feynman.references(item, state) }

    Column(
        Modifier
            .fillMaxSize()
            .background(AtlasColors.Paper)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${session.title} · ${session.index + 1}/${session.items.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm::finish) { Text("Завершить") }
        }
        LinearProgressIndicator(
            progress = { session.index.toFloat() / session.items.size },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            color = AtlasColors.Accent,
            trackColor = AtlasColors.Fog,
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeBadge(planned.mode)
                if (planned.feynman) {
                    Spacer(Modifier.padding(4.dp))
                    Text("+ почему?", color = AtlasColors.Accent, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(item.prompt, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
            Spacer(Modifier.height(16.dp))
            when (session.phase) {
                Phase.ANSWER -> AnswerInput(vm, session, item)
                Phase.WHY -> WhyInput(vm, session, item)
                Phase.GRADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(28.dp))
                    Spacer(Modifier.padding(8.dp))
                    Text(if (planned.feynman) "Проверяю ответ и обоснование…" else "Проверяю ответ…")
                }
                Phase.FEEDBACK -> {
                    Feedback(session, item)
                    if (planned.feynman && session.answerText.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        ReasoningFeedback(session, item, references, onSaveMine = vm::saveMyRationale)
                    } else {
                        item.whyPrompt?.let {
                            Spacer(Modifier.height(12.dp))
                            Text("Подумайте: $it", color = AtlasColors.Good)
                        }
                    }
                }
            }
        }

        Box(Modifier.padding(16.dp)) {
            when (session.phase) {
                Phase.ANSWER -> ConfidenceButtons(
                    enabled = item.format != Formats.CHOICE || session.selectedOption != null,
                    selfCheck = !Formats.isOpen(item.format) && item.format != Formats.CHOICE,
                    onSubmit = vm::submit,
                )
                Phase.WHY -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = vm::submitReasoning, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Проверить ответ и обоснование")
                    }
                    TextButton(onClick = { vm.setReasoningText(""); vm.submitReasoning() }) {
                        Text("Не могу объяснить", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Phase.GRADING -> Unit
                Phase.FEEDBACK -> RatingButtons(session, item, onRate = vm::rate, onFlag = vm::flagCurrent)
            }
        }
    }
}

@Composable
private fun ModeBadge(mode: Mode) {
    val (text, color) = when (mode) {
        Mode.REVIEW -> "Повторение" to AtlasColors.Good
        Mode.NEW -> "Новый вопрос" to AtlasColors.Accent
        Mode.AHEAD -> "Укрепление" to AtlasColors.Good
        Mode.PRETEST -> "Разведка: попробуйте угадать" to AtlasColors.Alert
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.border(1.dp, color, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun AnswerInput(vm: AtlasViewModel, session: SessionUi, item: Item) {
    var hintsShown by remember(item.id) { mutableStateOf(0) }
    when {
        item.format == Formats.CHOICE -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item.options.forEachIndexed { i, option ->
                val chosen = session.selectedOption == i
                Text(
                    option,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, if (chosen) AtlasColors.Accent else AtlasColors.Fog, RoundedCornerShape(12.dp))
                        .clickable { vm.selectOption(i) }
                        .padding(14.dp),
                )
            }
        }
        Formats.isOpen(item.format) -> {
            OutlinedTextField(
                value = session.answerText,
                onValueChange = vm::setAnswerText,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                placeholder = { Text("Ваш ответ своими словами") },
                textStyle = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            DictateButton(vm) { spoken -> vm.setAnswerText((session.answerText + " " + spoken).trim()) }
        }
        else -> Text(
            "Вспомните ответ про себя, затем отметьте, насколько уверены.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (item.hints.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        item.hints.take(hintsShown).forEach { Text("Подсказка: $it", color = AtlasColors.Good) }
        if (hintsShown < item.hints.size) TextButton(onClick = { hintsShown++ }) { Text("Подсказка") }
    }
}

/** Голосовой ввод: распознанный текст дописывается в поле. */
@Composable
private fun DictateButton(vm: AtlasViewModel, onText: (String) -> Unit) {
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
        }
    }
    OutlinedButton(onClick = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        try {
            speech.launch(intent)
        } catch (_: ActivityNotFoundException) {
            vm.notify("На телефоне не найдено распознавание речи. Можно диктовать с клавиатуры: значок микрофона на ней.")
        }
    }) { Text("Надиктовать") }
}

/** Шаг Фейнмана: ответ уже дан, теперь объяснить своими словами, почему он такой. */
@Composable
private fun WhyInput(vm: AtlasViewModel, session: SessionUi, item: Item) {
    Text("Ваш ответ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(session.answerText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Text("Почему так?", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
    Text(
        item.whyPrompt ?: "Объясните, как новичку: на какой норме или принципе держится ответ и как из них следует вывод.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = session.reasoningText,
        onValueChange = vm::setReasoningText,
        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        placeholder = { Text("Потому что…") },
        textStyle = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(8.dp))
    DictateButton(vm) { spoken -> vm.setReasoningText((session.reasoningText + " " + spoken).trim()) }
}

/** Разбор обоснования: оценка, замечание и верное обоснование с указанием, откуда оно. */
@Composable
private fun ReasoningFeedback(session: SessionUi, item: Item, references: List<Feynman.Reference>, onSaveMine: () -> Unit) {
    val graded = session.grade?.reasoning
    Text("Обоснование", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
    if (graded != null) {
        val (label, color) = when (graded.status) {
            Feynman.CORRECT -> "Обоснование верное" to AtlasColors.Good
            Feynman.PARTIAL -> "Обоснование неполное: оценка не выше «Вспомнил»" to AtlasColors.Accent
            Feynman.WRONG -> "Обоснование неверное: оценка не выше «С трудом»" to AtlasColors.Alert
            else -> "Обоснования нет: оценка не выше «С трудом»" to AtlasColors.Alert
        }
        Spacer(Modifier.height(6.dp))
        Banner(label, color)
        if (graded.feedback.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(graded.feedback, style = MaterialTheme.typography.bodyLarge)
        }
    } else if (session.reasoningText.isNotBlank()) {
        Text(
            "Обоснование не проверено автоматически. Сравните его с верным ниже.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (session.reasoningText.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text("Ваше обоснование: ${session.reasoningText}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    references.forEach { ref ->
        Spacer(Modifier.height(10.dp))
        val title = when (ref.kind) {
            Feynman.Kind.QUOTE -> "Цитата из источника" +
                (listOfNotNull(item.source?.title, item.source?.locator).joinToString(", ").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            Feynman.Kind.DECK -> "Верное обоснование"
            Feynman.Kind.MINE -> "Ваше сохранённое обоснование"
        }
        Text(title, style = MaterialTheme.typography.labelLarge, color = AtlasColors.Good)
        Text(ref.text, style = MaterialTheme.typography.bodyLarge)
    }
    val modelRationale = graded?.modelRationale?.takeIf { it.isNotBlank() }
    if (modelRationale != null && references.none { it.kind != Feynman.Kind.MINE }) {
        Spacer(Modifier.height(10.dp))
        Text("Объяснение модели. Сверьте с источником", style = MaterialTheme.typography.labelLarge, color = AtlasColors.Muted)
        Text(modelRationale, style = MaterialTheme.typography.bodyLarge)
    }
    val canSave = graded?.status == Feynman.CORRECT && session.reasoningText.isNotBlank() &&
        references.none { it.kind != Feynman.Kind.MINE }
    if (canSave) {
        TextButton(onClick = onSaveMine) { Text("Сохранить моё обоснование как эталон") }
    }
}

@Composable
private fun ConfidenceButtons(enabled: Boolean, selfCheck: Boolean, onSubmit: (Confidence) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (selfCheck) "Насколько уверены, что помните?" else "Отправить ответ. Насколько уверены?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val labels = if (selfCheck) listOf("Точно знаю", "Кажется, помню", "Не помню")
            else listOf("Уверен", "Думаю, так", "Наугад")
            Confidence.entries.zip(labels).forEach { (c, label) ->
                Button(
                    onClick = { onSubmit(c) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (c == Confidence.SURE) AtlasColors.Accent else AtlasColors.Soft,
                        contentColor = if (c == Confidence.SURE) Color.White else AtlasColors.Text),
                ) { Text(label, maxLines = 2) }
            }
        }
    }
}

@Composable
private fun Feedback(session: SessionUi, item: Item) {
    val rating = session.suggested
    val confidence = session.confidence
    if (rating != null && confidence != null && Progress.isConfidentError(rating, confidence)) {
        Banner("Уверенная ошибка. Такие ошибки запоминаются лучше всего, если сейчас внимательно прочитать разбор.", AtlasColors.Alert)
        Spacer(Modifier.height(12.dp))
    }
    if (item.format == Formats.CHOICE) {
        val correct = session.selectedOption == item.correctOption
        Banner(if (correct) "Верно" else "Неверно", if (correct) AtlasColors.Good else AtlasColors.Alert)
        Spacer(Modifier.height(12.dp))
    }
    session.gradeError?.let {
        Text(it, color = AtlasColors.Alert)
        Spacer(Modifier.height(12.dp))
    }
    session.grade?.let { g ->
        if (g.feedback.isNotBlank()) {
            Text(g.feedback, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
        }
        g.errors.forEach { Text("Ошибка: $it", color = AtlasColors.Alert) }
    }
    if (item.keyPoints.isNotEmpty()) {
        Text("Ключевые тезисы", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
        val statuses = session.grade?.keyPoints?.associate { it.id to it.status }.orEmpty()
        item.keyPoints.forEach { kp ->
            val (mark, color) = when (statuses[kp.id]) {
                "covered" -> "✓" to AtlasColors.Good
                "partial" -> "≈" to AtlasColors.Accent
                "missing" -> "✗" to AtlasColors.Alert
                else -> "•" to AtlasColors.Muted
            }
            Text("$mark ${kp.text}", color = color, modifier = Modifier.padding(vertical = 2.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
    Text("Эталонный ответ", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
    Text(item.answer, style = MaterialTheme.typography.bodyLarge)
    item.explanation?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val source = listOfNotNull(item.source?.title, item.source?.locator).joinToString(", ")
    if (source.isNotBlank() || item.norms.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Источник: " + listOf(source, item.norms.joinToString("; ")).filter { it.isNotBlank() }.joinToString(" · ") +
                (item.validAsOf?.let { " (актуально на $it)" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().background(color, RoundedCornerShape(10.dp)).padding(12.dp),
    )
}

@Composable
private fun RatingButtons(session: SessionUi, item: Item, onRate: (Rating) -> Unit, onFlag: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val fixed = session.suggested.takeIf { item.format == Formats.CHOICE }
        if (fixed != null) {
            Button(onClick = { onRate(fixed) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Дальше") }
        } else {
            Text(
                if (session.suggested != null) "Предложенная оценка выделена. Можно выбрать другую." else "Как получилось?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Rating.AGAIN to "Не вспомнил",
                    Rating.HARD to "С трудом",
                    Rating.GOOD to "Вспомнил",
                    Rating.EASY to "Легко",
                ).forEach { (r, label) ->
                    val highlighted = r == session.suggested
                    Button(
                        onClick = { onRate(r) },
                        modifier = Modifier.weight(1f).height(60.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (highlighted) AtlasColors.Accent else AtlasColors.Soft,
                            contentColor = if (highlighted) Color.White else AtlasColors.Text,
                        ),
                    ) { Text(label, maxLines = 2, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        TextButton(onClick = onFlag) { Text("Вопрос неверен или устарел", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
