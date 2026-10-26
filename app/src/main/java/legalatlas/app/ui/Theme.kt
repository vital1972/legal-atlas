package legalatlas.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

object AtlasColors {
    val Night = Color(0xFF14161F)
    val Panel = Color(0xFF1E2130)
    val Fog = Color(0xFF2A2E3D)
    val Dim = Color(0xFF3A3550)
    val Gold = Color(0xFFFFC857)
    val Mint = Color(0xFF7FD1B9)
    val Coral = Color(0xFFFF7A6B)
    val Text = Color(0xFFECEAF4)
    val Muted = Color(0xFF9A98AA)
}

private val colors = darkColorScheme(
    primary = AtlasColors.Gold,
    onPrimary = AtlasColors.Night,
    secondary = AtlasColors.Mint,
    onSecondary = AtlasColors.Night,
    error = AtlasColors.Coral,
    background = AtlasColors.Night,
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
        // Без Surface цвет текста и значков по умолчанию чёрный: заголовок и шестерёнка сливаются с тёмным фоном.
        Surface(modifier = Modifier.fillMaxSize(), color = AtlasColors.Night, contentColor = AtlasColors.Text, content = content)
    }
}
