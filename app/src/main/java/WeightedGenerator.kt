package com.example.entropyrng.generation

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Генератор чисел с учётом весов из анализа
 * Комбинирует физическую энтропию с весовыми коэффициентами
 */
class WeightedGenerator {

    /**
     * Режим генерации
     */
    enum class GenerationMode {
        PURE_ENTROPY,      // Чистая энтропия (текущий режим)
        WEIGHTED_ENTROPY   // Калиброванная энтропия (с весами)
    }

    /**
     * Генерация чисел в режиме чистой энтропии
     * Оригинальный метод из MainActivity
     */
    fun generatePureEntropy(
        count: Int,
        min: Int,
        max: Int,
        entropyBytes: ByteArray,
        kp: Float
    ): List<Int> {
        // XOR debias
        val debiased = ByteArray(entropyBytes.size / 2)
        for (i in debiased.indices) {
            debiased[i] = (entropyBytes[2 * i].toInt() xor entropyBytes[2 * i + 1].toInt()).toByte()
        }

        // SHA-256 с добавлением Kp и timestamp
        val sha256 = MessageDigest.getInstance("SHA-256")
        sha256.update(System.currentTimeMillis().toString().toByteArray())
        sha256.update(kp.toString().toByteArray())
        val hash = sha256.digest(debiased)

        // Генерация уникальных чисел
        val range = max - min + 1
        val uniqueNumbers = mutableSetOf<Int>()
        var hashIndex = 0

        while (uniqueNumbers.size < count && hashIndex < hash.size * 100) {
            val number = min + ((hash[hashIndex % hash.size].toInt() and 0xFF) % range)
            uniqueNumbers.add(number)
            hashIndex++
        }

        return uniqueNumbers.toList().sorted()
    }

    /**
     * Генерация чисел с учётом весов
     * Использует энтропию + веса из анализа
     */
    fun generateWeightedEntropy(
        count: Int,
        min: Int,
        max: Int,
        entropyBytes: ByteArray,
        kp: Float,
        weights: Map<Int, Float>
    ): List<Int> {
        // Создаём hash с энтропией + Kp
        val sha256 = MessageDigest.getInstance("SHA-256")
        sha256.update(System.currentTimeMillis().toString().toByteArray())
        sha256.update(kp.toString().toByteArray())
        val hash = sha256.digest(entropyBytes)

        // Конвертируем hash в seed для Random
        val seed = ByteBuffer.wrap(hash).getLong()
        val random = Random(seed)

        // Генерация с учётом весов
        val uniqueNumbers = mutableSetOf<Int>()
        var attempts = 0
        val maxAttempts = count * 100

        while (uniqueNumbers.size < count && attempts < maxAttempts) {
            val number = weightedRandomSelection(random, min, max, weights)
            uniqueNumbers.add(number)
            attempts++
        }

        return uniqueNumbers.toList().sorted()
    }

    /**
     * Взвешенный выбор случайного числа
     * Использует метод "рулетки" с весами
     */
    private fun weightedRandomSelection(
        random: Random,
        min: Int,
        max: Int,
        weights: Map<Int, Float>
    ): Int {
        // Подготовка кумулятивных весов
        val numbers = (min..max).toList()
        val cumulativeWeights = mutableListOf<Float>()
        var cumulative = 0f

        numbers.forEach { number ->
            val weight = weights[number] ?: (1.0f / (max - min + 1))
            cumulative += weight
            cumulativeWeights.add(cumulative)
        }

        // Генерируем случайное число в диапазоне [0, cumulative)
        val randomValue = random.nextFloat() * cumulative

        // Находим число по кумулятивному весу
        for (i in numbers.indices) {
            if (randomValue <= cumulativeWeights[i]) {
                return numbers[i]
            }
        }

        // Fallback (не должно происходить)
        return numbers.last()
    }

    /**
     * Гибридная генерация: комбинация чистой энтропии и весов
     * @param entropyRatio коэффициент влияния энтропии (0.0 = только веса, 1.0 = только энтропия)
     */
    fun generateHybrid(
        count: Int,
        min: Int,
        max: Int,
        entropyBytes: ByteArray,
        kp: Float,
        weights: Map<Int, Float>,
        entropyRatio: Float = 0.5f
    ): List<Int> {
        val pureCount = (count * entropyRatio).toInt()
        val weightedCount = count - pureCount

        val pureNumbers = if (pureCount > 0) {
            generatePureEntropy(pureCount, min, max, entropyBytes, kp).toSet()
        } else {
            emptySet()
        }

        val weightedNumbers = if (weightedCount > 0) {
            generateWeightedEntropy(weightedCount, min, max, entropyBytes, kp, weights).toSet()
        } else {
            emptySet()
        }

        // Объединяем и дополняем до нужного количества
        val combined = (pureNumbers + weightedNumbers).toMutableSet()

        // Если получилось меньше чисел из-за пересечений
        val seed = ByteBuffer.wrap(entropyBytes).getLong()
        val random = Random(seed)

        while (combined.size < count) {
            val number = weightedRandomSelection(random, min, max, weights)
            combined.add(number)
        }

        return combined.toList().sorted()
    }

    /**
     * Вспомогательная функция: создание равномерных весов
     * Используется когда анализ не проведён
     */
    fun createUniformWeights(min: Int, max: Int): Map<Int, Float> {
        val uniformWeight = 1.0f / (max - min + 1)
        return (min..max).associateWith { uniformWeight }
    }
}
