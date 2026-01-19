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
 * Поддерживает формат из NumberPredictionApp и lottery_stats.csv
 */
class LotteryDataImporter(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)

    /**
     * Импортировать данные из CSV файла
     * @param uri URI файла, выбранного пользователем
     * @return количество импортированных записей
     */
    suspend fun importFromCsv(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val dataList = mutableListOf<NumberData>()
        var errorCount = 0
        var lineNumber = 0

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    // Пропускаем заголовок
                    reader.readLine()
                    lineNumber++

                    reader.forEachLine { line ->
                        lineNumber++
                        try {
                            val data = parseCsvLine(line)
                            if (data != null) {
                                dataList.add(data)
                            }
                        } catch (e: Exception) {
                            errorCount++
                            // Логируем ошибку, но продолжаем импорт
                        }
                    }
                }
            }

            // Сохраняем в базу данных
            if (dataList.isNotEmpty()) {
                db.numberDataDao().insertAll(dataList)
            }

            ImportResult(
                success = true,
                importedCount = dataList.size,
                errorCount = errorCount,
                message = "Импортировано ${dataList.size} записей"
            )

        } catch (e: Exception) {
            ImportResult(
                success = false,
                importedCount = 0,
                errorCount = errorCount,
                message = "Ошибка импорта: ${e.message}"
            )
        }
    }

    /**
     * Парсинг одной строки CSV
     * Поддерживает два формата:
     * 1. lottery_stats.csv: lottery,draw_number,draw_date_time,my_field1,my_field2,win_field1,win_field2,win_amount
     * 2. NumberPredictionApp: id_тиража,дата,время,число1,число2,...
     */
    private fun parseCsvLine(line: String): NumberData? {
        val columns = line.split(",").map { it.trim().replace("\"", "") }

        if (columns.size < 3) return null

        // Определяем формат
        return when {
            // Формат lottery_stats.csv
            columns.size >= 8 && columns[0].isNotEmpty() -> {
                parseLotteryStatsFormat(columns)
            }
            // Формат NumberPredictionApp (минимум: тираж, дата, время + числа)
            columns.size >= 5 -> {
                parseNumberPredictionFormat(columns)
            }
            else -> null
        }
    }

    /**
     * Парсинг формата lottery_stats.csv
     */
    private fun parseLotteryStatsFormat(columns: List<String>): NumberData? {
        try {
            val lotteryType = columns[0]
            val drawNumber = columns[1]
            val dateTimeStr = columns[2]

            // Парсинг даты и времени из формата "14 октября 2025 14:09 МСК"
            val (date, time) = parseDateTimeRussian(dateTimeStr)

            // Извлекаем числа из поля win_field1
            val numbers = extractNumbersFromField(columns.getOrNull(5) ?: "")

            if (numbers.isEmpty()) return null

            return NumberData(
                iteration = drawNumber,
                date = date,
                time = time,
                numbers = numbers,
                source = "imported",
                lotteryType = lotteryType,
                metadata = "Imported from lottery_stats.csv"
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Парсинг формата NumberPredictionApp
     */
    private fun parseNumberPredictionFormat(columns: List<String>): NumberData? {
        try {
            val iteration = columns[0]
            val date = columns[1]
            val time = columns[2].replace(" МСК", "")

            // Все остальные колонки - это числа
            val numbers = columns.subList(3, columns.size.coerceAtMost(11))
                .mapNotNull { it.toIntOrNull() }
                .filter { it > 0 }

            if (numbers.isEmpty()) return null

            return NumberData(
                iteration = iteration,
                date = date,
                time = time,
                numbers = numbers,
                source = "imported",
                metadata = "Imported from NumberPredictionApp CSV"
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Извлечь числа из строки формата "Номер билета:,1041 3111 0000 2131 1877,..."
     */
    private fun extractNumbersFromField(field: String): List<Int> {
        // Простейшее извлечение: берём все числа от 1 до 100
        return field.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..100 }
            .distinct()
    }

    /**
     * Парсинг русской даты "14 октября 2025 14:09 МСК"
     * Возвращает пару (date: "yyyy-MM-dd", time: "HH:mm:ss")
     */
    private fun parseDateTimeRussian(dateTimeStr: String): Pair<String, String> {
        try {
            // Убираем " МСК"
            val cleaned = dateTimeStr.replace(" МСК", "").trim()

            // Разделяем на дату и время
            val parts = cleaned.split(" ")
            if (parts.size < 4) throw Exception("Invalid format")

            val day = parts[0].toIntOrNull() ?: 1
            val monthStr = parts[1]
            val year = parts[2].toIntOrNull() ?: 2025
            val timeStr = parts[3]

            // Конвертация месяца
            val month = russianMonthToNumber(monthStr)

            // Форматируем дату
            val date = String.format("%04d-%02d-%02d", year, month, day)

            // Форматируем время
            val timeParts = timeStr.split(":")
            val time = if (timeParts.size >= 2) {
                String.format("%02d:%02d:00",
                    timeParts[0].toIntOrNull() ?: 0,
                    timeParts[1].toIntOrNull() ?: 0
                )
            } else {
                "00:00:00"
            }

            return Pair(date, time)
        } catch (e: Exception) {
            // Fallback на текущую дату
            val cal = Calendar.getInstance()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return Pair(dateFormat.format(cal.time), timeFormat.format(cal.time))
        }
    }

    /**
     * Конвертация русского названия месяца в номер
     */
    private fun russianMonthToNumber(month: String): Int {
        return when (month.lowercase()) {
            "января" -> 1
            "февраля" -> 2
            "марта" -> 3
            "апреля" -> 4
            "мая" -> 5
            "июня" -> 6
            "июля" -> 7
            "августа" -> 8
            "сентября" -> 9
            "октября" -> 10
            "ноября" -> 11
            "декабря" -> 12
            else -> 1
        }
    }
}

/**
 * Результат импорта
 */
data class ImportResult(
    val success: Boolean,
    val importedCount: Int,
    val errorCount: Int,
    val message: String
)
