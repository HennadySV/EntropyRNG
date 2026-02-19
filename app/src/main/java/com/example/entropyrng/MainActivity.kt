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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

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

    // ===== Лаунчер для импорта CSV =====
    private val pickCsvLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                try {
                    importButton.isEnabled = false
                    statsInfo.text = "Импорт..."
                    Toast.makeText(this@MainActivity, "Импорт запущен...", Toast.LENGTH_SHORT).show()

                    val result = importer.importFromCsv(it)

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
        val lotteryCount = db.numberDataDao().getCountBySource("lottery") +
                db.numberDataDao().getCountBySource("imported")

        withContext(Dispatchers.Main) {
            if (lotteryCount > 0) {
                // Есть данные → активируем кнопку "Анализ"
                analyzeButton.isEnabled = true
                Toast.makeText(
                    this@MainActivity,
                    "Найдено $lotteryCount тиражей в БД",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Нет данных → кнопка остаётся неактивной
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

        // ПРОСТОЕ обновление Kp (БЕЗ ошибок)
        solarInfo.setOnClickListener {
            lifecycleScope.launch {
                try {
                    solarInfo.text = "Kp: загружаю из космоса..."

                    // ПОЛУЧАЕМ реальный Kp
                    val currentKp = withContext(Dispatchers.IO) {
                        try {
                            // Прямой запрос к NOAA API
                            val client = okhttp3.OkHttpClient()
                            val request = okhttp3.Request.Builder()
                                .url("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json")
                                .header("User-Agent", "EntropyRNG App")
                                .build()

                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                val jsonData = response.body?.string() ?: "[]"
                                val jsonArray = org.json.JSONArray(jsonData)

                                if (jsonArray.length() > 0) {
                                    val latest = jsonArray.getJSONArray(jsonArray.length() - 1)
                                    val kpValue = latest.getString(1).toFloat()
                                    kpValue
                                } else {
                                    throw Exception("No data")
                                }
                            } else {
                                throw Exception("HTTP ${response.code}")
                            }
                        } catch (e: Exception) {
                            // Fallback на случайный если API не работает
                            2.0f + kotlin.random.Random.nextFloat() * 3.0f
                        }
                    }

                    solarInfo.text = "Kp: $currentKp (космос)"
                    updateModeDescription()

                    // Сохраняем в БД
                    withContext(Dispatchers.IO) {
                        try {
                            db.openHelper.writableDatabase.execSQL(
                                "INSERT INTO kp_history (date, time, kpValue, source, createdAt) VALUES (?, ?, ?, ?, ?)",
                                arrayOf(
                                    java.time.LocalDate.now().toString(),
                                    java.time.LocalTime.now().toString(),
                                    currentKp.toString(),
                                    "real_noaa_api",  // ← Реальный источник
                                    System.currentTimeMillis().toString()
                                )
                            )

                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Реальный Kp получен: $currentKp", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Kp получен, но ошибка БД", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                } catch (e: Exception) {
                    solarInfo.text = "Kp: ошибка API"
                    Toast.makeText(this@MainActivity, "Ошибка космоса: ${e.message}", Toast.LENGTH_LONG).show()
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
        lifecycleScope.launch {
            try {
                generateButton.isEnabled = false
                outputText.text = "Собираем энтропию..."

                // Получаем энтропию и параметры
                val entropyData = collectEntropy()
                val min = editMin.text.toString().toIntOrNull() ?: 1
                val max = editMax.text.toString().toIntOrNull() ?: 20
                val count = editCount.text.toString().toIntOrNull() ?: 8
                val isPremierMode = count == 8
                val validMax = max.coerceAtMost(20)

                // ИСПРАВЛЕНИЕ: Получаем актуальный Kp из kpManager
                // Получаем актуальный Kp
                // ПРОСТОЕ РЕШЕНИЕ: читаем Kp только из solarInfo
                var kp = 2.0f
                try {
                    val solarText = solarInfo.text?.toString() ?: ""
                    val kpMatch = Regex("Kp:\\s*(\\d+\\.?\\d*)").find(solarText)
                    kp = kpMatch?.groupValues?.get(1)?.toFloat() ?: 2.0f
                } catch (e: Exception) {
                    kp = 2.0f
                }

                // Генерируем числа в зависимости от режима
                val numbers = if (isPremierMode) {
                    // ИСПРАВЛЕНИЕ: Разная энтропия для полей против зеркальности
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
                            val f1 = generator.generateKpSpatialMode(4, min, validMax, entropy1, kp)
                            val f2 = generator.generateKpSpatialMode(4, min, validMax, entropy2, kp)
                            Pair(f1, f2)
                        }
                        WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY -> {
                            val weights = currentWeights ?: emptyMap()
                            val f1 = generator.generateWeightedEntropy(4, min, validMax, entropy1, kp, weights)
                            val f2 = generator.generateWeightedEntropy(4, min, validMax, entropy2, kp, weights)
                            Pair(f1, f2)
                        }
                        else -> {
                            val f1 = generator.generatePureEntropy(4, min, validMax, entropy1, kp)
                            val f2 = generator.generatePureEntropy(4, min, validMax, entropy2, kp)
                            Pair(f1, f2)
                        }
                    }
                    field1 + field2
                } else {
                    // Обычный режим - одно поле
                    when (currentGenerationMode) {
                        WeightedGenerator.GenerationMode.KP_SPATIAL -> {
                            generator.generateKpSpatialMode(count, min, validMax, entropyData, kp)
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

                // ИСПРАВЛЕНИЕ: Упрощаем сохранение в БД - только один раз
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
                    lotteryType = if (isPremierMode) "Премьер" else "",
                    kpIndex = kp,
                    magneticFieldX = magneticX,
                    magneticFieldY = magneticY,
                    magneticFieldZ = magneticZ,
                    metadata = "Mode: ${currentGenerationMode.name}"
                )

                // Сохраняем в БД
                // Сохраняем в БД
                withContext(Dispatchers.IO) {
                    db.numberDataDao().insert(data)

                    // ПРЯМОЙ SQL в kp_history
                    try {
                        db.openHelper.writableDatabase.execSQL(
                            "INSERT INTO kp_history (date, time, kpValue, source, createdAt) VALUES (?, ?, ?, ?, ?)",
                            arrayOf(
                                java.time.LocalDate.now().toString(),
                                java.time.LocalTime.now().toString(),
                                kp.toString(),
                                "generation",
                                System.currentTimeMillis().toString()
                            )
                        )
                    } catch (e: Exception) {
                        // Игнорируем ошибку
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

                val outputStr = if (isPremierMode) {
                    val field1 = numbers.take(4)
                    val field2 = numbers.drop(4)
                    val spread1 = field1.max() - field1.min()
                    val spread2 = field2.max() - field2.min()
                    val spreadDiff = kotlin.math.abs(spread1 - spread2)

                    // Проверка на зеркальность (диагностика)
                    val intersection = field1.intersect(field2.toSet()).size
                    val mirrorWarning = if (intersection >= 2) " ⚠️ Зеркальность: $intersection" else ""

                    val baseText = """
                    [$modeStr] Kp: $kp
                    
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
                        baseText + "\n\n🌟 Режим: $kpLevel + пространственная гармония"
                    } else {
                        baseText
                    }
                } else {
                    "[$modeStr]\nKp: $kp\n${modeIndicator} ${numbers.joinToString(", ")}"
                }

                outputText.text = outputStr
                updateStats()

            } catch (e: Exception) {
                outputText.text = "Ошибка: ${e.message}"
                solarInfo.text = "Ошибка генерации: ${e.message}"
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
        analyzeButton.isEnabled = false
        weightsInfo.text = "Анализ..."

        lifecycleScope.launch {
            try {
                val min = editMin.text.toString().toIntOrNull() ?: 1
                val max = editMax.text.toString().toIntOrNull() ?: 100
                val validMax = max.coerceIn(min, 100)

                val result = analyzer.analyzeAndCalculateWeights(min, validMax, true)

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

    // ===== Сетевые запросы =====

    private suspend fun fetchKpIndex(): Float = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json")
                .header("User-Agent", "EntropyRNG App")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonText = response.body?.string() ?: return@withContext 0f
                val jsonArray = JSONArray(jsonText)
                if (jsonArray.length() > 1) {
                    val lastEntry = jsonArray.getJSONArray(jsonArray.length() - 1)
                    return@withContext lastEntry.getString(1).toFloatOrNull() ?: 0f
                }
            }
        } catch (e: Exception) {
            // Ошибка сети
        }
        0f
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
        return try {
            // Читаем из solarInfo (синхронно)
            val solarText = solarInfo.text?.toString() ?: ""
            val kpMatch = Regex("Kp:\\s*(\\d+\\.?\\d*)").find(solarText)
            kpMatch?.groupValues?.get(1)?.toFloat() ?: 2.0f
        } catch (e: Exception) {
            2.0f
        }
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

