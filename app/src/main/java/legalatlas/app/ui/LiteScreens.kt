package legalatlas.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import legalatlas.app.AtlasViewModel
import legalatlas.app.LiteUi
import legalatlas.app.Screen
import legalatlas.core.Accuracy
import legalatlas.core.DeckFile
import legalatlas.core.Ease
import legalatlas.core.Item
import legalatlas.core.Lite

private val DOC_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/rtf", "text/rtf", "text/plain", "application/json", "*/*",
)

/** Главный экран лайт-режима: две кнопки и список колод. */
@Composable
fun LiteHome(vm: AtlasViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val now = remember(state) { System.currentTimeMillis() }
    val unread = remember(state) { Lite.unread(state).size }
    val due = remember(state) { Lite.due(state, now).size }
    val nextDue = remember(state) { Lite.nextDue(state, now) }
    var selected by remember { mutableStateOf<DeckFile?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importUri) }

    Column(Modifier.fillMaxSize().background(AtlasColors.Paper).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Атлас права", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { picker.launch(DOC_TYPES) }) {
                Icon(Icons.Filled.Add, contentDescription = "Вопросы из документа")
            }
            IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, contentDescription = "Настройки") }
        }
        if (busy) Text("Читаю документ…", color = AtlasColors.Muted, modifier = Modifier.padding(horizontal = 16.dp))

        if (state.decks.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Вопросов пока нет", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Выберите документ (обзор практики, постановление, статью в PDF, Word или RTF) или поделитесь им из другого приложения: DeepSeek составит по нему вопросы.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { picker.launch(DOC_TYPES) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Выбрать документ") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = vm::importSample, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Загрузить пример") }
            }
            return@Column
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction(
                title = "Вспоминать",
                subtitle = when {
                    due > 0 -> "Пора вспомнить: $due"
                    nextDue != null -> "На сегодня всё. Следующее ${Lite.inWords(now, nextDue)}"
                    else -> "Сначала ознакомьтесь с вопросами"
                },
                color = if (due > 0) AtlasColors.Accent else AtlasColors.Muted,
                onClick = { vm.startRecall() },
            )
            BigAction(
                title = "Ознакомиться",
                subtitle = if (unread > 0) "Новых вопросов: $unread" else "Новых вопросов нет",
                color = if (unread > 0) AtlasColors.Good else AtlasColors.Muted,
                onClick = { vm.startReading() },
            )
        }

        Text("Колоды", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            items(state.decks.values.sortedBy { it.deck.title }, key = { it.deck.id }) { d ->
                val dUnread = Lite.unread(state, d.deck.id).size
                val dDue = Lite.due(state, now, d.deck.id).size
                val learned = d.items.count { it.id in state.cards }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(AtlasColors.Panel, RoundedCornerShape(12.dp))
                        .clickable { selected = d }
                        .padding(12.dp),
                ) {
                    Text(d.deck.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Изучено $learned из ${d.items.size}" +
                            (if (dDue > 0) " · вспомнить $dDue" else "") +
                            (if (dUnread > 0) " · новых $dUnread" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    selected?.let { d ->
        val dUnread = Lite.unread(state, d.deck.id).size
        val dDue = Lite.due(state, now, d.deck.id).size
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(d.deck.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { selected = null; vm.startRecall(d.deck.id) },
                        enabled = dDue > 0,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Вспоминать ($dDue)") }
                    OutlinedButton(
                        onClick = { selected = null; vm.startReading(d.deck.id) },
                        enabled = dUnread > 0,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Ознакомиться ($dUnread)") }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun BigAction(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Занятие: ознакомление или вспоминание. */
@Composable
fun LiteScreen(vm: AtlasViewModel) {
    val ui by vm.lite.collectAsStateWithLifecycle()
    val s = ui ?: return
    Column(Modifier.fillMaxSize().background(AtlasColors.Paper).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.title + if (s.current != null) " · ${s.index + 1}/${s.queue.size}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm::liteClose) { Text("Завершить") }
        }
        LinearProgressIndicator(
            progress = { (s.index.toFloat() / s.queue.size).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            color = if (s.reading) AtlasColors.Good else AtlasColors.Accent,
            trackColor = AtlasColors.Fog,
        )
        val item = s.current
        if (item == null) {
            Finished(s, vm)
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            s.lastNote?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = AtlasColors.Muted)
                Spacer(Modifier.height(8.dp))
            }
            Text(item.prompt, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
            Spacer(Modifier.height(16.dp))
            if (s.revealed) AnswerCard(item)
            else Text(
                "Вспомните ответ про себя, затем откройте его.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.padding(16.dp)) {
            when {
                s.reading -> ReadButtons(vm)
                !s.revealed -> Button(onClick = vm::liteReveal, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Показать ответ") }
                !s.fullChosen -> AccuracyButtons(vm)
                else -> EaseButtons(vm)
            }
        }
    }
}

@Composable
private fun AnswerCard(item: Item) {
    Column(
        Modifier.fillMaxWidth().background(AtlasColors.Panel, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Ответ", style = MaterialTheme.typography.labelLarge, color = AtlasColors.Accent)
        Text(item.answer, style = MaterialTheme.typography.bodyLarge)
        if (item.keyPoints.isNotEmpty()) {
            item.keyPoints.forEach { Text("• ${it.text}", color = AtlasColors.Text) }
        }
        (item.rationale ?: item.explanation)?.takeIf { it.isNotBlank() }?.let {
            Text("Почему", style = MaterialTheme.typography.labelLarge, color = AtlasColors.Accent)
            Text(it, color = AtlasColors.Text)
        }
        item.rationaleQuote?.takeIf { it.isNotBlank() }?.let {
            Text("«$it»", style = MaterialTheme.typography.bodyMedium, color = AtlasColors.Muted)
        }
        val source = listOfNotNull(item.source?.title, item.source?.locator).filter { it.isNotBlank() } + item.norms
        if (source.isNotEmpty()) {
            Text("Источник: " + source.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = AtlasColors.Muted)
        }
    }
}

@Composable
private fun ReadButtons(vm: AtlasViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.liteRead(false) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Понятно, дальше") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.liteRead(true) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Уже знаю") }
            OutlinedButton(onClick = vm::liteSkip, modifier = Modifier.weight(1f).height(52.dp)) { Text("Позже") }
        }
        TextButton(onClick = vm::liteFlag, modifier = Modifier.fillMaxWidth()) {
            Text("Вопрос неверен", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccuracyButtons(vm: AtlasViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Насколько точно вспомнили?", style = MaterialTheme.typography.labelLarge, color = AtlasColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("Не вспомнил", AtlasColors.Alert, Modifier.weight(1f)) { vm.liteAccuracy(Accuracy.NONE) }
            ChoiceButton("Частично", AtlasColors.Muted, Modifier.weight(1f)) { vm.liteAccuracy(Accuracy.PARTIAL) }
            ChoiceButton("Полностью", AtlasColors.Good, Modifier.weight(1f)) { vm.liteAccuracy(Accuracy.FULL) }
        }
        TextButton(onClick = vm::liteFlag, modifier = Modifier.fillMaxWidth()) {
            Text("Вопрос неверен", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EaseButtons(vm: AtlasViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Насколько легко вспомнилось?", style = MaterialTheme.typography.labelLarge, color = AtlasColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("С трудом", AtlasColors.Muted, Modifier.weight(1f)) { vm.liteEase(Ease.HARD) }
            ChoiceButton("Нормально", AtlasColors.Accent, Modifier.weight(1f)) { vm.liteEase(Ease.NORMAL) }
            ChoiceButton("Легко", AtlasColors.Good, Modifier.weight(1f)) { vm.liteEase(Ease.EASY) }
        }
        TextButton(onClick = vm::liteBack, modifier = Modifier.fillMaxWidth()) {
            Text("Назад к точности", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChoiceButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) { Text(text, maxLines = 1) }
}

@Composable
private fun Finished(s: LiteUi, vm: AtlasViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Готово", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        if (s.reading) {
            Text("Прочитано вопросов: ${s.done}. Завтра они появятся во «Вспоминать».", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("Ответов: ${s.done}, вспомнили полностью: ${s.remembered}.", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::liteClose, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("На главный экран") }
    }
}
