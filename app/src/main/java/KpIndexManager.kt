package com.example.entropyrng.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

/**
 * Менеджер для работы с Kp индексом
 * Отвечает за загрузку, кеширование и анализ солнечной активности
 */
class KpIndexManager(context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val kpDao = db.kpHistoryDao()

    /**
     * Получить текущий Kp индекс
     * 1. Пытается загрузить с NOAA API
     * 2. Сохраняет в базу
     * 3. Возвращает значение
     */
    suspend fun fetchAndSaveCurrentKp(): KpResult = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json")
                .header("User-Agent", "EntropyRNG App")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val jsonText = response.body?.string() ?: return@withContext KpResult.Error("Empty response")
                val jsonArray = JSONArray(jsonText)

                if (jsonArray.length() > 1) {
                    val lastEntry = jsonArray.getJSONArray(jsonArray.length() - 1)
                    val kpValue = lastEntry.getString(1).toFloatOrNull()
                        ?: return@withContext KpResult.Error("Invalid Kp value")

                    val timeTag = lastEntry.getString(0) // "2026-01-19 10:00:00"

                    // Парсим дату и время
                    val (date, time) = parseNoaaTimeTag(timeTag)

                    // Сохраняем в базу
                    val kpData = KpHistoryData(
                        date = date,
                        time = time,
                        kpValue = kpValue,
                        source = "auto"
                    )
                    kpDao.insert(kpData)

                    return@withContext KpResult.Success(kpValue, kpData)
                }
            }

            KpResult.Error("Failed to fetch from NOAA")

        } catch (e: Exception) {
            KpResult.Error("Network error: ${e.message}")
        }
    }

    /**
     * Получить Kp для конкретной даты/времени
     * Ищет в базе данных с погрешностью ±3 часа
     */
    suspend fun getKpForDateTime(date: String, time: String): Float? = withContext(Dispatchers.IO) {
        // Точное совпадение
        var kpData = kpDao.getByDateTime(date, time)
        if (kpData != null) return@withContext kpData.kpValue

        // Поиск ближайшего (±3 часа)
        val (timeStart, timeEnd) = calculateTimeRange(time, 3)
        kpData = kpDao.getNearestByDateTime(date, time, timeStart, timeEnd)

        kpData?.kpValue
    }

    /**
     * Сохранить Kp вручную (например, при генерации)
     */
    suspend fun saveKpForGeneration(kpValue: Float) = withContext(Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(calendar.time)

        val kpData = KpHistoryData(
            date = date,
            time = time,
            kpValue = kpValue,
            source = "generation"
        )
        kpDao.insert(kpData)
    }

    /**
     * Получить статистику по Kp
     */
    suspend fun getKpStatistics(): KpStatistics = withContext(Dispatchers.IO) {
        val count = kpDao.getCount()
        val average = kpDao.getAverageKp() ?: 0f
        val latest = kpDao.getLatest()

        KpStatistics(
            totalRecords = count,
            averageKp = average,
            latestKp = latest?.kpValue,
            latestDate = latest?.date,
            latestTime = latest?.time
        )
    }

    /**
     * Парсинг временной метки NOAA: "2026-01-19 10:00:00"
     */
    private fun parseNoaaTimeTag(timeTag: String): Pair<String, String> {
        return try {
            val parts = timeTag.trim().split(" ")
            val date = parts[0] // "2026-01-19"
            val time = parts[1] // "10:00:00"
            Pair(date, time)
        } catch (e: Exception) {
            val cal = Calendar.getInstance()
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(cal.time)
            Pair(date, time)
        }
    }

    /**
     * Рассчитать диапазон времени (±hours)
     * Возвращает (startTime, endTime)
     */
    private fun calculateTimeRange(time: String, hours: Int): Pair<String, String> {
        return try {
            val parts = time.split(":")
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()

            var startHour = hour - hours
            var endHour = hour + hours

            // Корректировка диапазона
            if (startHour < 0) startHour = 0
            if (endHour > 23) endHour = 23

            val startTime = String.format("%02d:%02d:00", startHour, minute)
            val endTime = String.format("%02d:%02d:59", endHour, minute)

            Pair(startTime, endTime)
        } catch (e: Exception) {
            Pair("00:00:00", "23:59:59")
        }
    }
}

/**
 * Результат запроса Kp
 */
sealed class KpResult {
    data class Success(val value: Float, val data: KpHistoryData) : KpResult()
    data class Error(val message: String) : KpResult()
}

/**
 * Статистика по Kp
 */
data class KpStatistics(
    val totalRecords: Int,
    val averageKp: Float,
    val latestKp: Float?,
    val latestDate: String?,
    val latestTime: String?
)
