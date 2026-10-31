package legalatlas.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import legalatlas.app.AtlasViewModel
import legalatlas.app.Screen
import legalatlas.core.HexLayout
import legalatlas.core.Planner
import legalatlas.core.Progress
import legalatlas.core.TopicMastery

@Composable
fun HomeScreen(vm: AtlasViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val now = remember(state) { System.currentTimeMillis() }
    val planner = remember { Planner() }
    val mastery = remember(state) { Progress.topics(state, now) }
    val worlds = remember(mastery) { mastery.mapNotNull { it.topic.world }.distinct().sorted() }
    var world by rememberSaveable { mutableStateOf<String?>(null) }
    val currentWorld = world?.takeIf { it in worlds } ?: worlds.firstOrNull()
    var selected by remember { mutableStateOf<TopicMastery?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importUri) }

    Column(
        Modifier
            .fillMaxSize()
            .background(AtlasColors.Paper)
            .safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Атлас права", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, contentDescription = "Настройки") }
        }

        if (state.decks.isEmpty()) {
            EmptyAtlas(onSample = vm::importSample, onFile = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) })
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

        if (worlds.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                worlds.forEach { w ->
                    val inWorld = mastery.filter { it.topic.world == w }
                    val pct = if (inWorld.isEmpty()) 0 else (inWorld.sumOf { it.mastery } / inWorld.size * 100).roundToInt()
                    FilterChip(selected = w == currentWorld, onClick = { world = w }, label = { Text("$w · $pct%") })
                }
            }
        } else {
            Text(
                currentWorld.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = AtlasColors.Accent,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        val worldTopics = mastery.filter { it.topic.world == currentWorld }
        val cells = remember(worldTopics.map { it.topic }) { HexLayout.layout(worldTopics.map { it.topic }) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapCanvas(
                cells = cells,
                topics = worldTopics.associateBy { it.topic.id },
                onTap = { id -> selected = worldTopics.firstOrNull { it.topic.id == id } },
                modifier = Modifier.fillMaxSize(),
            )
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

    selected?.let { tm ->
        TopicDialog(
            tm = tm,
            onDismiss = { selected = null },
            onPretest = { selected = null; vm.startPretest(tm.topic.id) },
            onPractice = { selected = null; vm.startSession(15, tm.topic.id) },
        )
    }
}

@Composable
private fun EmptyAtlas(onSample: () -> Unit, onFile: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Карта пока пуста", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Загрузите колоду вопросов, подготовленную в Claude, или начните с примера по исковой давности.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSample, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Загрузить пример") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onFile, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Выбрать файл JSON") }
    }
}

@Composable
private fun TopicDialog(tm: TopicMastery, onDismiss: () -> Unit, onPretest: () -> Unit, onPractice: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tm.topic.title) },
        text = {
            Column {
                if (!tm.unlocked) {
                    Text("Область под туманом. Разведка: несколько вопросов на угадывание до изучения. Ошибаться можно, это помогает запомнить. После разведки область откроется.")
                    Spacer(Modifier.height(8.dp))
                    Text("Вопросов в области: ${tm.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Освоено: ${(tm.mastery * 100).roundToInt()}%")
                    Text("Изучено вопросов: ${tm.introduced} из ${tm.total}")
                    if (tm.introduced > 0) Text("Вспомните сегодня: ${(tm.recall * 100).roundToInt()}% изученного")
                    if (tm.due > 0) Text("Ждут повторения: ${tm.due}", color = AtlasColors.Alert)
                }
            }
        },
        confirmButton = {
            if (tm.unlocked) TextButton(onClick = onPractice) { Text("Заняться областью") }
            else TextButton(onClick = onPretest) { Text("Разведка") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
