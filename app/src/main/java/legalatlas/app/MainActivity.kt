package legalatlas.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import legalatlas.app.ui.AtlasTheme
import legalatlas.app.ui.GenerateScreen
import legalatlas.app.ui.HomeScreen
import legalatlas.app.ui.SessionScreen
import legalatlas.app.ui.SettingsScreen
import legalatlas.app.ui.SummaryScreen

class MainActivity : ComponentActivity() {
    private val vm: AtlasViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent { AtlasTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** «Поделиться»: JSON загружается как колода, документ (PDF, Word, RTF, текст) открывает создание колоды. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        when {
            uri != null -> vm.importUri(uri)
            intent.getStringExtra(Intent.EXTRA_TEXT) != null -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)!!
                // JSON — это колода, любой другой текст — документ для создания вопросов.
                if (text.trimStart().startsWith("{")) vm.importText(text, "текст")
                else vm.openDocumentText(intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Текст", text)
            }
        }
    }
}

@Composable
private fun App(vm: AtlasViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    when (screen) {
        Screen.Home -> HomeScreen(vm)
        Screen.Session -> {
            BackHandler { vm.finish() }
            SessionScreen(vm)
        }
        Screen.Summary -> {
            BackHandler { vm.closeSummary() }
            SummaryScreen(vm)
        }
        Screen.Generate -> {
            BackHandler { vm.closeGenerator() }
            GenerateScreen(vm)
        }
        Screen.Settings -> {
            BackHandler { vm.go(Screen.Home) }
            SettingsScreen(vm)
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = vm::consumeMessage,
            confirmButton = { TextButton(onClick = vm::consumeMessage) { Text("Понятно") } },
            text = { Text(text) },
        )
    }
}
