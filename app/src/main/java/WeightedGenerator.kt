package com.example.entropyrng.generation

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.*
import kotlin.random.Random

/**
 * Генератор чисел с учётом весов из анализа + пространственных паттернов
 * Комбинирует физическую энтропию с весовыми коэффициентами и визуальной гармонией
 */
class WeightedGenerator {

    /**
     * Режим генерации
     */
    enum class GenerationMode {
        PURE_ENTROPY,      // Чистая энтропия (оригинальный режим)
        WEIGHTED_ENTROPY,  // Калиброванная энтропия (с весами)
        KP_SPATIAL         // Kp + Пространственная гармония (НОВЫЙ!)
    }

    /**
     * Аттракторы чисел для разных уровней Kp (из анализа данных)
     */
    private val kpAttractors = mapOf(
        "STORM" to mapOf(  // Kp > 4.0 (магнитная буря)
            5 to 8.7f, 17 to 8.2f, 7 to 7.6f, 20 to 6.5f, 16 to 6.0f
        ),
        "HIGH" to mapOf(   // Kp 3.0-4.0
            5 to 6.8f, 16 to 6.7f, 9 to 5.8f, 20 to 5.5f, 1 to 5.5f
        ),
        "MEDIUM" to mapOf( // Kp 1.5-3.0
            13 to 6.0f, 8 to 5.9f, 9 to 5.7f, 10 to 5.3f, 11 to 5.3f
        ),
        "LOW" to mapOf(    // Kp ≤ 1.5
            1 to 6.9f, 3 to 6.5f, 8 to 6.5f, 18 to 6.0f, 12 to 6.0f
        )
    )

    /**
     * Позиция числа на сетке 4×5
     */
    private data class GridPosition(val row: Int, val col: Int)

    /**
     * Конвертация числа в позицию сетки
     */
    private fun numberToGridPos(num: Int): GridPosition {
        return GridPosition((num - 1) / 5, (num - 1) % 5)
    }

    /**
     * Получить аттрактор для уровня Kp
     */
    private fun getKpAttractor(kp: Float): Map<Int, Float> {
        return when {
            kp > 4.0f -> kpAttractors["STORM"]!!
            kp > 3.0f -> kpAttractors["HIGH"]!!
            kp > 1.5f -> kpAttractors["MEDIUM"]!!
            else -> kpAttractors["LOW"]!!
        }
    }

    /**
     * Вычислить штраф за пространственную кластеризацию
     */
    private fun calculateSpatialPenalty(numbers: List<Int>): Float {
        var penalty = 0f

        // Штраф за последовательные числа (как интуиция пользователя)
        val sorted = numbers.sorted()
        for (i in 0 until sorted.size - 1) {
            if (sorted[i + 1] - sorted[i] == 1) {
                penalty += 30f // Жёсткий штраф за соседние числа
            }
        }

        // Штраф за близость чисел
        for (i in numbers.indices) {
            for (j in i + 1 until numbers.size) {
                val diff = abs(numbers[i] - numbers[j])
                when {
                    diff <= 2 -> penalty += 15f  // Очень близко
                    diff <= 4 -> penalty += 5f   // Близко
                }
            }
        }

        // Штраф за кластеризацию на визуальной сетке
        val positions = numbers.map { numberToGridPos(it) }
        for (i in positions.indices) {
            for (j in i + 1 until positions.size) {
                val manhattan = abs(positions[i].row - positions[j].row) +
                        abs(positions[i].col - positions[j].col)
                if (manhattan <= 1) {
                    penalty += 20f // Соседние клетки на сетке
                }
            }
        }

        return penalty
    }

    /**
     * Вычислить бонус за хорошие визуальные паттерны
     */
    private fun calculateVisualBonus(numbers: List<Int>): Float {
        var bonus = 0f
        val positions = numbers.map { numberToGridPos(it) }

        // Бонус за диагональные паттерны
        val sortedByRow = positions.sortedBy { it.row }
        var diagonalCount = 0
        for (i in 0 until sortedByRow.size - 1) {
            val curr = sortedByRow[i]
            val next = sortedByRow[i + 1]
            if (next.row - curr.row == 1 && abs(next.col - curr.col) == 1) {
                diagonalCount++
            }
        }
        bonus += diagonalCount * 10f

        // Бонус за равномерное распределение по строкам
        val rows = positions.map { it.row }.distinct()
        val cols = positions.map { it.col }.distinct()

        val rowSpread = if (rows.isNotEmpty()) rows.max() - rows.min() else 0
        val colSpread = if (cols.isNotEmpty()) cols.max() - cols.min() else 0

        if (rowSpread >= 2) bonus += 15f  // Хорошая вертикальная растяжка
        if (colSpread >= 2) bonus += 10f  // Хорошая горизонтальная растяжка

        return bonus
    }

    /**
     * Генерация в режиме Kp-Spatial Harmony
     */
    fun generateKpSpatialMode(
        count: Int,
        min: Int,
        max: Int,
        entropyBytes: ByteArray,
        kp: Float
    ): List<Int> {
        val attractor = getKpAttractor(kp)
        val results = mutableListOf<Int>()

        // Создаём более сильный seed mixing
        val entropyHash = MessageDigest.getInstance("SHA-256").digest(
            entropyBytes + System.nanoTime().toString().toByteArray() + kp.toString().toByteArray()
        )
        val seed = ByteBuffer.wrap(entropyHash).long
        val random = Random(seed)

        // Базовые веса с дополнительным шумом
        val baseWeights = (min..max).associateWith { num ->
            val baseWeight = attractor[num] ?: 5.0f
            val noise = (random.nextFloat() - 0.5f) * 1.0f  // ±0.5 шума
            baseWeight + noise
        }.toMutableMap()

        repeat(count) {
            var bestCandidate = min
            var bestScore = Float.NEGATIVE_INFINITY

            // Увеличиваем количество кандидатов для лучшего качества
            repeat(30) {  // Было 20, стало 30
                val candidates = baseWeights.keys.filter { it !in results }
                if (candidates.isEmpty()) return@repeat

                // Добавляем случайность в выбор кандидата
                val candidate = if (random.nextFloat() < 0.7f) {
                    // 70% времени - взвешенный выбор
                    val totalWeight = candidates.sumOf { baseWeights[it]!!.toDouble() }
                    var randomValue = random.nextDouble() * totalWeight

                    var selected = candidates.first()
                    for (num in candidates) {
                        randomValue -= baseWeights[num]!!
                        if (randomValue <= 0) {
                            selected = num
                            break
                        }
                    }
                    selected
                } else {
                    // 30% времени - чисто случайный выбор (для разнообразия)
                    candidates.random(random)
                }

                // Оценка кандидата с дополнительным шумом
                val testField = results + candidate
                val spatialPenalty = calculateSpatialPenalty(testField)
                val visualBonus = calculateVisualBonus(testField)
                val kpBonus = baseWeights[candidate]!! * 2f
                val randomBonus = (random.nextFloat() - 0.5f) * 10f  // Случайный фактор

                val score = kpBonus + visualBonus - spatialPenalty + randomBonus

                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = candidate
                }
            }

            results.add(bestCandidate)
        }

        return results.sorted()
    }

    /**
     * Генерация чисел в режиме чистой энтропии (оригинальный метод)
     */
    fun generatePureEntropy(
        count: Int,
        min: Int,
        max: Int,
        entropyBytes: ByteArray,
        kp: Float
    ): List<Int> {
        val results = mutableSetOf<Int>()
        val entropyHash = MessageDigest.getInstance("SHA-256").digest(entropyBytes)
        val random = Random(ByteBuffer.wrap(entropyHash).long)

        while (results.size < count) {
            val range = max - min + 1
            val number = min + (random.nextInt(range))
            results.add(number)
        }

        return results.sorted()
    }

    /**
     * Генерация чисел с весовыми коэффициентами (существующий метод)
     */
    fun generateWeightedEntropy(
        count: Int,
        min: Int,
        max: Int,
        entropyBytes: ByteArray,
        kp: Float,
        weights: Map<Int, Float>
    ): List<Int> {
        val results = mutableSetOf<Int>()
        val entropyHash = MessageDigest.getInstance("SHA-256").digest(entropyBytes)
        val random = Random(ByteBuffer.wrap(entropyHash).long)

        // Нормализуем веса
        val totalWeight = weights.values.sum()
        val normalizedWeights = weights.mapValues { it.value / totalWeight }

        while (results.size < count) {
            var randomValue = random.nextFloat()
            for ((number, weight) in normalizedWeights) {
                if (number in min..max) {
                    randomValue -= weight
                    if (randomValue <= 0 && number !in results) {
                        results.add(number)
                        break
                    }
                }
            }

            // Fallback если веса не сработали
            if (results.size < count && randomValue > 0) {
                val number = min + random.nextInt(max - min + 1)
                results.add(number)
            }
        }

        return results.sorted()
    }
    fun generateTwoFieldsAntiMirror(
        entropyBytes: ByteArray,
        kp: Float,
        weights: Map<Int, Float>? = null,
        mode: GenerationMode = GenerationMode.PURE_ENTROPY
    ): Pair<List<Int>, List<Int>> {

        var bestPair: Pair<List<Int>, List<Int>>? = null
        var bestScore = Float.NEGATIVE_INFINITY

        repeat(10) { attempt ->
            // Создаём сильно различающиеся entropy seeds
            val entropy1 = entropyBytes.copyOf()
            val entropy2 = entropyBytes.copyOf().apply {
                val salt = (attempt + 1) * 12345 + kp.toInt() * 67890
                for (i in indices) {
                    this[i] = (this[i].toInt() xor (salt shr (i % 32))).toByte()
                }
            }

            // Генерируем поля
            val field1 = when (mode) {
                GenerationMode.KP_SPATIAL -> generateKpSpatialMode(4, 1, 20, entropy1, kp)
                GenerationMode.WEIGHTED_ENTROPY -> generateWeightedEntropy(4, 1, 20, entropy1, kp, weights!!)
                else -> generatePureEntropy(4, 1, 20, entropy1, kp)
            }

            val field2 = when (mode) {
                GenerationMode.KP_SPATIAL -> generateKpSpatialMode(4, 1, 20, entropy2, kp)
                GenerationMode.WEIGHTED_ENTROPY -> generateWeightedEntropy(4, 1, 20, entropy2, kp, weights!!)
                else -> generatePureEntropy(4, 1, 20, entropy2, kp)
            }

            // Оценка качества пары
            val intersection = field1.intersect(field2.toSet()).size
            val mirrorPenalty = intersection * 50f  // Жёсткий штраф за совпадения

            val spread1 = field1.max() - field1.min()
            val spread2 = field2.max() - field2.min()
            val spreadBalance = 20f - kotlin.math.abs(spread1 - spread2) * 2f

            val diversity = (field1 + field2).toSet().size * 5f  // Бонус за разнообразие

            val score = diversity + spreadBalance - mirrorPenalty

            if (score > bestScore) {
                bestScore = score
                bestPair = Pair(field1.sorted(), field2.sorted())
            }
        }

        return bestPair ?: Pair(
            generatePureEntropy(4, 1, 20, entropyBytes, kp),
            generatePureEntropy(4, 1, 20, entropyBytes.copyOf().apply {
                this[0] = (this[0].toInt() xor 42).toByte()
            }, kp)
        )
    }

    /**
     * Генерация двух полей с улучшенной вариативностью
     */
    fun generateTwoFieldsWithVariability(
        entropyBytes: ByteArray,
        kp: Float,
        weights: Map<Int, Float>? = null,
        mode: GenerationMode = GenerationMode.PURE_ENTROPY
    ): Pair<List<Int>, List<Int>> {
        // СПЕЦИАЛЬНАЯ ЛОГИКА ДЛЯ МЕРТВОЙ ЗОНЫ
        if (kp in 3.0f..4.0f && mode == GenerationMode.KP_SPATIAL) {
            return generateDeadZoneFields(entropyBytes, kp)
        }
        val random = Random(System.currentTimeMillis())
        val targetSpread = random.nextInt(10) + 8 // 8-17
        var attempts = 0
        val maxAttempts = 50

        while (attempts < maxAttempts) {
            // Модифицируем энтропию для каждой попытки
            val modifiedEntropy = entropyBytes.copyOf().apply {
                this[attempts % this.size] = (this[attempts % this.size].toInt() xor random.nextInt()).toByte()
            }

            val field1 = when (mode) {
                GenerationMode.PURE_ENTROPY ->
                    generatePureEntropy(4, 1, 20, modifiedEntropy, kp)
                GenerationMode.WEIGHTED_ENTROPY ->
                    generateWeightedEntropy(4, 1, 20, modifiedEntropy, kp, weights!!)
                GenerationMode.KP_SPATIAL ->
                    generateKpSpatialMode(4, 1, 20, modifiedEntropy, kp)
            }

            // Модифицируем энтропию для второго поля
            val entropy2 = modifiedEntropy.copyOf().apply {
                this[(attempts + 1) % this.size] = (this[(attempts + 1) % this.size].toInt() xor kp.toInt()).toByte()
            }

            val field2 = when (mode) {
                GenerationMode.PURE_ENTROPY ->
                    generatePureEntropy(4, 1, 20, entropy2, kp)
                GenerationMode.WEIGHTED_ENTROPY ->
                    generateWeightedEntropy(4, 1, 20, entropy2, kp, weights!!)
                GenerationMode.KP_SPATIAL ->
                    generateKpSpatialMode(4, 1, 20, entropy2, kp)
            }

            val spread1 = field1.max() - field1.min()
            val spread2 = field2.max() - field2.min()
            val deltaSpread = abs(spread1 - spread2)

            // Новый режим KP_SPATIAL имеет более мягкие требования к spread
            val spreadTolerance = if (mode == GenerationMode.KP_SPATIAL) 4 else 2

            // Проверяем качество комбинации
            val field1Quality = if (mode == GenerationMode.KP_SPATIAL) {
                100f - calculateSpatialPenalty(field1) + calculateVisualBonus(field1)
            } else {
                abs(spread1 - targetSpread).toFloat()
            }

            val field2Quality = if (mode == GenerationMode.KP_SPATIAL) {
                100f - calculateSpatialPenalty(field2) + calculateVisualBonus(field2)
            } else {
                abs(spread2 - targetSpread).toFloat()
            }

            // Принимаем результат если качество хорошее
            if (mode == GenerationMode.KP_SPATIAL) {
                if (field1Quality > 50f && field2Quality > 50f) {
                    return Pair(field1, field2)
                }
            } else {
                if (abs(spread1 - targetSpread) <= spreadTolerance && abs(spread2 - targetSpread) <= spreadTolerance) {
                    return Pair(field1, field2)
                }
            }

            attempts++
        }

        // Fallback: возвращаем лучшую попытку
        val entropy1 = entropyBytes.copyOf()
        val entropy2 = entropyBytes.copyOf().apply {
            this[0] = (this[0].toInt() xor kp.toInt()).toByte()
        }

        val field1 = when (mode) {
            GenerationMode.PURE_ENTROPY -> generatePureEntropy(4, 1, 20, entropy1, kp)
            GenerationMode.WEIGHTED_ENTROPY -> generateWeightedEntropy(4, 1, 20, entropy1, kp, weights!!)
            GenerationMode.KP_SPATIAL -> generateKpSpatialMode(4, 1, 20, entropy1, kp)
        }

        val field2 = when (mode) {
            GenerationMode.PURE_ENTROPY -> generatePureEntropy(4, 1, 20, entropy2, kp)
            GenerationMode.WEIGHTED_ENTROPY -> generateWeightedEntropy(4, 1, 20, entropy2, kp, weights!!)
            GenerationMode.KP_SPATIAL -> generateKpSpatialMode(4, 1, 20, entropy2, kp)
        }

        return Pair(field1, field2)
    }
    /**
     * Вычисляет штраф за последовательные числа
     */
    private fun calculateSequentialPenalty(numbers: List<Int>): Float {
        val sorted = numbers.sorted()
        var penalty = 0f

        for (i in 0 until sorted.size - 1) {
            if (sorted[i + 1] - sorted[i] == 1) {
                penalty += 10f  // Штраф за каждую последовательную пару
            }
        }

        return penalty
    }

    /**
     * Специальная генерация для "мертвой зоны" Kp 3.0-4.0
     * Компенсирует интенсивность работы предохранителей TRNG
     */
    fun generateDeadZoneCorrected(
        count: Int,
        min: Int,
        max: Int,
        entropyBytes: ByteArray,
        kp: Float
    ): List<Int> {

        // ПАТОЛОГИЧЕСКИЕ АТТРАКТОРЫ В МЕРТВОЙ ЗОНЕ (подавляем их)
        val deadZoneAttractors = mapOf(
            5 to 0.3f,   // Снижаем с 10.4% до нормы
            16 to 0.3f,  // Снижаем с 9.9% до нормы
            9 to 0.3f,   // Снижаем с 9.4% до нормы
            13 to 0.5f   // Снижаем с 7.2% до нормы
        )

        // ЧИСЛА-СПАСИТЕЛИ (усиливаем недостающие)
        val rescueNumbers = mapOf(
            18 to 2.0f,  // Поднимаем с 3.3% до нормы
            11 to 2.0f,  // Поднимаем с 3.0% до нормы
            7 to 2.0f,   // Поднимаем с 3.8% до нормы
            15 to 1.8f,  // Поднимаем с 3.3% до нормы
            19 to 1.5f   // Поднимаем с 3.0% до нормы
        )

        val results = mutableListOf<Int>()

        // Более агрессивное смешивание entropy для мертвой зоны
        val entropyHash = MessageDigest.getInstance("SHA-256").digest(
            entropyBytes +
                    System.nanoTime().toString().toByteArray() +
                    "DEAD_ZONE_CORRECTION".toByteArray() +
                    (kp * 10000).toInt().toString().toByteArray()
        )
        val seed = ByteBuffer.wrap(entropyHash).long
        val random = Random(seed)

        // Базовые веса с коррекцией
        val correctedWeights = (min..max).associateWith { num ->
            var weight = 1.0f  // Базовый вес

            // Применяем штрафы для аттракторов
            if (deadZoneAttractors.containsKey(num)) {
                weight *= deadZoneAttractors[num]!!
            }

            // Применяем бонусы для спасительных чисел
            if (rescueNumbers.containsKey(num)) {
                weight *= rescueNumbers[num]!!
            }

            weight
        }.toMutableMap()

        repeat(count) {
            var bestCandidate = min
            var bestScore = Float.NEGATIVE_INFINITY

            // Увеличиваем количество попыток для лучшего качества
            repeat(50) { attempt ->
                val candidates = correctedWeights.keys.filter { it !in results }
                if (candidates.isEmpty()) return@repeat

                // Взвешенный выбор с дополнительной случайностью
                val candidate = if (random.nextFloat() < 0.8f) {
                    // 80% - взвешенный выбор с коррекцией
                    val totalWeight = candidates.sumOf { correctedWeights[it]!!.toDouble() }
                    var randomValue = random.nextDouble() * totalWeight

                    var selected = candidates.first()
                    for (num in candidates) {
                        randomValue -= correctedWeights[num]!!
                        if (randomValue <= 0) {
                            selected = num
                            break
                        }
                    }
                    selected
                } else {
                    // 20% - чисто случайный для разнообразия
                    candidates.random(random)
                }

                // Оценка кандидата с учетом анти-паттернов мертвой зоны
                val testField = results + candidate

                // СПЕЦИАЛЬНЫЕ ШТРАФЫ ДЛЯ МЕРТВОЙ ЗОНЫ:

                // 1. Штраф за аттракторы мертвой зоны
                val attractorPenalty = if (deadZoneAttractors.containsKey(candidate)) 20f else 0f

                // 2. Бонус за спасительные числа
                val rescueBonus = rescueNumbers[candidate]?.let { it * 15f } ?: 0f

                // 3. Анти-кластерный штраф (мертвая зона склонна к кластерам)
                val clusterPenalty = calculateSpatialPenalty(testField) * 1.5f

                // 4. Штраф за последовательности (1.86 в среднем в мертвой зоне)
                val sequentialPenalty = calculateSequentialPenalty(testField) * 10f

                // 5. Бонус за spread отличный от 7.0 (средний в мертвой зоне)
                val spreadBonus = if (testField.size >= 4) {
                    val spread = testField.max() - testField.min()
                    val spreadDiff = kotlin.math.abs(spread - 7.0f)
                    spreadDiff * 5f  // Бонус за отличие от проблемного spread
                } else 0f

                val score = rescueBonus + spreadBonus - attractorPenalty - clusterPenalty - sequentialPenalty

                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = candidate
                }
            }

            results.add(bestCandidate)
        }

        return results.sorted()
    }

    /**
     * Генерация двух полей для мертвой зоны с максимальной анти-зеркальностью
     */
    private fun generateDeadZoneFields(
        entropyBytes: ByteArray,
        kp: Float
    ): Pair<List<Int>, List<Int>> {

        // Создаем максимально разные entropy для полей
        val entropy1 = entropyBytes
        val entropy2 = entropyBytes.copyOf().apply {
            val deadZoneSalt = "ANTI_MIRROR_${System.nanoTime()}".toByteArray()
            for (i in indices) {
                this[i] = (this[i].toInt() xor
                        deadZoneSalt[i % deadZoneSalt.size].toInt() xor
                        (kp * 1000).toInt()).toByte()
            }
        }

        val field1 = generateDeadZoneCorrected(4, 1, 20, entropy1, kp)
        val field2 = generateDeadZoneCorrected(4, 1, 20, entropy2, kp)

        // ПРОВЕРЯЕМ КАЧЕСТВО РЕЗУЛЬТАТА
        val intersection = field1.intersect(field2.toSet()).size
        val totalClusters = calculateSpatialPenalty(field1 + field2)

        // Если результат все равно плохой - пересоздаем field2
        if (intersection > 0 || totalClusters > 15f) {
            val entropy3 = entropyBytes.copyOf().apply {
                val reSalt = "RESCUE_ATTEMPT_${kp}_${System.nanoTime()}".toByteArray()
                for (i in indices) {
                    this[i] = (this[i].toInt() xor reSalt[i % reSalt.size].toInt()).toByte()
                }
            }
            val newField2 = generateDeadZoneCorrected(4, 1, 20, entropy3, kp)
            return Pair(field1, newField2)
        }

        return Pair(field1, field2)
    }
}
