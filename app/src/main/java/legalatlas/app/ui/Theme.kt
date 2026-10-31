package legalatlas.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// Светлая палитра: бумага, синий акцент, зелёный для верного, коралловый для ошибок.
object AtlasColors {
    val Paper = Color(0xFFF5F7FB)
    val Panel = Color(0xFFFFFFFF)
    val Fog = Color(0xFFE2E7F0)
    val Soft = Color(0xFFE4E0F6)
    val Accent = Color(0xFF2F64C8)
    val Good = Color(0xFF1C8A5B)
    val Alert = Color(0xFFC8463A)
    val Text = Color(0xFF1B2333)
    val Muted = Color(0xFF5E6878)
}

private val colors = lightColorScheme(
    primary = AtlasColors.Accent,
    onPrimary = Color.White,
    secondary = AtlasColors.Good,
    onSecondary = Color.White,
    error = AtlasColors.Alert,
    background = AtlasColors.Paper,
    onBackground = AtlasColors.Text,
    surface = AtlasColors.Panel,
    onSurface = AtlasColors.Text,
    surfaceVariant = AtlasColors.Fog,
    onSurfaceVariant = AtlasColors.Muted,
)

// Шрифт крупнее стандартного: читать вопросы с телефона должно быть легко.
private val base = Typography()
private val typography = Typography(
    headlineSmall = base.headlineSmall.copy(fontSize = 26.sp),
    titleLarge = base.titleLarge.copy(fontSize = 24.sp),
    titleMedium = base.titleMedium.copy(fontSize = 19.sp),
    bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    labelLarge = base.labelLarge.copy(fontSize = 17.sp),
)

@Composable
fun AtlasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography) {
        // Surface задаёт цвет текста и значков по умолчанию для всего приложения.
        Surface(modifier = Modifier.fillMaxSize(), color = AtlasColors.Paper, contentColor = AtlasColors.Text, content = content)
    }
}
