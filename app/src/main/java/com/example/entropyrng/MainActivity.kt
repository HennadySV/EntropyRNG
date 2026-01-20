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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

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
    private lateinit var importer: LotteryDataImporter
    private lateinit var kpManager: KpIndexManager

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
                    Toast.makeText(this@MainActivity, "Импорт...", Toast.LENGTH_SHORT).show()

                    // Проверяем текущее количество записей
                    val currentCount = withContext(Dispatchers.IO) {
                        db.numberDataDao().getCountBySource("imported")
                    }

                    if (currentCount > 0) {
                        // Показываем диалог подтверждения
                        val shouldProceed = withContext(Dispatchers.Main) {
                            showImportConfirmationDialog(currentCount)
                        }

                        if (!shouldProceed) {
                            // Пользователь отменил
                            Toast.makeText(
                                this@MainActivity,
                                "Импорт отменён",
                                Toast.LENGTH_SHORT
                            ).show()
                            importButton.isEnabled = true
                            return@launch
                        }
                    }

                    val result = importer.importFromCsv(it)

                    if (result.success) {
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

    private suspend fun showImportConfirmationDialog(currentCount: Int): Boolean {
        return withContext(Dispatchers.Main) {
            var result = false
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Повторный импорт")
                .setMessage("В базе уже есть $currentCount импортированных записей.\n\nЭто создаст дубликаты. Продолжить?")
                .setPositiveButton("Да") { _, _ -> result = true }
                .setNegativeButton("Отмена") { _, _ -> result = false }
                .setCancelable(false)
                .create()

            dialog.show()

            // Ждём пока диалог закроется
            while (dialog.isShowing) {
                kotlinx.coroutines.delay(100)
            }

            result
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

        // Переключатель режима
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

        modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && currentWeights == null) {
                Toast.makeText(
                    this,
                    "Сначала проведите анализ!",
                    Toast.LENGTH_SHORT
                ).show()
                modeSwitch.isChecked = false
            }
        }

        // Изначально анализ недоступен
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
        val min = editMin.text.toString().toIntOrNull() ?: 1
        val max = editMax.text.toString().toIntOrNull() ?: 100
        val count = editCount.text.toString().toIntOrNull() ?: 1
        val validCount = count.coerceIn(1, 10)
        val validMax = max.coerceIn(min, 100)

        generateButton.isEnabled = false
        outputText.text = "Сбор энтропии и генерация..."

        lifecycleScope.launch {
            try {
                // Получить Kp индекс и сохранить в БД
                solarInfo.text = "Kp: загрузка..."
                val kpResult = kpManager.fetchAndSaveCurrentKp()

                val kp = when (kpResult) {
                    is KpResult.Success -> {
                        solarInfo.text = "Kp: ${kpResult.value} (сохранено)"
                        kpResult.value
                    }
                    is KpResult.Error -> {
                        solarInfo.text = "Kp: ошибка (${kpResult.message})"
                        0f // Fallback
                    }
                }

                // Собрать энтропию
                val entropyBytes = collectEntropy()

                // Определяем режим генерации
                val mode = if (modeSwitch.isChecked && currentWeights != null) {
                    WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY
                } else {
                    WeightedGenerator.GenerationMode.PURE_ENTROPY
                }

                // Проверяем: генерируем 2 поля по 4 числа (лотерея Премьер) или обычный режим?
                val isPremierMode = (min == 1 && validMax == 20 && validCount == 8)

                val numbers = if (isPremierMode) {
                    // РЕЖИМ ПРЕМЬЕР: генерируем 2 поля по 4 числа с вариативностью
                    val weights = if (mode == WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY) {
                        analyzer.calculateKpAdjustedWeights(min, validMax, kp)
                    } else null

                    val (field1, field2) = generator.generateTwoFieldsWithVariability(
                        entropyBytes, kp, weights, mode
                    )

                    field1 + field2  // Объединяем оба поля
                } else {
                    // ОБЫЧНЫЙ РЕЖИМ: генерируем как раньше
                    if (mode == WeightedGenerator.GenerationMode.WEIGHTED_ENTROPY) {
                        val kpAdjustedWeights = analyzer.calculateKpAdjustedWeights(
                            min, validMax, kp
                        )

                        generator.generateWeightedEntropy(
                            validCount, min, validMax,
                            entropyBytes, kp,
                            kpAdjustedWeights
                        )
                    } else {
                        generator.generatePureEntropy(
                            validCount, min, validMax, entropyBytes, kp
                        )
                    }
                }

                // Сохранить в БД
                saveGenerated(numbers, min, validMax, kp)

                // Показать результат с красивым форматированием для Премьер
                val modeStr = if (modeSwitch.isChecked) "Калиброванный" else "Чистая энтропия"

                val outputStr = if (isPremierMode) {
                    // Красивое отображение для 2 полей
                    val field1 = numbers.take(4)
                    val field2 = numbers.drop(4)
                    val spread1 = field1.max() - field1.min()
                    val spread2 = field2.max() - field2.min()
                    val spreadDiff = kotlin.math.abs(spread1 - spread2)

                    """
                    [$modeStr] Kp: $kp
                    
                    Поле 1: ${field1.joinToString(", ")}
                    spread: $spread1
                    
                    Поле 2: ${field2.joinToString(", ")}
                    spread: $spread2
                    
                    Δ spread: $spreadDiff
                    """.trimIndent()
                } else {
                    // Обычное отображение
                    "[$modeStr]\nKp: $kp\n${numbers.joinToString(", ")}"
                }

                outputText.text = outputStr

            } catch (e: Exception) {
                outputText.text = "Ошибка: ${e.message}"
                solarInfo.text = "Ошибка генерации"
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            } finally {
                // ВАЖНО: Всегда разблокируем кнопку
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
}
