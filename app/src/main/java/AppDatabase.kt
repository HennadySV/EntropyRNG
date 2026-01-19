package com.example.entropyrng.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * База данных приложения
 * Хранит историю тиражей лотереи и сгенерированных чисел
 */
@Database(
    entities = [NumberData::class, KpHistoryData::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun numberDataDao(): NumberDataDao
    abstract fun kpHistoryDao(): KpHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Получить экземпляр базы данных (Singleton)
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "entropy_rng_database"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Можно добавить начальные данные при первом создании БД
                        }
                    })
                    .fallbackToDestructiveMigration() // Пересоздать БД при ошибках миграции
                    .build()

                INSTANCE = instance
                instance
            }
        }

        /**
         * Уничтожить экземпляр (для тестирования)
         */
        fun destroyInstance() {
            INSTANCE = null
        }
    }
}
