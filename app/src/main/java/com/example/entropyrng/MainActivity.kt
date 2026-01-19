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
                importButton.isEnabled = false
                Toast.makeText(this@MainActivity, "Импорт...", Toast.LENGTH_SHORT).show()

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

                importButton.isEnabled = true
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

        // Обновить статистику БД
        lifecycleScope.launch {
            updateStats()
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

                // Генерация в зависимости от режима
                val numbers = if (modeSwitch.isChecked && currentWeights != null) {
                    // Режим с весами + учёт Kp
                    val kpAdjustedWeights = analyzer.calculateKpAdjustedWeights(
                        min, validMax, kp
                    )

                    generator.generateWeightedEntropy(
                        validCount, min, validMax,
                        entropyBytes, kp,
                        kpAdjustedWeights
                    )
                } else {
                    // Режим чистой энтропии
                    generator.generatePureEntropy(
                        validCount, min, validMax, entropyBytes, kp
                    )
                }

                // Сохранить в БД
                saveGenerated(numbers, min, validMax, kp)

                // Показать результат
                val mode = if (modeSwitch.isChecked) "Калиброванный" else "Чистая энтропия"
                outputText.text = "[$mode]\nKp: $kp\n${numbers.joinToString(", ")}"

            } catch (e: Exception) {
                outputText.text = "Ошибка: ${e.message}"
                Toast.makeText(this@MainActivity, "Ошибка генерации", Toast.LENGTH_SHORT).show()
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
            } finally {
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
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
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
            while (true) {
                audioRecord?.read(buffer, 0, bufferSize)
                entropyBuffer.offer(buffer.copyOf())
            }
        }
    }

    private fun startCamera() {
        val cameraId = "0"
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val previewSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(ImageFormat.YUV_420_888).first { it.width <= 320 && it.height <= 240 }

                imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val yPlane = image.planes[0]
                        val bytes = ByteArray(yPlane.buffer.remaining())
                        yPlane.buffer.get(bytes)
                        entropyBuffer.offer(bytes)
                        image.close()
                    }
                }, handler)

                val surfaces = listOf(imageReader!!.surface)

                camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        request.addTarget(imageReader!!.surface)
                        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        session.setRepeatingRequest(request.build(), null, handler)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, handler)
            }
            override fun onDisconnected(camera: CameraDevice) {}
            override fun onError(camera: CameraDevice, error: Int) {}
        }, handler)
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
        val stats = analyzer.getDatabaseStats()
        statsInfo.text = "БД: ${stats.totalRecords} (${stats.lotteryRecords} тиражей, ${stats.generatedRecords} сгенерировано)"
    }

    // ===== Lifecycle =====

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        audioRecord?.stop()
        audioRecord?.release()
        imageReader?.close()
        cameraDevice?.close()
        handlerThread.quitSafely()
    }
}
