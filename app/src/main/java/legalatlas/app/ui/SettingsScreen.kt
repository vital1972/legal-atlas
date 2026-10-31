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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlin.math.roundToInt
import legalatlas.app.AtlasViewModel
import legalatlas.app.Screen

@Composable
fun SettingsScreen(vm: AtlasViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val keyHint by vm.graderKeyHint.collectAsStateWithLifecycle()
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    var graderKey by remember { mutableStateOf("") }
    val settings = state.settings
    var url by remember { mutableStateOf(settings.serverUrl) }
    var token by remember { mutableStateOf(settings.token) }
    var newPerDay by remember { mutableFloatStateOf(settings.newPerDay.toFloat()) }
    var goal by remember { mutableFloatStateOf(settings.weeklyGoalMinutes.toFloat()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importUri) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::writeExport)
    }
    val backupWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::writeBackup)
    }
    val backupReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::readBackup) }

    fun save() = vm.saveSettings(
        settings.copy(
            serverUrl = url.trim(),
            token = token.trim(),
            newPerDay = newPerDay.roundToInt(),
            weeklyGoalMinutes = goal.roundToInt(),
        ),
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(AtlasColors.Paper)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { save(); vm.go(Screen.Home) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Настройки", style = MaterialTheme.typography.titleLarge)
        }

        Text("Оценка ответов", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
        Text(
            if (keyHint != null) "Ключ DeepSeek сохранён ($keyHint). Развёрнутые ответы оцениваются прямо с телефона."
            else "Вставьте ключ DeepSeek (platform.deepseek.com → API keys), и развёрнутые ответы будут оцениваться без сервера.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = graderKey, onValueChange = { graderKey = it },
            label = { Text(if (keyHint != null) "Новый ключ DeepSeek" else "Ключ DeepSeek") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveGraderKey(graderKey); graderKey = "" }, enabled = graderKey.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text("Сохранить ключ")
            }
            if (keyHint != null) {
                OutlinedButton(onClick = { vm.saveGraderKey("") }, modifier = Modifier.weight(1f)) { Text("Удалить ключ") }
            }
        }
        Text(
            "Ключ хранится в зашифрованном виде только на этом телефоне и не попадает в резервную копию. Поставьте в кабинете DeepSeek лимит расходов.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("Сервер (необязательно)", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
        Text(
            "Нужен только для загрузки вопросов со страницы на компьютере. Если ключ DeepSeek сохранён выше, оценка идёт без сервера.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it }, label = { Text("Адрес, например https://legal-atlas.xxx.workers.dev") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, label = { Text("Ключ доступа (APP_TOKEN)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(onClick = { save(); vm.sync() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (busy) "Синхронизация…" else "Сохранить и синхронизировать")
        }

        HorizontalDivider()
        Text("Нагрузка", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
        Text("Новых вопросов в день: ${newPerDay.roundToInt()}")
        Slider(value = newPerDay, onValueChange = { newPerDay = it }, valueRange = 0f..30f, steps = 29, onValueChangeFinished = ::save)
        Text(
            "Через месяц на каждый новый вопрос в день приходится 7–10 повторений, поэтому больше 15 при 30 минутах не рекомендую.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Цель на неделю: ${goal.roundToInt()} мин")
        Slider(value = goal, onValueChange = { goal = it }, valueRange = 30f..300f, steps = 26, onValueChangeFinished = ::save)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Обоснование «почему» (метод Фейнмана)", modifier = Modifier.weight(1f))
            Switch(checked = settings.feynman, onCheckedChange = { on -> vm.saveSettings(settings.copy(feynman = on)) })
        }
        Text(
            "Часть развёрнутых вопросов после ответа просит объяснить, почему он такой. Такие вопросы занимают не больше трети сессии.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()
        Text("Колоды", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
        state.decks.values.sortedBy { it.deck.title }.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.deck.title)
                    Text("${d.deck.world} · ${d.items.size} вопросов", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.deleteDeck(d.deck.id) }) { Text("Удалить") }
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/rtf", "text/rtf", "text/plain", "application/json", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Документ или файл колоды")
        }
        Button(onClick = vm::openRework, modifier = Modifier.fillMaxWidth()) { Text("Переработать колоды по результатам") }
        OutlinedButton(onClick = vm::importSample, modifier = Modifier.fillMaxWidth()) { Text("Загрузить пример") }
        if (state.flaggedItems.isNotEmpty()) {
            OutlinedButton(onClick = vm::unflagAll, modifier = Modifier.fillMaxWidth()) {
                Text("Вернуть отмеченные вопросы (${state.flaggedItems.size})")
            }
        }
        if (state.retiredItems.isNotEmpty()) {
            OutlinedButton(onClick = vm::restoreRetired, modifier = Modifier.fillMaxWidth()) {
                Text("Вернуть убранные лёгкие вопросы (${state.retiredItems.size})")
            }
        }

        HorizontalDivider()
        Text("Резервная копия", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
        Text(
            "Колоды и прогресс автоматически сохраняются в резервную копию Google, если она включена в настройках телефона. Дополнительно можно сохранить копию в файл.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { backupWriter.launch("legal-atlas-backup-${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) {
            Text("Сохранить копию в файл")
        }
        OutlinedButton(onClick = { backupReader.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Восстановить из файла")
        }

        HorizontalDivider()
        Text("Отчёт", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
        Text(
            "Файл с историей ответов и состоянием каждого вопроса. Отдайте его Claude, чтобы найти слабые места и дописать вопросы.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { exporter.launch("legal-atlas-${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) {
            Text("Сохранить отчёт")
        }
        Spacer(Modifier.height(24.dp))
    }

    pendingRestore?.let { restored ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text("Восстановить из копии?") },
            text = {
                Text(
                    "В копии колод: ${restored.decks.size}, ответов в журнале: ${restored.logs.size}. " +
                        "Всё, что сейчас в приложении, будет заменено содержимым копии.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Восстановить") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Отмена") } },
        )
    }
}
