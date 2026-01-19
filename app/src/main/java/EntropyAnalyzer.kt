package com.example.entropyrng.analysis

import com.example.entropyrng.data.AppDatabase
import com.example.entropyrng.data.Converters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Анализатор энтропии и исторических данных лотереи
 * Рассчитывает веса для калиброванной генерации
 */
class EntropyAnalyzer(private val db: AppDatabase) {

    private val converters = Converters()

    /**
     * Результат анализа
     */
    data class AnalysisResult(
        val totalDraws: Int,              // Общее количество тиражей
        val uniqueNumbers: Set<Int>,      // Уникальные числа
        val frequencies: Map<Int, Int>,   // Частоты чисел
        val weights: Map<Int, Float>,     // Нормализованные веса (0-1)
        val topNumbers: List<Pair<Int, Float>>, // Топ чисел по весу
        val message: String
    )

    /**
     * Анализировать исторические данные и рассчитать веса
     * @param minRange минимальное число диапазона
     * @param maxRange максимальное число диапазона
     * @param useFrequencyOnly если true, используется только частота (без корреляций)
     */
    suspend fun analyzeAndCalculateWeights(
        minRange: Int,
        maxRange: Int,
        useFrequencyOnly: Boolean = true
    ): AnalysisResult = withContext(Dispatchers.IO) {

        // Получаем все тиражи лотереи
        val allDraws = db.numberDataDao().getLotteryDrawsOnly()

        if (allDraws.isEmpty()) {
            return@withContext AnalysisResult(
                totalDraws = 0,
                uniqueNumbers = emptySet(),
                frequencies = emptyMap(),
                weights = emptyMap(),
                topNumbers = emptyList(),
                message = "Нет данных для анализа. Импортируйте CSV."
            )
        }

        // Подсчитываем частоты
        val frequencies = mutableMapOf<Int, Int>()
        val allNumbers = mutableListOf<Int>()

        allDraws.forEach { draw ->
            draw.numbers.forEach { number ->
                if (number in minRange..maxRange) {
                    frequencies[number] = frequencies.getOrDefault(number, 0) + 1
                    allNumbers.add(number)
                }
            }
        }

        // Рассчитываем веса
        val weights = if (useFrequencyOnly) {
            calculateFrequencyWeights(frequencies, minRange, maxRange)
        } else {
            // Будущая реализация: корреляции с солнечной активностью и магнитным полем
            calculateFrequencyWeights(frequencies, minRange, maxRange)
        }

        // Топ-10 чисел
        val topNumbers = weights.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key to it.value }

        AnalysisResult(
            totalDraws = allDraws.size,
            uniqueNumbers = frequencies.keys,
            frequencies = frequencies,
            weights = weights,
            topNumbers = topNumbers,
            message = "Проанализировано ${allDraws.size} тиражей. Найдено ${frequencies.keys.size} уникальных чисел."
        )
    }

    /**
     * Расчёт весов на основе частот
     * Более частые числа получают больший вес
     */
    private fun calculateFrequencyWeights(
        frequencies: Map<Int, Int>,
        minRange: Int,
        maxRange: Int
    ): Map<Int, Float> {

        if (frequencies.isEmpty()) {
            // Если нет данных, возвращаем равномерное распределение
            return (minRange..maxRange).associateWith { 1.0f / (maxRange - minRange + 1) }
        }

        // Нормализация: делим каждую частоту на общее количество появлений
        val totalOccurrences = frequencies.values.sum()
        val normalizedWeights = mutableMapOf<Int, Float>()

        // Для чисел в диапазоне, которых нет в истории, даём минимальный вес
        val minWeight = 0.001f

        (minRange..maxRange).forEach { number ->
            val frequency = frequencies[number] ?: 0
            val weight = if (frequency > 0) {
                frequency.toFloat() / totalOccurrences.toFloat()
            } else {
                minWeight
            }
            normalizedWeights[number] = weight
        }

        // Повторная нормализация чтобы сумма весов = 1.0
        val sum = normalizedWeights.values.sum()
        return normalizedWeights.mapValues { it.value / sum }
    }

    /**
     * Расчёт корреляции с солнечной активностью (будущая реализация)
     * Анализирует связь между Kp индексом и выпавшими числами
     */
    suspend fun analyzeSolarCorrelation(
        minRange: Int,
        maxRange: Int
    ): Map<Int, Float> = withContext(Dispatchers.IO) {

        val draws = db.numberDataDao().getLotteryDrawsOnly()
        val kpDao = db.kpHistoryDao()

        if (draws.isEmpty()) {
            return@withContext emptyMap()
        }

        // Для каждого тиража пытаемся найти Kp
        val drawsWithKp = mutableListOf<Pair<NumberData, Float>>()

        draws.forEach { draw ->
            val kp = findKpForDraw(draw, kpDao)
            if (kp != null) {
                drawsWithKp.add(Pair(draw, kp))
            }
        }

        if (drawsWithKp.isEmpty()) {
            return@withContext emptyMap()
        }

        // Для каждого числа собираем Kp когда оно выпадало
        val numberKpMap = mutableMapOf<Int, MutableList<Float>>()

        drawsWithKp.forEach { (draw, kp) ->
            draw.numbers.forEach { number ->
                if (number in minRange..maxRange) {
                    numberKpMap.getOrPut(number) { mutableListOf() }.add(kp)
                }
            }
        }

        // Средний Kp для каждого числа
        numberKpMap.mapValues { (_, kpList) ->
            kpList.average().toFloat()
        }
    }

    /**
     * Найти Kp для тиража (ищет в пределах ±3 часов)
     */
    private suspend fun findKpForDraw(draw: NumberData, kpDao: KpHistoryDao): Float? {
        // Попытка найти точное совпадение
        val exact = kpDao.getByDateTime(draw.date, draw.time)
        if (exact != null) return exact.kpValue

        // Поиск в диапазоне ±3 часа
        val (startTime, endTime) = calculateTimeRange(draw.time, 3)
        val nearest = kpDao.getNearestByDateTime(draw.date, draw.time, startTime, endTime)

        return nearest?.kpValue
    }

    /**
     * Вспомогательная функция: расчёт диапазона времени
     */
    private fun calculateTimeRange(time: String, hours: Int): Pair<String, String> {
        return try {
            val parts = time.split(":")
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()

            var startHour = hour - hours
            var endHour = hour + hours

            if (startHour < 0) startHour = 0
            if (endHour > 23) endHour = 23

            val startTime = String.format("%02d:%02d:00", startHour, minute)
            val endTime = String.format("%02d:%02d:59", endHour, minute)

            Pair(startTime, endTime)
        } catch (e: Exception) {
            Pair("00:00:00", "23:59:59")
        }
    }

    /**
     * НОВЫЙ МЕТОД: Расчёт весов с учётом текущего Kp
     * Комбинирует частоты + корреляцию с солнечной активностью
     *
     * @param currentKp текущий Kp на момент генерации
     * @return скорректированные веса с учётом Kp
     */
    suspend fun calculateKpAdjustedWeights(
        minRange: Int,
        maxRange: Int,
        currentKp: Float
    ): Map<Int, Float> = withContext(Dispatchers.IO) {

        // 1. Базовые веса (частоты)
        val analysisResult = analyzeAndCalculateWeights(minRange, maxRange, true)
        val frequencyWeights = analysisResult.weights

        // 2. Пытаемся получить корреляции с Kp
        val avgKpPerNumber = analyzeSolarCorrelation(minRange, maxRange)

        // Если нет данных по Kp → возвращаем только частоты
        if (avgKpPerNumber.isEmpty()) {
            return@withContext frequencyWeights
        }

        // 3. Общий средний Kp из истории
        val kpDao = db.kpHistoryDao()
        val globalAvgKp = kpDao.getAverageKp() ?: currentKp

        // 4. Корректировка весов на основе текущего Kp
        val adjustedWeights = mutableMapOf<Int, Float>()

        (minRange..maxRange).forEach { number ->
            val baseWeight = frequencyWeights[number] ?: (1.0f / (maxRange - minRange + 1))
            val avgKpForNumber = avgKpPerNumber[number] ?: globalAvgKp

            // КЛЮЧЕВАЯ ФОРМУЛА:
            // Если текущий Kp близок к среднему Kp этого числа → увеличиваем вес
            // Используем обратную функцию расстояния
            val kpDifference = kotlin.math.abs(currentKp - avgKpForNumber)
            val maxDifference = 9.0f // Максимальный Kp = 9

            // Коэффициент близости: 1.0 (идеально близко) ... 0.0 (максимально далеко)
            val kpSimilarity = 1.0f - (kpDifference / maxDifference).coerceIn(0f, 1f)

            // Комбинируем: 60% частота + 40% корреляция с Kp
            // При kpSimilarity = 1.0 → бонус +40%
            // При kpSimilarity = 0.0 → штраф -40%
            val kpBonus = 0.6f + (0.4f * kpSimilarity)
            val adjustedWeight = baseWeight * kpBonus

            adjustedWeights[number] = adjustedWeight
        }

        // 5. Нормализация (сумма = 1.0)
        val sum = adjustedWeights.values.sum()
        if (sum > 0) {
            adjustedWeights.mapValues { it.value / sum }
        } else {
            frequencyWeights
        }
    }

    /**
     * Расчёт корреляции с магнитным полем (будущая реализация)
     */
    suspend fun analyzeMagneticCorrelation(
        minRange: Int,
        maxRange: Int
    ): Map<Int, Float> = withContext(Dispatchers.IO) {

        val draws = db.numberDataDao().getLotteryDrawsOnly()
            .filter {
                it.magneticFieldX != null &&
                        it.magneticFieldY != null &&
                        it.magneticFieldZ != null
            }

        if (draws.isEmpty()) {
            return@withContext emptyMap()
        }

        // Аналогичный анализ для магнитного поля
        // TODO: Реализовать корреляционный анализ

        emptyMap()
    }

    /**
     * Комплексный анализ с учётом всех факторов
     * Комбинирует веса из частот, солнечной активности и магнитного поля
     */
    suspend fun calculateCombinedWeights(
        minRange: Int,
        maxRange: Int,
        frequencyWeight: Float = 0.7f,      // Вес частоты (70%)
        solarWeight: Float = 0.2f,          // Вес солнечной активности (20%)
        magneticWeight: Float = 0.1f        // Вес магнитного поля (10%)
    ): Map<Int, Float> = withContext(Dispatchers.IO) {

        // Получаем все веса
        val freqWeights = analyzeAndCalculateWeights(minRange, maxRange).weights
        val solarWeights = analyzeSolarCorrelation(minRange, maxRange)
        val magneticWeights = analyzeMagneticCorrelation(minRange, maxRange)

        // Комбинируем веса
        val combinedWeights = mutableMapOf<Int, Float>()

        (minRange..maxRange).forEach { number ->
            val freq = freqWeights[number] ?: (1.0f / (maxRange - minRange + 1))
            val solar = solarWeights[number] ?: freq // Fallback на частоту
            val magnetic = magneticWeights[number] ?: freq

            val combined = (freq * frequencyWeight) +
                    (solar * solarWeight) +
                    (magnetic * magneticWeight)

            combinedWeights[number] = combined
        }

        // Нормализация
        val sum = combinedWeights.values.sum()
        combinedWeights.mapValues { it.value / sum }
    }

    /**
     * Получить статистику по текущей базе данных
     */
    suspend fun getDatabaseStats(): DatabaseStats = withContext(Dispatchers.IO) {
        val total = db.numberDataDao().getCount()
        val lottery = db.numberDataDao().getCountBySource("lottery") +
                db.numberDataDao().getCountBySource("imported")
        val generated = db.numberDataDao().getCountBySource("generated")

        DatabaseStats(
            totalRecords = total,
            lotteryRecords = lottery,
            generatedRecords = generated
        )
    }

    data class DatabaseStats(
        val totalRecords: Int,
        val lotteryRecords: Int,
        val generatedRecords: Int
    )
}
