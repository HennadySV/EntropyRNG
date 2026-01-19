package com.example.entropyrng.data

import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * Конвертеры для Room Database
 * Преобразуют сложные типы в примитивы для хранения в SQLite
 */
class Converters {

    /**
     * Преобразует List<Int> в JSON строку
     * Пример: [1, 5, 12, 18] -> "[1,5,12,18]"
     */
    @TypeConverter
    fun fromIntList(value: List<Int>): String {
        val jsonArray = JSONArray()
        value.forEach { jsonArray.put(it) }
        return jsonArray.toString()
    }

    /**
     * Преобразует JSON строку обратно в List<Int>
     * Пример: "[1,5,12,18]" -> [1, 5, 12, 18]
     */
    @TypeConverter
    fun toIntList(value: String): List<Int> {
        val list = mutableListOf<Int>()
        try {
            val jsonArray = JSONArray(value)
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getInt(i))
            }
        } catch (e: Exception) {
            // В случае ошибки возвращаем пустой список
        }
        return list
    }
}
