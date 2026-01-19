package com.example.entropyrng.data

import androidx.room.*

/**
 * Data Access Object для работы с таблицей lottery_draws
 * Все операции с базой данных выполняются через этот интерфейс
 */
@Dao
interface NumberDataDao {

    // ===== INSERT =====

    /**
     * Вставить одну запись
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: NumberData): Long

    /**
     * Вставить множество записей
     * Используется при импорте CSV
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dataList: List<NumberData>)

    // ===== QUERY =====

    /**
     * Получить все записи
     */
    @Query("SELECT * FROM lottery_draws ORDER BY date DESC, time DESC")
    suspend fun getAll(): List<NumberData>

    /**
     * Получить записи по источнику
     * @param source "lottery" | "generated" | "imported"
     */
    @Query("SELECT * FROM lottery_draws WHERE source = :source ORDER BY date DESC, time DESC")
    suspend fun getBySource(source: String): List<NumberData>

    /**
     * Получить только тиражи лотереи (исключая сгенерированные)
     */
    @Query("SELECT * FROM lottery_draws WHERE source IN ('lottery', 'imported') ORDER BY date DESC, time DESC")
    suspend fun getLotteryDrawsOnly(): List<NumberData>

    /**
     * Получить записи за определённый период
     * @param startDate формат "yyyy-MM-dd"
     * @param endDate формат "yyyy-MM-dd"
     */
    @Query("SELECT * FROM lottery_draws WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC, time DESC")
    suspend fun getByDateRange(startDate: String, endDate: String): List<NumberData>

    /**
     * Получить записи с определённым типом лотереи
     */
    @Query("SELECT * FROM lottery_draws WHERE lotteryType = :type ORDER BY date DESC, time DESC")
    suspend fun getByLotteryType(type: String): List<NumberData>

    /**
     * Подсчитать общее количество записей
     */
    @Query("SELECT COUNT(*) FROM lottery_draws")
    suspend fun getCount(): Int

    /**
     * Подсчитать количество записей по источнику
     */
    @Query("SELECT COUNT(*) FROM lottery_draws WHERE source = :source")
    suspend fun getCountBySource(source: String): Int

    /**
     * Получить последние N записей
     */
    @Query("SELECT * FROM lottery_draws ORDER BY date DESC, time DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<NumberData>

    // ===== DELETE =====

    /**
     * Удалить запись
     */
    @Delete
    suspend fun delete(data: NumberData)

    /**
     * Удалить все записи
     */
    @Query("DELETE FROM lottery_draws")
    suspend fun deleteAll()

    /**
     * Удалить записи по источнику
     */
    @Query("DELETE FROM lottery_draws WHERE source = :source")
    suspend fun deleteBySource(source: String)

    // ===== ANALYSIS QUERIES =====

    /**
     * Получить все числа из тиражей для анализа частот
     * Возвращает список всех чисел (с повторениями)
     */
    @Query("SELECT numbers FROM lottery_draws WHERE source IN ('lottery', 'imported')")
    suspend fun getAllNumbersForAnalysis(): List<String> // JSON строки
}
