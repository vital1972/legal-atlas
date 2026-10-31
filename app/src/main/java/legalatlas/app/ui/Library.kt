package legalatlas.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import legalatlas.core.Book

/** Цвета обложек по отраслям и цвета закладок: от первой (красной) до пятой (фиолетовой). */
object LibraryColors {
    val covers = listOf(
        Color(0xFF2F64C8), Color(0xFF1C8A5B), Color(0xFF8A4FBF), Color(0xFF0F7F8C),
        Color(0xFFC0672B), Color(0xFFB0405A),
    )
    val tabs = listOf(Color(0xFFE85D4A), Color(0xFFF0A830), Color(0xFF3FAE6A), Color(0xFF2F86D6), Color(0xFF8A4FBF))
    val Page = Color(0xFFFFFDF8)
    val Shelf = Color(0xFFC9B79C)
    val ShelfEdge = Color(0xFFA8946F)
    val Spiral = Color(0xFF7A8394)
}

private val BookWidth = 84.dp
private val BookHeight = 112.dp
private val TabPeek = 9.dp
private val BookGap = 16.dp

/** Полка отрасли: книги рядами, под каждым рядом — доска полки. */
@Composable
fun Shelf(world: String, color: Color, books: List<Book>, onOpen: (Book) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            world.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = AtlasColors.Muted,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = ((maxWidth - 16.dp + BookGap) / (BookWidth + BookGap)).toInt().coerceAtLeast(1)
            Column {
                books.chunked(perRow).forEach { row ->
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(BookGap),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        row.forEach { BookView(it, color, onClick = { onOpen(it) }) }
                    }
                    Box(Modifier.fillMaxWidth().height(10.dp).background(LibraryColors.Shelf))
                    Box(Modifier.fillMaxWidth().height(4.dp).background(LibraryColors.ShelfEdge))
                }
            }
        }
    }
}

/**
 * Книга на пружине. Закрыта (обложка), если тема не начата или прочитана; иначе раскрыта на листе.
 * Ярлычки вклеены за верхний край: снаружи видна верхушка, ниже края — наклеенная часть.
 */
@Composable
fun BookView(book: Book, color: Color, onClick: () -> Unit) {
    val title = book.mastery.topic.title
    val closed = book.locked || book.read
    val cover = if (book.locked) lerp(color, Color(0xFF9AA3B2), 0.7f) else color
    Box(
        Modifier
            .width(BookWidth)
            .height(BookHeight + TabPeek)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title, закладок ${book.tabs} из 5" },
    ) {
        val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 7.dp, bottomEnd = 7.dp)
        Box(
            Modifier
                .offset(y = TabPeek)
                .size(BookWidth, BookHeight)
                .then(
                    if (closed) Modifier.background(cover, shape)
                    else Modifier.background(LibraryColors.Page, shape).border(2.dp, cover, shape),
                ),
        ) {
            if (!closed) {
                Canvas(Modifier.fillMaxSize().padding(start = 14.dp, end = 10.dp, top = 16.dp, bottom = 40.dp)) {
                    var y = 0f
                    val step = 8.dp.toPx()
                    while (y < size.height) {
                        drawLine(Color(0xFFDFE4EC), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        y += step
                    }
                }
            }
            if (book.locked) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(18.dp),
                )
            }
            Text(
                title,
                color = if (closed) Color.White else AtlasColors.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall.copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 13.dp, end = 6.dp, bottom = 8.dp),
            )
        }
        // Пружина: колечки заходят за левый край книги.
        Canvas(Modifier.offset(x = (-5).dp, y = TabPeek + 7.dp).size(11.dp, BookHeight - 14.dp)) {
            val ring = 3.dp.toPx()
            val step = 8.dp.toPx()
            var y = 0f
            while (y + ring <= size.height) {
                drawRoundRect(LibraryColors.Spiral, Offset(0f, y), Size(size.width, ring), CornerRadius(ring / 2))
                y += step
            }
        }
        // Ярлычки поверх книги: верхушка торчит, нижняя часть наклеена и чуть светлее.
        Row(Modifier.padding(start = 18.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 0 until book.tabs) {
                val faded = book.fading && i == book.tabs - 1
                val tab = LibraryColors.tabs[i]
                Canvas(Modifier.size(10.dp, 24.dp)) {
                    val alpha = if (faded) 0.35f else 1f
                    val peek = TabPeek.toPx()
                    drawRoundRect(tab.copy(alpha = alpha), Offset.Zero, Size(size.width, peek + 2f), CornerRadius(2.dp.toPx()))
                    drawRect(lerp(tab, Color.White, 0.22f).copy(alpha = alpha), Offset(0f, peek), Size(size.width, size.height - peek))
                }
            }
        }
    }
}
