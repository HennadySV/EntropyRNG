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

    /**
     * Генерация двух полей с вариативностью распределения
     * Имитирует паттерн лотереи: поля могут отличаться по spread
     *
     * Статистика лотереи "Премьер":
     * - Средняя разница spread между полями: 4.16
     * - 6.6% тиражей имеют разницу ≥10 (одно кластер, другое размашистое)
     *
     * @return Pair(field1, field2) где каждое поле - отсортированный список из 4 чисел
     */
    fun generateTwoFieldsWithVariability(
        entropyBytes: ByteArray,
        kp: Float,
        weights: Map<Int, Float>?,
        mode: GenerationMode
    ): Pair<List<Int>, List<Int>> {
        // Генерируем первое поле
        val field1 = when (mode) {
            GenerationMode.PURE_ENTROPY ->
                generatePureEntropy(4, 1, 20, entropyBytes, kp)
            GenerationMode.WEIGHTED_ENTROPY ->
                generateWeightedEntropy(4, 1, 20, entropyBytes, kp, weights!!)
        }

        // Генерируем второе поле
        var field2 = when (mode) {
            GenerationMode.PURE_ENTROPY ->
                generatePureEntropy(4, 1, 20, entropyBytes, kp)
            GenerationMode.WEIGHTED_ENTROPY ->
                generateWeightedEntropy(4, 1, 20, entropyBytes, kp, weights!!)
        }

        // Вычисляем spread обоих полей
        val spread1 = field1.max() - field1.min()
        val spread2 = field2.max() - field2.min()
        val spreadDiff = kotlin.math.abs(spread1 - spread2)

        // Если разница слишком маленькая (< 3), добавляем вариативность
        // Согласно статистике лотереи, средняя разница = 4.16
        if (spreadDiff < 3) {
            // Создаём seed из текущего времени для случайности
            val seed = ByteBuffer.wrap(entropyBytes).getLong() xor System.nanoTime()
            val random = Random(seed)

            // 30% шанс перегенерировать второе поле для разнообразия
            if (random.nextFloat() < 0.3f) {
                // Модифицируем энтропию добавлением случайного шума
                val noisyEntropy = entropyBytes.copyOf().apply {
                    this[0] = (this[0].toInt() xor random.nextInt()).toByte()
                }

                field2 = when (mode) {
                    GenerationMode.PURE_ENTROPY ->
                        generatePureEntropy(4, 1, 20, noisyEntropy, kp)
                    GenerationMode.WEIGHTED_ENTROPY ->
                        generateWeightedEntropy(4, 1, 20, noisyEntropy, kp, weights!!)
                }
            }
        }

        // Дополнительно: в 6.6% случаев создаём экстремальную разницу (≥10)
        // Это имитирует реальную лотерею
        val seed = ByteBuffer.wrap(entropyBytes).getLong()
        val random = Random(seed)

        if (random.nextFloat() < 0.066f) {  // 6.6% случаев
            // Решаем какое поле делать кластерным, а какое размашистым
            val makeField2Extreme = random.nextBoolean()

            if (makeField2Extreme) {
                // Генерируем экстремально размашистое или кластерное поле 2
                val targetSpread = if (random.nextBoolean()) {
                    // Размашистое (spread 15-19)
                    15 + random.nextInt(5)
                } else {
                    // Кластерное (spread 3-6)
                    3 + random.nextInt(4)
                }

                // Генерируем поле с целевым spread
                field2 = generateFieldWithTargetSpread(
                    targetSpread = targetSpread,
                    entropyBytes = entropyBytes,
                    kp = kp,
                    weights = weights,
                    mode = mode,
                    random = random
                )
            }
        }

        return Pair(field1, field2)
    }

    /**
     * Генерирует поле с заданным целевым spread
     * Используется для создания экстремальных комбинаций
     */
    private fun generateFieldWithTargetSpread(
        targetSpread: Int,
        entropyBytes: ByteArray,
        kp: Float,
        weights: Map<Int, Float>?,
        mode: GenerationMode,
        random: Random
    ): List<Int> {
        var attempts = 0
        val maxAttempts = 50

        while (attempts < maxAttempts) {
            // Модифицируем энтропию для каждой попытки
            val modifiedEntropy = entropyBytes.copyOf().apply {
                this[attempts % this.size] = (this[attempts % this.size].toInt() xor random.nextInt()).toByte()
            }

            val field = when (mode) {
                GenerationMode.PURE_ENTROPY ->
                    generatePureEntropy(4, 1, 20, modifiedEntropy, kp)
                GenerationMode.WEIGHTED_ENTROPY ->
                    generateWeightedEntropy(4, 1, 20, modifiedEntropy, kp, weights!!)
            }

            val spread = field.max() - field.min()

            // Проверяем близость к целевому spread (±2)
            if (kotlin.math.abs(spread - targetSpread) <= 2) {
                return field
            }

            attempts++
        }

        // Если не удалось достичь целевого spread, возвращаем лучшую попытку
        return when (mode) {
            GenerationMode.PURE_ENTROPY ->
                generatePureEntropy(4, 1, 20, entropyBytes, kp)
            GenerationMode.WEIGHTED_ENTROPY ->
                generateWeightedEntropy(4, 1, 20, entropyBytes, kp, weights!!)
        }
    }
}
