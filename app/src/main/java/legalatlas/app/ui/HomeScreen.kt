package legalatlas.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import legalatlas.app.AtlasViewModel
import legalatlas.app.Screen
import legalatlas.core.Book
import legalatlas.core.Bookmarks
import legalatlas.core.Planner

@Composable
fun HomeScreen(vm: AtlasViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val now = remember(state) { System.currentTimeMillis() }
    val planner = remember { Planner() }
    val books = remember(state) { Bookmarks.books(state, now) }
    val worlds = remember(books) { books.map { it.mastery.topic.world ?: "Без отрасли" }.distinct().sorted() }
    var selected by remember { mutableStateOf<Book?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importUri) }
    val docTypes = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/rtf", "text/rtf", "text/plain", "application/json", "*/*",
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(AtlasColors.Paper)
            .safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Атлас права", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { picker.launch(docTypes) }) {
                Icon(Icons.Filled.Add, contentDescription = "Колода из документа или файла")
            }
            IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, contentDescription = "Настройки") }
        }

        if (state.decks.isEmpty()) {
            EmptyAtlas(onSample = vm::importSample, onFile = { picker.launch(docTypes) })
            return@Column
        }

        val minutes = planner.minutesThisWeek(state, now)
        val goal = state.settings.weeklyGoalMinutes
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "За неделю $minutes из $goal мин · к повторению ${planner.dueCount(state, now)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (minutes.toFloat() / goal).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = AtlasColors.Good,
                trackColor = AtlasColors.Fog,
            )
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            items(worlds, key = { it }) { w ->
                val onShelf = books.filter { (it.mastery.topic.world ?: "Без отрасли") == w }
                    .sortedWith(compareBy({ it.locked }, { it.mastery.topic.title }))
                Shelf(w, LibraryColors.covers[worlds.indexOf(w) % LibraryColors.covers.size], onShelf) { selected = it }
            }
        }

        Text(
            "Сессия",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(10, 20, 30).forEach { m ->
                Button(onClick = { vm.startSession(m) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("$m мин") }
            }
        }
    }

    selected?.let { book ->
        TopicDialog(
            book = book,
            onDismiss = { selected = null },
            onPretest = { selected = null; vm.startPretest(book.mastery.topic.id) },
            onPractice = { selected = null; vm.startSession(15, book.mastery.topic.id) },
        )
    }
}

@Composable
private fun EmptyAtlas(onSample: () -> Unit, onFile: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Карта пока пуста", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Выберите документ (PDF, Word, RTF): приложение создаст по нему вопросы. Можно загрузить готовую колоду JSON или начать с примера по исковой давности.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSample, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Загрузить пример") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onFile, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Выбрать документ или колоду") }
    }
}

/** Что нужно для следующей закладки. */
private fun nextTab(book: Book): String? = when (book.tabs) {
    1 -> "Следующая закладка: пройти каждый вопрос книги хотя бы раз."
    2 -> "Следующая закладка: вспоминать не меньше 70% вопросов."
    3 -> "Следующая закладка: не меньше 85% и верные обоснования «почему»."
    4 -> "Последняя закладка: не меньше 90% и интервалы больше месяца."
    else -> null
}

@Composable
private fun TopicDialog(book: Book, onDismiss: () -> Unit, onPretest: () -> Unit, onPractice: () -> Unit) {
    val tm = book.mastery
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tm.topic.title) },
        text = {
            Column {
                if (!tm.unlocked) {
                    Text("Книга закрыта. Разведка: несколько вопросов на угадывание до изучения. Ошибаться можно, это помогает запомнить. После разведки книга откроется и получит первую закладку.")
                    Spacer(Modifier.height(8.dp))
                    Text("Вопросов в книге: ${tm.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Закладок: ${book.tabs} из 5" + if (book.fading) " (последняя бледнеет: пора повторить)" else "")
                    nextTab(book)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    Text("Освоено: ${(tm.mastery * 100).roundToInt()}%")
                    Text("Изучено вопросов: ${tm.introduced} из ${tm.total}")
                    if (tm.introduced > 0) Text("Вспомните сегодня: ${(tm.recall * 100).roundToInt()}% изученного")
                    if (tm.due > 0) Text("Ждут повторения: ${tm.due}", color = AtlasColors.Alert)
                }
            }
        },
        confirmButton = {
            if (tm.unlocked) TextButton(onClick = onPractice) { Text("Заняться книгой") }
            else TextButton(onClick = onPretest) { Text("Разведка") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
