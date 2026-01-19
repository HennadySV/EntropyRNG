package com.example.entropyrng.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Модель данных для истории Kp индекса
 * Хранит значения солнечной активности с временными метками
 */
@Entity(
    tableName = "kp_history",
    indices = [
        Index(value = ["date", "time"], unique = true) // Уникальность по дате+времени
    ]
)
data class KpHistoryData(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val date: String,              // "yyyy-MM-dd"
    val time: String,              // "HH:mm:ss"
    val kpValue: Float,            // 0-9 (планетарный K-индекс)

    val source: String = "auto",   // "auto" | "manual" | "generation"

    val createdAt: Long = System.currentTimeMillis()
)
