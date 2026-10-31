package legalatlas.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import legalatlas.app.AtlasViewModel
import legalatlas.app.GenMode
import legalatlas.app.GenUi
import legalatlas.core.Candidate
import legalatlas.core.Formats

/** Колода из документа: настройки, ход работы и предпросмотр вопросов с отметками проверки. */
@Composable
fun GenerateScreen(vm: AtlasViewModel) {
    val ui by vm.gen.collectAsStateWithLifecycle()
    val g = ui ?: return

    Column(
        Modifier
            .fillMaxSize()
            .background(AtlasColors.Paper)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeGenerator, enabled = !g.running) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (g.mode == GenMode.DEEPEN) "Углубление трудных вопросов" else "Колода из документа",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (g.mode == GenMode.DEEPEN) "Трудных вопросов: ${g.weak.size}"
                    else "«${g.docTitle}» · ${"%,d".format(g.text.length).replace(',', ' ')} знаков",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        when {
            g.running -> Running(g)
            g.candidates.isNotEmpty() -> Preview(vm, g)
            g.mode == GenMode.DEEPEN -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                g.error?.let { Text(it, color = AtlasColors.Alert) }
                Button(onClick = vm::generate, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Попробовать ещё раз") }
            }
            else -> Setup(vm, g)
        }
    }
}

@Composable
private fun Setup(vm: AtlasViewModel, g: GenUi) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Сколько вопросов: ${g.total}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = g.total.toFloat(),
            onValueChange = { vm.setGenTotal(it.roundToInt()) },
            valueRange = 5f..60f,
            steps = 10,
        )
        OutlinedTextField(
            value = g.focus,
            onValueChange = vm::setGenFocus,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("На чём сделать акцент (необязательно)") },
            placeholder = { Text("Например: сроки и приёмка работ, готовлюсь к спору с подрядчиком") },
            minLines = 2,
        )
        Text(
            "DeepSeek прочитает документ по частям и разложит вопросы по вашим книгам, а для новой темы заведёт новую книгу. " +
                "У каждого вопроса будет дословная цитата из документа: приложение проверит, что она там действительно есть. " +
                "Перед добавлением вы увидите все вопросы и сможете убрать лишние.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        g.error?.let { Text(it, color = AtlasColors.Alert) }
        Button(onClick = vm::generate, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Создать вопросы") }
    }
}

@Composable
private fun Running(g: GenUi) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(g.progress ?: "Начинаю…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Каждая часть занимает около минуты. Не закрывайте приложение.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Preview(vm: AtlasViewModel, g: GenUi) {
    val unverified = g.candidates.count { !it.quoteVerified && it.quoteRequired }
    val dups = g.candidates.count { it.duplicateOf != null }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Предложено ${g.candidates.size}, выбрано ${g.selected.size}. " +
                    (if (unverified > 0) "Цитата не найдена в документе: $unverified. " else "") +
                    (if (dups > 0) "Похожи на уже имеющиеся: $dups. " else "") +
                    (if (unverified + dups > 0) "Такие вопросы сняты, отметьте их сами, если считаете верными." else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (g.truncated) Text("Документ очень длинный: вопросы созданы по его первой части.", color = AtlasColors.Alert)
        }
        val grouped = g.candidates.groupBy { it.topicTitle to it.newTopic }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.forEach { (book, list) ->
                item(key = "h-${book.first}") {
                    Text(
                        book.first + if (book.second) " · новая книга" else "",
                        style = MaterialTheme.typography.titleMedium,
                        color = AtlasColors.Accent,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(list, key = { it.item.id }) { c -> CandidateRow(c, c.item.id in g.selected) { vm.toggleCandidate(c.item.id) } }
            }
        }
        Button(
            onClick = vm::addSelected,
            enabled = g.selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) { Text("Добавить выбранные (${g.selected.size})") }
    }
}

@Composable
private fun CandidateRow(c: Candidate, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AtlasColors.Panel, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(c.item.prompt, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(c.item.answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
            Spacer(Modifier.height(4.dp))
            val format = when (c.item.format) {
                Formats.FREE_RECALL -> "свободный ответ"
                Formats.CASE_SCENARIO -> "задача"
                Formats.CHOICE -> "выбор варианта"
                Formats.CLOZE -> "пропуск"
                else -> "вспомнить"
            }
            val tags = listOfNotNull(
                format,
                c.item.source?.locator,
                if (c.item.pretest) "разведка" else null,
            ).joinToString(" · ")
            Text(tags, style = MaterialTheme.typography.labelMedium, color = AtlasColors.Muted)
            when {
                c.quoteVerified -> Text("✓ цитата найдена в документе", style = MaterialTheme.typography.labelMedium, color = AtlasColors.Good)
                c.quoteRequired -> Text("✗ цитата не найдена в документе: проверьте ответ", style = MaterialTheme.typography.labelMedium, color = AtlasColors.Alert)
                else -> Text("Без цитаты: сверьте ответ с источником", style = MaterialTheme.typography.labelMedium, color = AtlasColors.Muted)
            }
            if (c.duplicateOf != null) Text("Похож на уже имеющийся вопрос", style = MaterialTheme.typography.labelMedium, color = AtlasColors.Alert)
        }
    }
}
