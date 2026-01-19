package com.example.entropyrng.data

import androidx.room.*

/**
 * Data Access Object для работы с историей Kp индекса
 */
@Dao
interface KpHistoryDao {

    // ===== INSERT =====

    /**
     * Вставить запись Kp (или обновить если уже есть на эту дату/время)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(kpData: KpHistoryData): Long

    /**
     * Вставить множество записей
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(kpDataList: List<KpHistoryData>)

    // ===== QUERY =====

    /**
     * Получить все записи
     */
    @Query("SELECT * FROM kp_history ORDER BY date DESC, time DESC")
    suspend fun getAll(): List<KpHistoryData>

    /**
     * Получить Kp для конкретной даты и времени
     * @param date формат "yyyy-MM-dd"
     * @param time формат "HH:mm:ss"
     */
    @Query("SELECT * FROM kp_history WHERE date = :date AND time = :time LIMIT 1")
    suspend fun getByDateTime(date: String, time: String): KpHistoryData?

    /**
     * Получить ближайший Kp для даты и времени (в пределах ±3 часов)
     * Используется для связывания с тиражами лотереи
     */
    @Query("""
        SELECT * FROM kp_history 
        WHERE date = :date 
        AND time BETWEEN :timeStart AND :timeEnd
        ORDER BY ABS(
            CAST(substr(time, 1, 2) AS INTEGER) * 60 + CAST(substr(time, 4, 2) AS INTEGER) -
            (CAST(substr(:targetTime, 1, 2) AS INTEGER) * 60 + CAST(substr(:targetTime, 4, 2) AS INTEGER))
        ) ASC
        LIMIT 1
    """)
    suspend fun getNearestByDateTime(
        date: String,
        targetTime: String,
        timeStart: String,  // targetTime - 3 часа
        timeEnd: String     // targetTime + 3 часа
    ): KpHistoryData?

    /**
     * Получить записи за период
     */
    @Query("SELECT * FROM kp_history WHERE date BETWEEN :startDate AND :endDate ORDER BY date, time")
    suspend fun getByDateRange(startDate: String, endDate: String): List<KpHistoryData>

    /**
     * Получить последнюю запись
     */
    @Query("SELECT * FROM kp_history ORDER BY date DESC, time DESC LIMIT 1")
    suspend fun getLatest(): KpHistoryData?

    /**
     * Подсчитать общее количество записей
     */
    @Query("SELECT COUNT(*) FROM kp_history")
    suspend fun getCount(): Int

    /**
     * Получить средний Kp за весь период
     */
    @Query("SELECT AVG(kpValue) FROM kp_history")
    suspend fun getAverageKp(): Float?

    /**
     * Получить средний Kp за определённый период
     */
    @Query("SELECT AVG(kpValue) FROM kp_history WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getAverageKpInRange(startDate: String, endDate: String): Float?

    // ===== DELETE =====

    /**
     * Удалить запись
     */
    @Delete
    suspend fun delete(kpData: KpHistoryData)

    /**
     * Удалить все записи
     */
    @Query("DELETE FROM kp_history")
    suspend fun deleteAll()

    /**
     * Удалить старые записи (например, старше 1 года)
     */
    @Query("DELETE FROM kp_history WHERE date < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: String)

    // ===== ANALYSIS QUERIES =====

    /**
     * Получить все уникальные даты с Kp
     */
    @Query("SELECT DISTINCT date FROM kp_history ORDER BY date DESC")
    suspend fun getAllDates(): List<String>
}
