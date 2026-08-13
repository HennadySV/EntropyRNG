package com.example.entropyrng

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.entropyrng.analysis.EntropyAnalyzer
import com.example.entropyrng.data.AppDatabase
import com.example.entropyrng.data.NumberData
import com.example.entropyrng.data.KpIndexManager
import com.example.entropyrng.data.KpResult
import com.example.entropyrng.generation.WeightedGenerator
import com.example.entropyrng.import.LotteryDataImporter
import com.example.entropyrng.export.DatabaseExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Поддерживаемые форматы лотереи.
 *
 * PREMIER_4X20 / PREMIER_4X17 хранят собственную историю тиражей (импорт CSV,
 * "Веса" и "KP+Space" считаются каждый по своим данным, см. lotteryType в NumberData)
 * и всегда генерируют два поля по 4 числа.
 *
 * CUSTOM — произвольный диапазон/количество (например "8 из 72"): без истории,
 * всегда один кортеж на `count` чисел, без принудительной разбивки на поля.
 */
enum class LotteryFormat(
    val label: String,       // значение, сохраняемое в NumberData.lotteryType
    val displayName: String, // текст в выпадающем списке
    val minNumber: Int,
    val maxNumber: Int,
    val fieldSize: Int,      // чисел в одном поле, если split == true
    val split: Boolean,      // делить ли результат на два поля
    val storesHistory: Boolean // доступны ли импорт CSV / "Веса" / данные для "KP+Space"
) {
    PREMIER_4X20("Премьер 4х20", "Премьер 4х20 (2 поля по 4, 1-20)", 1, 20, 4, true, true),
    PREMIER_4X17("Премьер 4х17", "Премьер 4х17 (2 поля по 4, 1-17)", 1, 17, 4, true, true),
    CUSTOM("", "Произвольный (одно поле)", 1, 100, 0, false, false)
}

class MainActivity : AppCompatActivity(), SensorEventListener {

    // ===== Сенсоры и энтропия =====
    private lateinit var sensorManager: SensorManager
    private lateinit var magnetometer: Sensor
    private var audioRecord: AudioRecord? = null
    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraManager: CameraManager
    private var imageReader: ImageReader? = null
    private val entropyBuffer = ArrayBlockingQueue<ByteArray>(100)
    private lateinit var handler: Handler
    private lateinit var handlerThread: HandlerThread

    // ===== База данных и анализ =====
    private lateinit var db: AppDatabase
    private lateinit var analyzer: EntropyAnalyzer
    private lateinit var generator: WeightedGenerator
    private var currentGenerationMode = WeightedGenerator.GenerationMode.PURE_ENTROPY
    private var isKpSpatialMode = false  // Флаг для нового режима
    private lateinit var importer: LotteryDataImporter
    private lateinit var kpManager: KpIndexManager
    private lateinit var exporter: DatabaseExporter

    // ===== Формат лотереи (4х20 / 4х17 / произвольный) =====
    private var currentLotteryFormat: LotteryFormat = LotteryFormat.PREMIER_4X20

    // ===== Kp: только реально полученное значение, никогда не подставляем случайное =====
    private var lastRealKp: Float? = null

    // ===== Кеш весов =====
    private var currentWeights: Map<Int, Float>? = null
    private var lastAnalysisResult: EntropyAnalyzer.AnalysisResult? = null

    // ===== UI элементы =====
    private lateinit var editMin: EditText
    private lateinit var editMax: EditText
    private lateinit var editCount: EditText
    private lateinit var generateButton: Button
    private lateinit var importButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var exportButton: Button
    private lateinit var modeSwitch: Switch
    private lateinit var outputText: TextView
    private lateinit var weightsInfo: TextView
    private lateinit var solarInfo: TextView
    private lateinit var statsInfo: TextView
    private lateinit var lotteryTypeSpinner: Spinner
    private lateinit var lotteryFormatInfo: TextView

    // ===== Лаунчер для импорта CSV =====
    private val pickCsvLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val format = currentLotteryFormat
            if (!format.storesHistory) {
                Toast.makeText(this, "Для произвольного формата импорт истории недоступен. Выберите Премьер 4х20 или 4х17.", Toast.LENGTH_LONG).show()
                return@let
            }
            lifecycleScope.launch {
                try {
                    importButton.isEnabled = false
                    statsInfo.text = "Импорт..."
                    Toast.makeText(this@MainActivity, "Импорт запущен (${format.label})...", Toast.LENGTH_SHORT).show()

                    val result = importer.importFromCsv(it, format.label, format.maxNumber)

                    if (result.success) {
                        // Детальное сообщение: добавлено / обновлено / пропущено
                        Toast.makeText(
                            this@MainActivity,
                            "✓ ${result.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        analyzeButton.isEnabled = true
                        updateStats()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "✗ ${result.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ошибка импорта: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    e.printStackTrace()
                } finally {
                    importButton.isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация компонентов
        initializeComponents()
        initializeUI()
        setupPermissions()

        // Восстановление состояния
        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
        }

        // Обновить статистику БД и проверить наличие данных
        lifecycleScope.launch {
            updateStats()
            checkDatabaseAndEnableButtons()
        }

    }

    /**
     * Проверяет наличие данных в БД и активирует кнопки
     */
    private suspend fun checkDatabaseAndEnableButtons() = withContext(Dispatchers.IO) {
        val format = currentLotteryFormat
        val lotteryCount = if (format.storesHistory) {
            db.numberDataDao().getLotteryDrawsOnlyByType(format.label).size
        } else 0

        withContext(Dispatchers.Main) {
            if (format.storesHistory && lotteryCount > 0) {
                // Есть данные по текущему формату → активируем кнопку "Анализ"
                analyzeButton.isEnabled = true
                Toast.makeText(
                    this@MainActivity,
                    "Найдено $lotteryCount тиражей (${format.label}) в БД",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Нет данных по этому формату → кнопка остаётся неактивной
                analyzeButton.isEnabled = false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Сохраняем состояние
        outState.putBoolean("hasWeights", currentWeights != null)
        outState.putBoolean("analyzeEnabled", analyzeButton.isEnabled)
        outState.putBoolean("switchEnabled", modeSwitch.isEnabled)
        outState.putBoolean("switchChecked", modeSwitch.isChecked)

        // Сохраняем веса (если есть)
        if (currentWeights != null) {
            val weightsArray = currentWeights!!.entries.map { "${it.key}:${it.value}" }.toTypedArray()
            outState.putStringArray("weights", weightsArray)
        }
    }

    private fun restoreState(savedInstanceState: Bundle) {
        // Восстанавливаем состояние UI
        val hasWeights = savedInstanceState.getBoolean("hasWeights", false)
        val analyzeEnabled = savedInstanceState.getBoolean("analyzeEnabled", false)
        val switchEnabled = savedInstanceState.getBoolean("switchEnabled", false)
        val switchChecked = savedInstanceState.getBoolean("switchChecked", false)

        analyzeButton.isEnabled = analyzeEnabled
        modeSwitch.isEnabled = switchEnabled
        modeSwitch.isChecked = switchChecked

        // Восстанавливаем веса
        if (hasWeights) {
            val weightsArray = savedInstanceState.getStringArray("weights")
            if (weightsArray != null) {
                val restoredWeights = mutableMapOf<Int, Float>()
                weightsArray.forEach { entry ->
                    val parts = entry.split(":")
                    if (parts.size == 2) {
                        val key = parts[0].toIntOrNull()
                        val value = parts[1].toFloatOrNull()
                        if (key != null && value != null) {
                            restoredWeights[key] = value
                        }
                    }
                }
                if (restoredWeights.isNotEmpty()) {
                    currentWeights = restoredWeights
                    // Показываем что веса восстановлены
                    weightsInfo.text = "Веса восстановлены (${restoredWeights.size} чисел)"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Перезапускаем только датчики (не камеру, она уже запущена)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // Перезапускаем только магнитометр и микрофон
            startMagnetometer()
            startMicrophone()
        }
    }

    private fun initializeComponents() {
        // Сенсоры
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)!!
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        handlerThread = HandlerThread("CameraThread").apply { start() }
        handler = Handler(handlerThread.looper)

        // БД и модули
        db = AppDatabase.getDatabase(this)
        analyzer = EntropyAnalyzer(db)
        generator = WeightedGenerator()
        importer = LotteryDataImporter(this)
        kpManager = KpIndexManager(this)
        exporter = DatabaseExporter(this, db)

    }

    private fun initializeUI() {
        // Поля ввода
        editMin = findViewById(R.id.editMin)
        editMax = findViewById(R.id.editMax)
        editCount = findViewById(R.id.editCount)

        // Кнопки
        generateButton = findViewById(R.id.generateButton)
        importButton = findViewById(R.id.importButton)
        analyzeButton = findViewById(R.id.analyzeButton)
        exportButton = findViewById(R.id.exportButton)
        modeSwitch = findViewById(R.id.modeSwitch)

        // Текстовые поля
        outputText = findViewById(R.id.outputText)
        weightsInfo = findViewById(R.id.weightsInfo)
        solarInfo = findViewById(R.id.solarInfo)
        statsInfo = findViewById(R.id.statsInfo)
        lotteryFormatInfo = findViewById(R.id.lotteryFormatInfo)

        // Выбор формата лотереи
        lotteryTypeSpinner = findViewById(R.id.lotteryTypeSpinner)
        val formats = LotteryFormat.values()
        lotteryTypeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            formats.map { it.displayName }
        )
        lotteryTypeSpinner.setSelection(formats.indexOf(currentLotteryFormat))
        lotteryTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                applyLotteryFormat(formats[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        applyLotteryFormat(currentLotteryFormat)

        // Обработчики событий
        generateButton.setOnClickListener { onGenerateClick() }
        importButton.setOnClickListener { onImportClick() }
        analyzeButton.setOnClickListener { onAnalyzeClick() }
        exportButton.setOnClickListener { onExportClick() }

        // Кнопка режимов (ТОЛЬКО ОДИН РАЗ)
        try {
            val modeToggleButton = findViewById<Button>(R.id.modeToggleButton)
            modeToggleButton?.setOnClickListener { toggleGenerationMode() }
            updateModeButtonText()
            updateModeDescription()
        } catch (e: Exception) {
            // Кнопка не найдена - не критично
        }

        // Обновление Kp: только реальные данные NOAA через KpIndexManager (сохраняет в БД сам).
        // ВАЖНО: при сбое сети/API НЕ подставляем случайное число — честно показываем ошибку
        // и не трогаем lastRealKp, иначе KP+Space будет тайком генерировать на рандоме,
        // выдавая его за "реальный космос" (это и был баг №1).
        solarInfo.setOnClickListener {
            lifecycleScope.launch {
                solarInfo.text = "Kp: загружаю из космоса..."

                when (val result = kpManager.fetchAndSaveCurrentKp()) {
                    is KpResult.Success -> {
                        lastRealKp = result.value
                        solarInfo.text = "Kp: ${result.value} (NOAA, реальные данные)"
                        updateModeDescription()
                        Toast.makeText(this@MainActivity, "Kp получен: ${result.value}", Toast.LENGTH_SHORT).show()
                    }
                    is KpResult.Error -> {
                        solarInfo.text = "Kp: ошибка API (${result.message})"
                        Toast.makeText(
                            this@MainActivity,
                            "Не удалось получить Kp: ${result.message}. Прежнее значение не менялось.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Остальные обработчики
        modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && currentWeights == null) {
                Toast.makeText(this, "Сначала проведите анализ!", Toast.LENGTH_SHORT).show()
                modeSwitch.isChecked = false
            }
        }

        analyzeButton.isEnabled = false
        modeSwitch.isEnabled = false
    }

    /**
     * Переключение активного формата лотереи.
     * Премьер 4х20 / 4х17: диапазон и количество фиксированы, доступны импорт/анализ/веса,
     * генерация всегда даёт 2 поля по 4 числа.
     * Произвольный: диапазон/количество свободные, история и веса недоступны,
     * генерация всегда одним полем (например "8 из 72").
     */
    private fun applyLotteryFormat(format: LotteryFormat) {
        currentLotteryFormat = format

        if (format.storesHistory) {
            editMin.setText(format.minNumber.toString())
            editMax.setText(format.maxNumber.toString())
            editCount.setText((format.fieldSize * 2).toString())
            editMin.isEnabled = false
            editMax.isEnabled = false
            editCount.isEnabled = false
            lotteryFormatInfo.text = "2 поля по ${format.fieldSize} числа, диапазон ${format.minNumber}-${format.maxNumber}"
            importButton.isEnabled = true
        } else {
            editMin.isEnabled = true
            editMax.isEnabled = true
            editCount.isEnabled = true
            lotteryFormatInfo.text = "Одно поле, диапазон и количество — свои"
            importButton.isEnabled = false
        }

        // У разных форматов разная (или отсутствующая) история — старые веса/анализ больше не актуальны
        currentWeights = null
        lastAnalysisResult = null
        weightsInfo.text = "Топ числа: -"
        modeSwitch.isChecked = false
        modeSwitch.isEnabled = false

        // "Веса" и "KP+Space" в произвольном формате не имеют смысла — истории для них нет
        if (!format.storesHistory && currentGenerationMode != WeightedGenerator.GenerationMode.PURE_ENTROPY) {
            currentGenerationMode = WeightedGenerator.GenerationMode.PURE_ENTROPY
            updateModeButtonText()
        }
        updateModeDescription()

        lifecycleScope.launch { checkDatabaseAndEnableButtons() }
    }

    private fun setupPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                1
            )
        } else {
            startCollectors()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCollectors()
        }
    }

    // ===== Обработчики кнопок =====

    private fun onGenerateClick() {
        val format = currentLotteryFormat

        // Режим KP+Space без реально полученного Kp не запускаем — раньше здесь
        // тихо подставлялось случайное число, выданное за "реальные данные из космоса" (баг №1).
        if (currentGenerationMode == WeightedGenerator.GenerationMode.KP_SPATIAL && lastRealKp == null) {
            Toast.makeText(
                this,
                "Нет актуального Kp. Нажмите на строку Kp ниже, чтобы получить реальное значение с NOAA.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        lifecycleScope.launch {
            try {
                generateButton.isEnabled = false
                outputText.text = "Собираем энтропию..."

                // Получаем энтропию и параметры
                val entropyData = collectEntropy()

                // Диапазон и количество берём из формата лотереи (Премьер 4х20/4х17 — фиксированные,
                // Произвольный — то, что ввёл пользователь; поля editMin/Max/Count заблокированы/разблокированы
                // соответственно в applyLotteryFormat()).
                val min = if (format.storesHistory) format.minNumber else (editMin.text.toString().toIntOrNull() ?: 1)
                val userMax = if (format.storesHistory) format.maxNumber else (editMax.text.toString().toIntOrNull() ?: 20)
                val validMax = userMax.coerceAtLeast(min)
                val count = if (format.storesHistory) format.fieldSize * 2 else (editCount.text.toString().toIntOrNull() ?: 8).coerceAtLeast(1)
                val splitMode = format.split
                val fieldSize = format.fieldSize

                // Только реально полученное значение (проверено выше для KP_SPATIAL);
                // для остальных режимов kp не влияет на сами числа, только на доп. энтропию поля 2.
                val kp = lastRealKp ?: 0f
                val hasRealKp = lastRealKp != null
                val isDeadZone = hasRealKp && kp in 3.0f..4.0f
                val kpZoneInfo = when {
                    !hasRealKp -> "— нет данных"
                    kp <= 2.0f -> "🔷 ШТИЛЬ"
                    kp in 2.0f..3.0f -> "🔹 НОРМА"
                    kp in 3.0f..4.0f -> "⚠️ МЕРТВАЯ ЗОНА"
                    else -> "⚡ БУРЯ"
                }
                if (isDeadZone && currentGenerationMode == WeightedGenerator.GenerationMode.KP_SPATIAL) {
                    outputText.text = "⚠️ Обнаружена мертвая зона!\nКомпенсируем предохранители TRNG..."
                }

                // Для форматов без собственной вручную откалиброванной таблицы аттракторов (всё кроме 4х20)
                // KP+Space считает веса из РЕАЛЬНОЙ истории тиражей этого формата, а не из чужой таблицы под 4х20.
                val kpAttractorOverride: Map<Int, Float>? =
                    if (currentGenerationMode == WeightedGenerator.GenerationMode.KP_SPATIAL && format != LotteryFormat.PREMIER_4X20) {
                        analyzer.calculateKpAdjustedWeights(min, validMax, kp, if (format.storesHistory) format.label else null)
                    } else null

                // Генерируем числа в зависимости от режима
                val numbers = if (splitMode) {
                    // Разная энтропия для полей против зеркальности
                    val entropy1 = entropyData
                    val entropy2 = entropyData.copyOf().apply {
                        // Микшируем энтропию для field2 + добавляем Kp и время
                        val timeBytes = System.currentTimeMillis().toString().toByteArray()
                        val kpBytes = (kp * 1000).toInt().toString().toByteArray()

                        for (i in indices) {
                            this[i] = (this[i].toInt() xor
                                    timeBytes[i % timeBytes.size].toInt() xor
                                    kpBytes[i % kpBytes.size].toInt()).toByte()
                        }
                    }

                    val (field1, field2) = when (currentGenerationMode) {
                        WeightedGenerator.GenerationMode.KP_SPATIAL -> {
                            val f1 = generator.generateKpSpatialMode(fieldSize, min, validMax, entropy1, kp, kpAttractorOverride)
                            val f2 = generator.generateKpSpatialMode(fieldSize, min, validMax, entropy2, kp, kpAttractorOverride)
                            Pair(f1, f2)
                        }
                        WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY -> {
                            val weights = currentWeights ?: emptyMap()
                            val f1 = generator.generateWeightedEntropy(fieldSize, min, validMax, entropy1, kp, weights)
                            val f2 = generator.generateWeightedEntropy(fieldSize, min, validMax, entropy2, kp, weights)
                            Pair(f1, f2)
                        }
                        else -> {
                            val f1 = generator.generatePureEntropy(fieldSize, min, validMax, entropy1, kp)
                            val f2 = generator.generatePureEntropy(fieldSize, min, validMax, entropy2, kp)
                            Pair(f1, f2)
                        }
                    }
                    field1 + field2
                } else {
                    // Одно поле целиком (например "8 из 72": count=8, без разбивки)
                    when (currentGenerationMode) {
                        WeightedGenerator.GenerationMode.KP_SPATIAL -> {
                            generator.generateKpSpatialMode(count, min, validMax, entropyData, kp, kpAttractorOverride)
                        }
                        WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY -> {
                            val weights = currentWeights ?: emptyMap()
                            generator.generateWeightedEntropy(count, min, validMax, entropyData, kp, weights)
                        }
                        else -> {
                            generator.generatePureEntropy(count, min, validMax, entropyData, kp)
                        }
                    }
                }

                val magneticData = entropyData.take(12)
                val magneticX = magneticData.getOrNull(0)?.toFloat()
                val magneticY = magneticData.getOrNull(4)?.toFloat()
                val magneticZ = magneticData.getOrNull(8)?.toFloat()

                val data = NumberData(
                    iteration = "GEN-${System.currentTimeMillis()}",
                    date = java.time.LocalDate.now().toString(),
                    time = java.time.LocalTime.now().toString(),
                    numbers = numbers,
                    source = "generated",
                    lotteryType = if (format.storesHistory) format.label else "",
                    kpIndex = lastRealKp, // null если Kp не запрашивался — без фиктивных нулей в истории
                    magneticFieldX = magneticX,
                    magneticFieldY = magneticY,
                    magneticFieldZ = magneticZ,
                    metadata = "Mode: ${currentGenerationMode.name}"
                )

                withContext(Dispatchers.IO) {
                    db.numberDataDao().insert(data)

                    // Логируем Kp только если он реальный — иначе в kp_history не должно попадать
                    // ничего сгенерированного/случайного.
                    if (hasRealKp) {
                        kpManager.saveKpForGeneration(kp)
                    }
                }

                // Показать результат
                val modeIndicator = when (currentGenerationMode) {
                    WeightedGenerator.GenerationMode.PURE_ENTROPY -> "🎲"
                    WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY -> "⚖️"
                    WeightedGenerator.GenerationMode.KP_SPATIAL -> "🌟"
                }

                val modeStr = when (currentGenerationMode) {
                    WeightedGenerator.GenerationMode.PURE_ENTROPY -> "Чистая энтропия"
                    WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY -> "Калиброванный"
                    WeightedGenerator.GenerationMode.KP_SPATIAL -> "Kp-Spatial"
                }

                val kpDisplay = if (hasRealKp) kp.toString() else "не запрошен"

                val outputStr = if (splitMode) {
                    val field1 = numbers.take(fieldSize)
                    val field2 = numbers.drop(fieldSize)
                    val spread1 = field1.max() - field1.min()
                    val spread2 = field2.max() - field2.min()
                    val spreadDiff = kotlin.math.abs(spread1 - spread2)

                    // Проверка на зеркальность (диагностика)
                    val intersection = field1.intersect(field2.toSet()).size
                    val mirrorWarning = if (intersection >= 2) " ⚠️ Зеркальность: $intersection" else ""

                    val formatLabel = if (format.storesHistory) format.label else "Свой формат"
                    val baseText = """
                    [$formatLabel] [$modeStr] Kp: $kpDisplay

                    ${modeIndicator} Поле 1: ${field1.joinToString(", ")}
                    spread: $spread1

                    ${modeIndicator} Поле 2: ${field2.joinToString(", ")}
                    spread: $spread2

                    Δ spread: $spreadDiff$mirrorWarning
                    """.trimIndent()

                    if (currentGenerationMode == WeightedGenerator.GenerationMode.KP_SPATIAL) {
                        val kpLevel = when {
                            kp > 4.0f -> "БУРЯ ⚡"
                            kp > 3.0f -> "ВЫСОКИЙ 🔸"
                            kp > 1.5f -> "СРЕДНИЙ 🔹"
                            else -> "НИЗКИЙ 🔷"
                        }

                        val correctionInfo = if (isDeadZone) {
                            "\n🛠️ КОРРЕКЦИЯ активна"
                        } else {
                            ""
                        }

                        baseText + "\n\n🌟 Режим: $kpLevel ($kpZoneInfo)" + correctionInfo
                    } else {
                        baseText
                    }
                } else {
                    // Одно поле (Произвольный формат)
                    "[$modeStr]\nKp: $kpDisplay\n${modeIndicator} ${numbers.joinToString(", ")}"
                }

                outputText.text = outputStr
                updateStats()

            } catch (e: Exception) {
                outputText.text = "Ошибка: ${e.message}"
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            } finally {
                generateButton.isEnabled = true
            }
        }
    }

    private fun onImportClick() {
        pickCsvLauncher.launch("text/*")
    }

    private fun onAnalyzeClick() {
        val format = currentLotteryFormat
        if (!format.storesHistory) {
            Toast.makeText(this, "Для произвольного формата нет истории для анализа.", Toast.LENGTH_LONG).show()
            return
        }

        analyzeButton.isEnabled = false
        weightsInfo.text = "Анализ..."

        lifecycleScope.launch {
            try {
                val min = format.minNumber
                val validMax = format.maxNumber

                // Считаем веса только по истории ВЫБРАННОГО формата (4х20 и 4х17 не смешиваются)
                val result = analyzer.analyzeAndCalculateWeights(min, validMax, true, format.label)

                lastAnalysisResult = result
                currentWeights = result.weights

                if (result.totalDraws > 0) {
                    // Показать топ-5 чисел
                    val topText = result.topNumbers.take(5)
                        .joinToString(", ") { "${it.first}(${String.format("%.1f%%", it.second * 100)})" }

                    weightsInfo.text = "Топ: $topText"

                    Toast.makeText(
                        this@MainActivity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()

                    modeSwitch.isEnabled = true
                } else {
                    weightsInfo.text = "Нет данных"
                    Toast.makeText(
                        this@MainActivity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                weightsInfo.text = "Ошибка анализа"
                Toast.makeText(
                    this@MainActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                e.printStackTrace()
            } finally {
                // ВАЖНО: Всегда разблокируем кнопку
                analyzeButton.isEnabled = true
            }
        }
    }

    // ===== Сбор энтропии =====

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startCollectors() {
        startMagnetometer()
        startMicrophone()
        startCamera()
    }

    private fun startMagnetometer() {
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_FASTEST)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val bytes = ByteBuffer.allocate(12).apply {
                putFloat(event.values[0])
                putFloat(event.values[1])
                putFloat(event.values[2])
            }.array()
            entropyBuffer.offer(bytes)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startMicrophone() {
        // Если микрофон уже запущен, не запускаем снова
        if (audioRecord != null && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            return
        }

        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            audioRecord?.startRecording()

            thread {
                val buffer = ByteArray(bufferSize)
                while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        audioRecord?.read(buffer, 0, bufferSize)
                        entropyBuffer.offer(buffer.copyOf())
                    } catch (e: Exception) {
                        // Ошибка чтения - выходим из цикла
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Не удалось запустить микрофон - продолжаем без него
            e.printStackTrace()
        }
    }

    private fun startCamera() {
        val cameraId = "0"
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        // Если камера уже открыта, не открываем снова
        if (cameraDevice != null) return

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera

                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                        val previewSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                            .getOutputSizes(ImageFormat.YUV_420_888).first { it.width <= 320 && it.height <= 240 }

                        imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
                        imageReader?.setOnImageAvailableListener({ reader ->
                            val image = reader.acquireLatestImage()
                            if (image != null) {
                                try {
                                    val yPlane = image.planes[0]
                                    val bytes = ByteArray(yPlane.buffer.remaining())
                                    yPlane.buffer.get(bytes)
                                    entropyBuffer.offer(bytes)
                                } catch (e: Exception) {
                                    // Игнорируем ошибки чтения
                                } finally {
                                    image.close()
                                }
                            }
                        }, handler)

                        val surfaces = listOf(imageReader!!.surface)

                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    // Проверяем что камера всё ещё жива
                                    if (cameraDevice == null) return

                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    request.addTarget(imageReader!!.surface)
                                    request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                    session.setRepeatingRequest(request.build(), null, handler)
                                } catch (e: Exception) {
                                    // Камера умерла - ничего не делаем
                                    e.printStackTrace()
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                // Сессия не создана - ничего не делаем
                            }
                        }, handler)
                    } catch (e: Exception) {
                        // Ошибка при настройке камеры
                        e.printStackTrace()
                        cameraDevice?.close()
                        cameraDevice = null
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, handler)
        } catch (e: Exception) {
            // Не удалось открыть камеру - продолжаем без неё
            e.printStackTrace()
        }
    }

    private suspend fun collectEntropy(): ByteArray = withContext(Dispatchers.IO) {
        entropyBuffer.clear()

        val rawEntropy = ByteArray(1024)
        var offset = 0
        val timeout = System.currentTimeMillis() + 5000

        while (offset < 1024 && System.currentTimeMillis() < timeout) {
            val chunk = entropyBuffer.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            System.arraycopy(chunk, 0, rawEntropy, offset, chunk.size.coerceAtMost(1024 - offset))
            offset += chunk.size
        }

        if (offset < 1024) {
            throw IllegalStateException("Недостаточно энтропии")
        }

        rawEntropy
    }

    // ===== Сохранение в БД =====

    private suspend fun saveGenerated(
        numbers: List<Int>,
        min: Int,
        max: Int,
        kp: Float
    ) = withContext(Dispatchers.IO) {
        val data = NumberData(
            iteration = "GEN-${System.currentTimeMillis()}",
            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date()),
            time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date()),
            numbers = numbers,
            source = "generated",
            kpIndex = kp,
            metadata = "Range: $min-$max, Mode: ${if (modeSwitch.isChecked) "weighted" else "pure"}"
        )
        db.numberDataDao().insert(data)

        // Обновить статистику
        withContext(Dispatchers.Main) {
            updateStats()
        }
    }

    // ===== Статистика =====

    private suspend fun updateStats() {
        val stats = withContext(Dispatchers.IO) {
            analyzer.getDatabaseStats()
        }
        withContext(Dispatchers.Main) {
            statsInfo.text = "БД: ${stats.totalRecords} (${stats.lotteryRecords} тиражей, ${stats.generatedRecords} сгенерировано)"
        }
    }

    // ───────────────────────────────────────────────
    //  EXPORT
    // ───────────────────────────────────────────────

    private fun onExportClick() {
        exportButton.isEnabled = false
        statsInfo.text = "Экспорт..."

        lifecycleScope.launch {
            try {
                val result = exporter.exportAll()

                if (result.success) {
                    val fileList = result.filesWritten.joinToString("\n")
                    Toast.makeText(
                        this@MainActivity,
                        "✅ Экспорт OK\n${result.rowsExported} строк\nПапка: Downloads",
                        Toast.LENGTH_LONG
                    ).show()
                    statsInfo.text = "Экспорт: ${result.rowsExported} строк → Downloads"

                    // Обновляем стату БД
                    updateStats()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "❌ Ошибка: ${result.errorMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                    statsInfo.text = "Ошибка экспорта"
                }

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                statsInfo.text = "Ошибка экспорта"
                e.printStackTrace()
            } finally {
                exportButton.isEnabled = true
            }
        }
    }

    // ===== Lifecycle =====

    override fun onPause() {
        super.onPause()
        // Останавливаем датчики
        sensorManager.unregisterListener(this)

        // Останавливаем и освобождаем микрофон
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // НЕ закрываем камеру и handlerThread здесь!
        // Они нужны для работы после onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Закрываем камеру и освобождаем ресурсы только при уничтожении
        try {
            imageReader?.close()
            imageReader = null
            cameraDevice?.close()
            cameraDevice = null
            handlerThread.quitSafely()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun toggleGenerationMode() {
        if (!currentLotteryFormat.storesHistory) {
            // Произвольный формат: нет истории тиражей → нет "Весов" и нет KP+Space
            Toast.makeText(this, "Для произвольного формата доступна только 'Энтропия' — нет исторических данных для весов.", Toast.LENGTH_LONG).show()
            return
        }
        currentGenerationMode = when (currentGenerationMode) {
            WeightedGenerator.GenerationMode.PURE_ENTROPY -> {
                isKpSpatialMode = false
                WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY
            }
            WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY -> {
                isKpSpatialMode = true
                WeightedGenerator.GenerationMode.KP_SPATIAL
            }
            WeightedGenerator.GenerationMode.KP_SPATIAL -> {
                isKpSpatialMode = false
                WeightedGenerator.GenerationMode.PURE_ENTROPY
            }
        }
        updateModeButtonText()

        // Показываем информацию о режиме
        val modeInfo = when (currentGenerationMode) {
            WeightedGenerator.GenerationMode.PURE_ENTROPY ->
                "🎲 Чистая энтропия\nОригинальный режим"
            WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY ->
                "⚖️ Взвешенная энтропия\nС учётом частот"
            WeightedGenerator.GenerationMode.KP_SPATIAL ->
                "🌟 Kp + Пространство\nМагнитная активность + визуальные паттерны"
        }

        Toast.makeText(this, modeInfo, Toast.LENGTH_LONG).show()
    }
    private fun updateModeButtonText() {
        val modeButton = findViewById<Button>(R.id.modeToggleButton)
        val buttonText = when (currentGenerationMode) {
            WeightedGenerator.GenerationMode.PURE_ENTROPY -> "🎲 Энтропия"
            WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY -> "⚖️ Веса"
            WeightedGenerator.GenerationMode.KP_SPATIAL -> "🌟 Kp+Space"
        }
        modeButton.text = buttonText

        // Меняем цвет кнопки в зависимости от режима
        val colorRes = when (currentGenerationMode) {
            WeightedGenerator.GenerationMode.PURE_ENTROPY -> android.R.color.holo_blue_bright
            WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY -> android.R.color.holo_orange_light
            WeightedGenerator.GenerationMode.KP_SPATIAL -> android.R.color.holo_purple
        }
        modeButton.setBackgroundColor(ContextCompat.getColor(this, colorRes))
    }
    /**
     * Показать подробную информацию о режимах генерации
     */
    fun showModeInfo(view: android.view.View) {
        val modeDetails = when (currentGenerationMode) {
            WeightedGenerator.GenerationMode.PURE_ENTROPY ->
                "🎲 ЧИСТАЯ ЭНТРОПИЯ\n\n" +
                        "• Использует датчики телефона\n" +
                        "• Магнитометр + акселерометр\n" +
                        "• Полностью случайная генерация\n" +
                        "• Оригинальный алгоритм"

            WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY ->
                "⚖️ ВЗВЕШЕННАЯ ЭНТРОПИЯ\n\n" +
                        "• Учитывает статистику лотереи\n" +
                        "• Частые числа имеют больший вес\n" +
                        "• Основано на анализе 8000+ тиражей\n" +
                        "• Топ числа: 14, 12, 20, 17, 18"

            WeightedGenerator.GenerationMode.KP_SPATIAL ->
                "🌟 Kp + ПРОСТРАНСТВЕННАЯ ГАРМОНИЯ\n\n" +
                        "• Учитывает магнитную активность Земли\n" +
                        "• Kp-индекс влияет на частоты чисел\n" +
                        "• Избегает визуальных кластеров\n" +
                        "• Оптимальное размещение на билете\n\n" +
                        "Уровни Kp:\n" +
                        "• НИЗКИЙ (≤1.5): 1, 3, 8, 18, 12\n" +
                        "• СРЕДНИЙ (1.5-3): 13, 8, 9, 10, 11\n" +
                        "• ВЫСОКИЙ (3-4.5): 5, 16, 9, 20, 1\n" +
                        "• БУРЯ (>4.5): 5, 17, 7, 20, 16"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Режимы генерации")
            .setMessage(modeDetails)
            .setPositiveButton("Понятно") { _, _ -> }
            .setNeutralButton("Переключить") { _, _ -> toggleGenerationMode() }
            .show()
    }

    /**
     * Получение текущего значения Kp (для использования в генераторе)
     */
    private fun getCurrentKpValue(): Float {
        // Только реально полученное значение; 2.0f тут — нейтральный дефолт для
        // косметического описания режима, пока Kp вообще ни разу не запрашивался.
        // Для самой генерации (KP+Space) отдельная проверка не даёт стартовать без lastRealKp.
        return lastRealKp ?: 2.0f
    }

    /**
     * Обновление описания режима в UI
     */
    private fun updateModeDescription() {
        val modeDescription = findViewById<TextView>(R.id.modeDescription)
        if (modeDescription == null) {
            // Если TextView не найден, просто выходим
            return
        }

        val currentKp = getCurrentKpValue()

        val description = when (currentGenerationMode) {
            WeightedGenerator.GenerationMode.PURE_ENTROPY ->
                "🎲 Режим: Чистая энтропия (датчики телефона)"

            WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY ->
                "⚖️ Режим: Взвешенная энтропия (статистика лотереи)"

            WeightedGenerator.GenerationMode.KP_SPATIAL -> {
                val kpLevel = when {
                    currentKp > 4.0f -> "БУРЯ ⚡"
                    currentKp > 3.0f -> "ВЫСОКИЙ 🔸"
                    currentKp > 1.5f -> "СРЕДНИЙ 🔹"
                    else -> "НИЗКИЙ 🔷"
                }
                "🌟 Режим: Kp-Spatial (Kp=$currentKp $kpLevel + визуал)"
            }
        }

        modeDescription.text = description
    }

}

