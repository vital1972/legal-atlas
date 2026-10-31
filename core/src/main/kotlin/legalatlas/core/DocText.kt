package legalatlas.core

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Текст документа для создания колоды: Word (.docx), RTF (так сохраняет КонсультантПлюс)
 * и обычный текст. PDF разбирается в приложении отдельной библиотекой.
 */
object DocText {

    /** Абзацы из word/document.xml: текст из <w:t>, табуляции и переносы внутри абзаца — пробелы. */
    fun docx(bytes: ByteArray): String {
        val xml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { it.name == "word/document.xml" }
                ?.let { zip.readBytes().toString(Charsets.UTF_8) }
        } ?: throw IllegalArgumentException("Это не документ Word (.docx)")
        val out = StringBuilder()
        val paragraph = Regex("<w:p[ >].*?</w:p>|<w:p/>", RegexOption.DOT_MATCHES_ALL)
        val run = Regex("<w:t(?: [^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>", RegexOption.DOT_MATCHES_ALL)
        for (p in paragraph.findAll(xml)) {
            val line = run.findAll(p.value).joinToString("") { m ->
                if (m.value.startsWith("<w:t")) unescapeXml(m.groupValues[1]) else " "
            }.trim()
            if (line.isNotEmpty()) out.append(line).append('\n')
        }
        return out.toString().trim()
    }

    private fun unescapeXml(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("&amp;", "&")

    /**
     * Простой разбор RTF: служебные группы (шрифты, стили, сведения) пропускаются,
     * \'hh — байт в кодировке документа (\ansicpgNNNN, по умолчанию 1251), \uN — символ Юникода.
     */
    fun rtf(bytes: ByteArray): String {
        val src = String(bytes, Charsets.ISO_8859_1)
        var charset: Charset = Charset.forName("windows-1251")
        Regex("\\\\ansicpg(\\d+)").find(src)?.let { m ->
            runCatching { Charset.forName("windows-" + m.groupValues[1]) }.getOrNull()?.let { charset = it }
        }
        val skipWords = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer",
            "headerl", "headerr", "footerl", "footerr", "listtable", "listoverridetable", "rsidtbl", "xmlnstbl",
            "generator", "themedata", "colorschememapping", "latentstyles", "datastore", "object")
        val out = StringBuilder()
        val pending = java.io.ByteArrayOutputStream()
        fun flush() {
            if (pending.size() > 0) { out.append(String(pending.toByteArray(), charset)); pending.reset() }
        }
        // На каждый уровень вложенности: пропускать ли текст и сколько символов пропустить после \uN.
        val skipStack = ArrayDeque<Boolean>()
        var skip = false
        var uc = 1
        var toSkip = 0
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '{' -> { skipStack.addLast(skip); i++
                    if (src.startsWith("\\*", i)) skip = true
                }
                c == '}' -> { flush(); skip = skipStack.removeLastOrNull() ?: false; i++ }
                c == '\\' -> {
                    val next = src.getOrNull(i + 1) ?: break
                    when {
                        next == '\'' -> {
                            val hex = src.substring(i + 2, minOf(i + 4, src.length))
                            if (toSkip > 0) toSkip-- else if (!skip) hex.toIntOrNull(16)?.let { pending.write(it) }
                            i += 4
                        }
                        next == '\\' || next == '{' || next == '}' -> {
                            if (!skip) { flush(); out.append(next) }
                            i += 2
                        }
                        next == '~' -> { if (!skip) { flush(); out.append(' ') }; i += 2 }
                        next == '-' || next == '_' -> { if (!skip && next == '_') { flush(); out.append('-') }; i += 2 }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < src.length && src[j].isLetter()) j++
                            val word = src.substring(i + 1, j)
                            var k = j
                            if (k < src.length && (src[k] == '-' || src[k].isDigit())) {
                                k++
                                while (k < src.length && src[k].isDigit()) k++
                            }
                            val arg = src.substring(j, k).toIntOrNull()
                            if (k < src.length && src[k] == ' ') k++
                            i = k
                            if (word in skipWords) { skip = true; continue }
                            if (skip) continue
                            when (word) {
                                "par", "line", "sect", "page", "row" -> { flush(); out.append('\n') }
                                "tab", "cell" -> { flush(); out.append(' ') }
                                "emdash" -> { flush(); out.append('—') }
                                "endash" -> { flush(); out.append('–') }
                                "lquote", "rquote" -> { flush(); out.append('\'') }
                                "ldblquote" -> { flush(); out.append('«') }
                                "rdblquote" -> { flush(); out.append('»') }
                                "bullet" -> { flush(); out.append('•') }
                                "uc" -> uc = arg ?: 1
                                "u" -> if (arg != null) {
                                    flush(); out.append((if (arg < 0) arg + 65536 else arg).toChar()); toSkip = uc
                                }
                            }
                        }
                        else -> i += 2
                    }
                }
                c == '\r' || c == '\n' -> i++
                else -> {
                    if (toSkip > 0) toSkip-- else if (!skip) { flush(); out.append(c) }
                    i++
                }
            }
        }
        flush()
        return out.toString().lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** По имени файла и началу содержимого. PDF сюда не входит. */
    fun extract(name: String, bytes: ByteArray): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".docx") || (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) -> docx(bytes)
            lower.endsWith(".rtf") || String(bytes.take(5).toByteArray(), Charsets.ISO_8859_1) == "{\\rtf" -> rtf(bytes)
            else -> decodeText(bytes)
        }
    }

    /** Обычный текст: UTF-8, а если он не читается — Windows-1251. */
    fun decodeText(bytes: ByteArray): String {
        val utf = bytes.toString(Charsets.UTF_8)
        return if ('�' in utf) String(bytes, Charset.forName("windows-1251")) else utf.removePrefix("﻿")
    }

    /** Делит текст на части примерно равной длины по границам абзацев. */
    fun split(text: String, parts: Int): List<String> {
        if (parts <= 1) return listOf(text)
        val target = text.length / parts
        val paragraphs = text.split('\n')
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (p in paragraphs) {
            if (cur.length >= target && out.size < parts - 1) {
                out += cur.toString().trim(); cur.clear()
            }
            // Очень длинный абзац режем по предложениям, чтобы часть не разрасталась.
            if (p.length > target * 2) {
                p.split(Regex("(?<=[.;])\\s+")).forEach { s ->
                    if (cur.length >= target && out.size < parts - 1) { out += cur.toString().trim(); cur.clear() }
                    cur.append(s).append(' ')
                }
                cur.append('\n')
            } else {
                cur.append(p).append('\n')
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotBlank() }
    }
}
