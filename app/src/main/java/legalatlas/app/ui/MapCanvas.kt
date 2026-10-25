package legalatlas.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import legalatlas.core.Hex
import legalatlas.core.TopicMastery

private val SQRT3 = sqrt(3f)

/**
 * Карта одного мира. Закрытые области под туманом, открытые светятся
 * пропорционально вероятности вспомнить их содержимое сегодня.
 */
@Composable
fun MapCanvas(
    cells: Map<String, Hex>,
    topics: Map<String, TopicMastery>,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val baseSize = with(LocalDensity.current) { 64.dp.toPx() }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val currentCells by rememberUpdatedState(cells)
    val currentTap by rememberUpdatedState(onTap)

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 2.5f)
                    pan += panChange
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val center = Offset(size.width / 2f, size.height / 2f) + pan
                    val hex = pixelToHex(pos - center, baseSize * scale)
                    currentCells.entries.firstOrNull { it.value == hex }?.let { currentTap(it.key) }
                }
            },
    ) {
        val size = baseSize * scale
        val center = Offset(this.size.width / 2f, this.size.height / 2f) + pan
        val occupied = cells.values.toSet()

        // Пустые соты вокруг — неисследованная территория мира.
        occupied.flatMap { it.neighbors() }.filter { it !in occupied }.toSet().forEach { hex ->
            drawPath(hexPath(center + hexToPixel(hex, size), size * 0.94f), Color.White.copy(alpha = 0.06f), style = Stroke(1.5f))
        }

        for ((id, hex) in cells) {
            val tm = topics[id] ?: continue
            val c = center + hexToPixel(hex, size)
            val path = hexPath(c, size * 0.94f)
            val fill = when {
                !tm.unlocked -> AtlasColors.Fog
                else -> lerp(AtlasColors.Dim, AtlasColors.Gold, tm.mastery.toFloat().coerceIn(0f, 1f).pow(0.7f))
            }
            drawPath(path, fill)
            if (!tm.unlocked) drawFog(c, size)
            val outline = when {
                tm.unlocked && tm.due > 0 -> AtlasColors.Coral
                tm.unlocked -> AtlasColors.Gold.copy(alpha = 0.5f)
                else -> Color.White.copy(alpha = 0.15f)
            }
            drawPath(path, outline, style = Stroke(if (tm.due > 0) 4f else 2f))

            val textColor = if (fill.luminance() > 0.45f) AtlasColors.Night else AtlasColors.Text
            val label = if (tm.unlocked) "${tm.topic.title}\n${(tm.mastery * 100).roundToInt()}%" else "${tm.topic.title}\n?"
            val width = (size * SQRT3 * 0.8f).toInt().coerceAtLeast(1)
            val layout = measurer.measure(
                label,
                style = TextStyle(
                    color = textColor.copy(alpha = if (tm.unlocked) 1f else 0.7f),
                    fontSize = (12 * scale.coerceIn(0.8f, 1.6f)).sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                overflow = TextOverflow.Ellipsis,
                maxLines = 4,
                constraints = Constraints(maxWidth = width, maxHeight = (size * 1.3f).toInt()),
            )
            drawText(layout, topLeft = c - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }
    }
}

private fun DrawScope.drawFog(c: Offset, size: Float) {
    for (i in 0 until 5) {
        val angle = i * 72.0 * PI / 180
        val p = c + Offset((cos(angle) * size * 0.35).toFloat(), (sin(angle) * size * 0.35).toFloat())
        drawCircle(Color.White.copy(alpha = 0.04f), radius = size * 0.45f, center = p)
    }
}

private fun hexToPixel(h: Hex, size: Float) =
    Offset(size * SQRT3 * (h.q + h.r / 2f), size * 1.5f * h.r)

private fun pixelToHex(p: Offset, size: Float): Hex {
    val q = (SQRT3 / 3 * p.x - p.y / 3) / size
    val r = (2f / 3 * p.y) / size
    return cubeRound(q, r)
}

private fun cubeRound(q: Float, r: Float): Hex {
    val s = -q - r
    var rq = q.roundToInt()
    var rr = r.roundToInt()
    val rs = s.roundToInt()
    val dq = abs(rq - q)
    val dr = abs(rr - r)
    val ds = abs(rs - s)
    if (dq > dr && dq > ds) rq = -rr - rs else if (dr > ds) rr = -rq - rs
    return Hex(rq, rr)
}

private fun hexPath(c: Offset, size: Float) = Path().apply {
    for (i in 0 until 6) {
        val angle = (60 * i - 30) * PI / 180
        val x = c.x + size * cos(angle).toFloat()
        val y = c.y + size * sin(angle).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
