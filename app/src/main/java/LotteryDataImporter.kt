package com.example.entropyrng.import

import android.content.Context
import android.net.Uri
import com.example.entropyrng.data.AppDatabase
import com.example.entropyrng.data.NumberData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

/**
 * Импортер данных лотереи из CSV файлов
 *
 * Поддерживаемый формат (results_all_clear):
 *   008938,17 июля 2025,19:54 МСК,"8, 11, 13, 14","11, 1, 8, 10"
 *
 * Логика импорта:
 *   - Парсит CSV с учётом кавычек (поля могут содержать запятые)
 *   - Пропускает тираж если он уже есть И запись полная (8 чисел)
 *   - Обновляет тираж если он есть, но запись неполная (< 8 чисел)
 *   - Добавляет тираж если его нет совсем
 */
class LotteryDataImporter(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)

    // ─────────────────────────────────────────────────────────────
    //  Публичный метод
    // ─────────────────────────────────────────────────────────────

    /**
     * @param lotteryType к какому формату лотереи относится файл, например
     *   "Премьер 4х20" или "Премьер 4х17" — записывается в NumberData.lotteryType
     *   и используется для проверки дублей (тиражи разных форматов нумеруются независимо).
     * @param maxNumber верхняя граница диапазона чисел для этого формата (20 для 4х20, 17 для 4х17).
     *   Числа вне 1..maxNumber в строке считаются ошибкой парсинга поля.
     */
    suspend fun importFromCsv(
        uri: Uri,
        lotteryType: String,
        maxNumber: Int
    ): ImportResult = withContext(Dispatchers.IO) {
        var added    = 0
        var updated  = 0
        var skipped  = 0
        var errors   = 0
        var lineNum  = 0

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->

                    // Пропускаем заголовок если он есть
                    val firstLine = reader.readLine() ?: return@use
                    lineNum++

                    // Определяем — заголовок это или данные
                    val startLine = if (isHeaderLine(firstLine)) {
                        reader.readLine()  // пропустили заголовок, читаем первую строку данных
                    } else {
                        firstLine          // заголовка нет — первая строка уже данные
                    }

                    var line: String? = startLine

                    while (line != null) {
                        lineNum++
                        val trimmed = line.trim()

                        if (trimmed.isNotEmpty()) {
                            try {
                                val parsed = parseLine(trimmed, lotteryType, maxNumber)

                                if (parsed != null) {
                                    val result = upsertDraw(parsed)
                                    when (result) {
                                        UpsertAction.ADDED   -> added++
                                        UpsertAction.UPDATED -> updated++
                                        UpsertAction.SKIPPED -> skipped++
                                    }
                                } else {
                                    errors++
                                }
                            } catch (e: Exception) {
                                errors++
                            }
                        }

                        line = reader.readLine()
                    }
                }
            }

            ImportResult(
                success       = true,
                addedCount    = added,
                updatedCount  = updated,
                skippedCount  = skipped,
                errorCount    = errors,
                message       = "Добавлено: $added | Обновлено: $updated | Пропущено: $skipped | Ошибок: $errors"
            )

        } catch (e: Exception) {
            ImportResult(
                success       = false,
                addedCount    = added,
                updatedCount  = updated,
                skippedCount  = skipped,
                errorCount    = errors,
                message       = "Критическая ошибка: ${e.message}"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Upsert: добавить / обновить / пропустить
    // ─────────────────────────────────────────────────────────────

    private suspend fun upsertDraw(parsed: NumberData): UpsertAction {
        // Ищем дубль в пределах ТОГО ЖЕ формата лотереи — у 4х20 и 4х17
        // независимая нумерация тиражей, совпадение iteration между ними ничего не значит.
        val existing = db.numberDataDao().getByIterationAndType(parsed.iteration, parsed.lotteryType ?: "")

        return when {
            // Записи нет — просто вставляем
            existing == null -> {
                db.numberDataDao().insert(parsed)
                UpsertAction.ADDED
            }

            // Запись есть, но неполная (< 8 чисел) — обновляем
            existing.numbers.size < 8 -> {
                db.numberDataDao().insert(parsed.copy(id = existing.id))
                UpsertAction.UPDATED
            }

            // Запись есть и полная — пропускаем
            else -> UpsertAction.SKIPPED
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Парсинг строки CSV с учётом кавычек
    // ─────────────────────────────────────────────────────────────

    /**
     * Правильный CSV-парсер: понимает поля в кавычках с запятыми внутри.
     *
     * Пример:
     *   008938,17 июля 2025,19:54 МСК,"8, 11, 13, 14","11, 1, 8, 10"
     *   →  ["008938", "17 июля 2025", "19:54 МСК", "8, 11, 13, 14", "11, 1, 8, 10"]
     */
    private fun splitCsvLine(line: String): List<String> {
        val result  = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        for (ch in line) {
            when {
                ch == '"'  -> inQuotes = !inQuotes          // вход/выход из кавычек
                ch == ',' && !inQuotes -> {                 // разделитель вне кавычек
                    result.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        result.add(current.toString().trim())               // последнее поле
        return result
    }

    /**
     * Парсим строку в NumberData.
     *
     * Формат results_all_clear:
     *   col0 = номер тиража   (008938)
     *   col1 = дата           (17 июля 2025)
     *   col2 = время          (19:54 МСК)
     *   col3 = поле 1         (8, 11, 13, 14)   — кавычки уже сняты парсером
     *   col4 = поле 2         (11, 1, 8, 10)
     */
    private fun parseLine(line: String, lotteryType: String, maxNumber: Int): NumberData? {
        val cols = splitCsvLine(line)
        if (cols.size < 5) return null

        val iteration = cols[0].trim()
        if (iteration.isEmpty()) return null

        val dateStr = cols[1].trim()
        val timeStr = cols[2].trim().replace(" МСК", "").trim()

        // Парсим числа из обоих полей
        val field1 = parseNumberList(cols[3], maxNumber)
        val field2 = parseNumberList(cols[4], maxNumber)

        if (field1.isEmpty() && field2.isEmpty()) return null

        val numbers = field1 + field2

        // Дата → yyyy-MM-dd
        val date = parseDateRussian(dateStr)
        // Время → HH:mm:ss
        val time = parseTime(timeStr)

        return NumberData(
            iteration   = iteration,
            date        = date,
            time        = time,
            numbers     = numbers,
            source      = "imported",
            lotteryType = lotteryType,
            metadata    = "Imported from results CSV"
        )
    }

    /**
     * Парсит строку вида "8, 11, 13, 14" → listOf(8, 11, 13, 14)
     */
    private fun parseNumberList(raw: String, maxNumber: Int): List<Int> =
        raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..maxNumber }

    // ─────────────────────────────────────────────────────────────
    //  Дата / время
    // ─────────────────────────────────────────────────────────────

    private fun parseDateRussian(dateStr: String): String {
        return try {
            val parts = dateStr.trim().split(" ")
            // Ожидаем "17 июля 2025"
            if (parts.size >= 3) {
                val day   = parts[0].toIntOrNull() ?: 1
                val month = russianMonthToNumber(parts[1])
                val year  = parts[2].toIntOrNull() ?: 2025
                String.format("%04d-%02d-%02d", year, month, day)
            } else {
                dateStr  // вернём как есть если не распознали
            }
        } catch (e: Exception) {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            fmt.format(Date())
        }
    }

    private fun parseTime(timeStr: String): String {
        return try {
            val parts = timeStr.split(":")
            if (parts.size >= 2) {
                String.format(
                    "%02d:%02d:00",
                    parts[0].trim().toIntOrNull() ?: 0,
                    parts[1].trim().toIntOrNull() ?: 0
                )
            } else "00:00:00"
        } catch (e: Exception) {
            "00:00:00"
        }
    }

    private fun russianMonthToNumber(month: String): Int =
        when (month.lowercase().trim()) {
            "января"   -> 1
            "февраля"  -> 2
            "марта"    -> 3
            "апреля"   -> 4
            "мая"      -> 5
            "июня"     -> 6
            "июля"     -> 7
            "августа"  -> 8
            "сентября" -> 9
            "октября"  -> 10
            "ноября"   -> 11
            "декабря"  -> 12
            else       -> 1
        }

    // ─────────────────────────────────────────────────────────────
    //  Вспомогательные
    // ─────────────────────────────────────────────────────────────

    /**
     * Определяем является ли строка заголовком (не содержит числовой iteration)
     */
    private fun isHeaderLine(line: String): Boolean {
        val firstCol = line.split(",").firstOrNull()?.trim() ?: return true
        return firstCol.toIntOrNull() == null && !firstCol.matches(Regex("\\d{6}"))
    }

    private enum class UpsertAction { ADDED, UPDATED, SKIPPED }
}

// ─────────────────────────────────────────────────────────────────
//  Результат импорта (расширенный)
// ─────────────────────────────────────────────────────────────────

data class ImportResult(
    val success      : Boolean,
    val addedCount   : Int,
    val updatedCount : Int,
    val skippedCount : Int,
    val errorCount   : Int,
    val message      : String
)
