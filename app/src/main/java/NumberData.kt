package com.example.entropyrng.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

/**
 * Модель данных для тиража лотереи
 * Хранит как исторические данные, так и сгенерированные приложением
 */
@Entity(tableName = "lottery_draws")
@TypeConverters(Converters::class)
data class NumberData(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    // Основные данные тиража
    val iteration: String,              // Номер тиража (например, "011456")
    val date: String,                   // Дата в формате "yyyy-MM-dd"
    val time: String,                   // Время в формате "HH:mm:ss"
    val numbers: List<Int>,             // Выпавшие числа [1,5,12,18,...]

    // Метаданные источника
    val source: String,                 // "lottery" | "generated" | "imported"
    val lotteryType: String? = null,    // "Премьер" | "Топ 12" и т.д.

    // Данные энтропии на момент тиража
    val kpIndex: Float? = null,         // Индекс солнечной активности Kp (0-9)
    val magneticFieldX: Float? = null,  // Магнитное поле X (μT)
    val magneticFieldY: Float? = null,  // Магнитное поле Y (μT)
    val magneticFieldZ: Float? = null,  // Магнитное поле Z (μT)

    // Дополнительные метаданные
    val metadata: String = "",          // JSON с дополнительной информацией
    val createdAt: Long = System.currentTimeMillis() // Timestamp создания записи
)
