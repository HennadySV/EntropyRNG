package com.example.entropyrng.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.entropyrng.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Экспорт базы данных в CSV
 * Поддерживает два варианта:
 *   - ExportMode.LOTTERY_DRAWS  → таблица lottery_draws
 *   - ExportMode.KP_HISTORY     → таблица kp_history
 *   - ExportMode.ALL            → обе таблицы в один ZIP (или два файла)
 */
class DatabaseExporter(private val context: Context, private val db: AppDatabase) {

    data class ExportResult(
        val success: Boolean,
        val filesWritten: List<String>,   // Имена файлов
        val rowsExported: Int,
        val errorMessage: String? = null
    )

    // ───────────────────────────────────────────────
    //  PUBLIC: экспорт всего
    // ───────────────────────────────────────────────

    suspend fun exportAll(): ExportResult = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val results = mutableListOf<String>()
        var totalRows = 0

        try {
            // 1. lottery_draws
            val drawsResult = exportLotteryDraws(timestamp)
            if (drawsResult.success) {
                results += drawsResult.filesWritten
                totalRows += drawsResult.rowsExported
            } else {
                return@withContext drawsResult
            }

            // 2. kp_history
            val kpResult = exportKpHistory(timestamp)
            if (kpResult.success) {
                results += kpResult.filesWritten
                totalRows += kpResult.rowsExported
            } else {
                return@withContext kpResult
            }

            ExportResult(true, results, totalRows)

        } catch (e: Exception) {
            ExportResult(false, emptyList(), 0, e.message)
        }
    }

    // ───────────────────────────────────────────────
    //  PRIVATE: lottery_draws → CSV
    // ───────────────────────────────────────────────

    private suspend fun exportLotteryDraws(timestamp: String): ExportResult =
        withContext(Dispatchers.IO) {
            val fileName = "entropy_draws_$timestamp.csv"

            try {
                val draws = db.numberDataDao().getAll()

                val csv = buildString {
                    // Заголовок
                    appendLine(
                        "id,iteration,date,time,source,lotteryType," +
                                "field1_n1,field1_n2,field1_n3,field1_n4," +
                                "field2_n1,field2_n2,field2_n3,field2_n4," +
                                "all_numbers," +
                                "spread_field1,spread_field2,delta_spread," +
                                "kpIndex," +
                                "magneticX,magneticY,magneticZ," +
                                "createdAt"
                    )

                    draws.forEach { d ->
                        val nums = d.numbers  // List<Int>
                        val f1 = nums.take(4)
                        val f2 = if (nums.size >= 8) nums.drop(4).take(4) else emptyList()

                        val spread1 = if (f1.size == 4) f1.max() - f1.min() else ""
                        val spread2 = if (f2.size == 4) f2.max() - f2.min() else ""
                        val delta = if (f1.size == 4 && f2.size == 4)
                            kotlin.math.abs(f1.max() - f1.min() - (f2.max() - f2.min())) else ""

                        appendLine(
                            listOf(
                                d.id,
                                d.iteration,
                                d.date,
                                d.time,
                                d.source,
                                d.lotteryType ?: "",
                                // поле 1
                                f1.getOrElse(0) { "" },
                                f1.getOrElse(1) { "" },
                                f1.getOrElse(2) { "" },
                                f1.getOrElse(3) { "" },
                                // поле 2
                                f2.getOrElse(0) { "" },
                                f2.getOrElse(1) { "" },
                                f2.getOrElse(2) { "" },
                                f2.getOrElse(3) { "" },
                                // все числа строкой
                                "\"${nums.joinToString("|")}\"",
                                // spread
                                spread1, spread2, delta,
                                // датчики
                                d.kpIndex ?: "",
                                d.magneticFieldX ?: "",
                                d.magneticFieldY ?: "",
                                d.magneticFieldZ ?: "",
                                d.createdAt
                            ).joinToString(",")
                        )
                    }
                }

                writeFile(fileName, csv)
                ExportResult(true, listOf(fileName), draws.size)

            } catch (e: Exception) {
                ExportResult(false, emptyList(), 0, "Ошибка draws: ${e.message}")
            }
        }

    // ───────────────────────────────────────────────
    //  PRIVATE: kp_history → CSV
    // ───────────────────────────────────────────────

    private suspend fun exportKpHistory(fileTimestamp: String): ExportResult =
        withContext(Dispatchers.IO) {
            val fileName = "entropy_kp_$fileTimestamp.csv"

            try {
                val kpRecords = db.kpHistoryDao().getAll()

                val csv = buildString {
                    appendLine("id,date,time,kpValue,source,createdAt")

                    kpRecords.forEach { k ->
                        appendLine(
                            listOf(
                                k.id,
                                k.date,
                                k.time,
                                k.kpValue,
                                k.source,
                                k.createdAt
                            ).joinToString(",")
                        )
                    }
                }

                writeFile(fileName, csv)
                ExportResult(true, listOf(fileName), kpRecords.size)

            } catch (e: Exception) {
                ExportResult(false, emptyList(), 0, "Ошибка kp: ${e.message}")
            }
        }

    // ───────────────────────────────────────────────
    //  PRIVATE: запись файла (API 29+ и ниже)
    // ───────────────────────────────────────────────

    private fun writeFile(fileName: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ → MediaStore (не нужны разрешения WRITE_EXTERNAL_STORAGE)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("MediaStore insert failed")

            resolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(content)
                }
            }
        } else {
            // Android 9 и ниже → прямая запись в Downloads
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            OutputStreamWriter(java.io.FileOutputStream(File(downloadsDir, fileName)), Charsets.UTF_8).use { it.write(content) }
        }
    }
}