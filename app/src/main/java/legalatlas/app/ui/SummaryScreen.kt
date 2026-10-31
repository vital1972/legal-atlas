package legalatlas.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import legalatlas.app.AtlasViewModel

@Composable
fun SummaryScreen(vm: AtlasViewModel) {
    val ui by vm.session.collectAsStateWithLifecycle()
    val s = ui
    Column(
        Modifier
            .fillMaxSize()
            .background(AtlasColors.Paper)
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Итог", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        if (s != null) {
            val minutes = ((System.currentTimeMillis() - s.startedAt) / 60_000).coerceAtLeast(1)
            Text("Ответов: ${s.answered} за $minutes мин", style = MaterialTheme.typography.bodyLarge)
            if (s.answered > 0) {
                Text("Вспомнили: ${s.correct} (${s.correct * 100 / s.answered}%)", style = MaterialTheme.typography.bodyLarge)
            }
            if (s.pretestTopic != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Область открыта. Её вопросы начнут появляться в обычных сессиях как новые.",
                    color = AtlasColors.Accent,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (s.confidentErrors.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Уверенные ошибки", style = MaterialTheme.typography.titleMedium, color = AtlasColors.Alert)
                Text(
                    "Вы были уверены, но ответили неверно. Эти вопросы вернутся уже сегодня.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                s.confidentErrors.forEach { item ->
                    Spacer(Modifier.height(10.dp))
                    Text(item.prompt, style = MaterialTheme.typography.bodyMedium)
                    Text(item.answer, style = MaterialTheme.typography.bodyMedium, color = AtlasColors.Good)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::closeSummary, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("К карте") }
    }
}
