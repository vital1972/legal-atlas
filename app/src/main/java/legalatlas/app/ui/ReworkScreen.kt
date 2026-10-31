package legalatlas.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import legalatlas.app.AtlasViewModel
import legalatlas.app.Screen

/**
 * Переработка колод: лёгкие вопросы можно убрать из повторений, к трудным — добавить
 * новые вопросы с другой стороны. Всё отмечено заранее, пользователь только снимает лишнее.
 */
@Composable
fun ReworkScreen(vm: AtlasViewModel) {
    val analysis by vm.rework.collectAsStateWithLifecycle()
    val a = analysis ?: return
    var easyPicked by remember(a) { mutableStateOf(a.easy.map { it.id }.toSet()) }
    var weakPicked by remember(a) { mutableStateOf(a.weak.map { it.item.id }.toSet()) }

    Column(Modifier.fillMaxSize().background(AtlasColors.Paper).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Text("Переработка колод", style = MaterialTheme.typography.titleLarge)
        }
        if (a.easy.isEmpty() && a.weak.isEmpty()) {
            Text(
                "Пока нечего перерабатывать. Лёгкие вопросы появятся после 4–5 уверенных повторений, " +
                    "трудные — после повторных ошибок. Загляните через пару недель занятий.",
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a.weak.isNotEmpty()) {
                item {
                    Section(
                        "Трудные: ${a.weak.size}",
                        "К отмеченным DeepSeek придумает 1–2 новых вопроса с другой стороны: различение, применение, исключения. " +
                            "Перед добавлением вы их увидите.",
                    )
                }
                items(a.weak, key = { "w-" + it.item.id }) { w ->
                    PickRow(w.item.prompt, w.reasons.joinToString(" · "), w.item.id in weakPicked) {
                        weakPicked = if (w.item.id in weakPicked) weakPicked - w.item.id else weakPicked + w.item.id
                    }
                }
                item {
                    Button(
                        onClick = { vm.deepen(weakPicked) },
                        enabled = weakPicked.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Углубить отмеченные (${weakPicked.size})") }
                }
            }
            if (a.easy.isNotEmpty()) {
                item {
                    Section(
                        "Даются легко: ${a.easy.size}",
                        "Отвечаете уверенно и верно не меньше трёх раз подряд, интервал больше двух месяцев. " +
                            "Их можно убрать из повторений и освободить время для трудного.",
                    )
                }
                items(a.easy, key = { "e-" + it.id }) { item ->
                    PickRow(item.prompt, null, item.id in easyPicked) {
                        easyPicked = if (item.id in easyPicked) easyPicked - item.id else easyPicked + item.id
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { vm.retire(easyPicked) },
                        enabled = easyPicked.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp).padding(bottom = 4.dp),
                    ) { Text("Убрать из повторений (${easyPicked.size})") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, note: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = AtlasColors.Accent)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PickRow(text: String, note: String?, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AtlasColors.Panel, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = AtlasColors.Alert) }
        }
    }
}
