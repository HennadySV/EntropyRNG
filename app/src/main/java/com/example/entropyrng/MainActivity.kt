package com.example.entropyrng

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var magnetometer: Sensor
    private var audioRecord: AudioRecord? = null
    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraManager: CameraManager
    private var imageReader: ImageReader? = null
    private val entropyBuffer = ArrayBlockingQueue<ByteArray>(100)
    private lateinit var handler: Handler
    private lateinit var handlerThread: HandlerThread

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)!!

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        handlerThread = HandlerThread("CameraThread").apply { start() }
        handler = Handler(handlerThread.looper)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 1)
        } else {
            startCollectors()
        }

        findViewById<Button>(R.id.generateButton).setOnClickListener {
            val min = findViewById<EditText>(R.id.editMin).text.toString().toIntOrNull() ?: 1
            val max = findViewById<EditText>(R.id.editMax).text.toString().toIntOrNull() ?: 100
            val count = findViewById<EditText>(R.id.editCount).text.toString().toIntOrNull() ?: 1
            val validCount = count.coerceIn(1, 10)
            val validMax = max.coerceIn(min, 100)

            lifecycleScope.launch {
                findViewById<TextView>(R.id.outputText).text = "Загрузка Kp и генерация..."
                val kp = fetchKpIndex()
                val numbers = generateRandomNumbers(validCount, min, validMax, kp)
                findViewById<TextView>(R.id.outputText).text = "Kp: $kp\n${numbers.joinToString(", ")}"
            }
        }
    }

    private suspend fun fetchKpIndex(): Float = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json")
                .header("User-Agent", "EntropyRNG App (your@email.com)") // Рекомендуется NOAA
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
            // Ошибка сети — возвращаем 0
        }
        0f
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCollectors()
        }
    }

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
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
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

    private fun generateRandomNumbers(count: Int, min: Int, max: Int, kp: Float): List<Int> {
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
            throw IllegalStateException("Not enough entropy")
        }

        val debaised = ByteArray(rawEntropy.size / 2)
        for (i in debaised.indices) {
            debaised[i] = (rawEntropy[2 * i].toInt() xor rawEntropy[2 * i + 1].toInt()).toByte()
        }

        val sha256 = MessageDigest.getInstance("SHA-256")
        sha256.update(System.currentTimeMillis().toString().toByteArray())
        sha256.update(kp.toString().toByteArray())
        val hash = sha256.digest(debaised)

        val range = max - min + 1
        if (count > range) {
            throw IllegalArgumentException("Cannot generate $count unique numbers")
        }

        val uniqueNumbers = mutableSetOf<Int>()
        var hashIndex = 0
        while (uniqueNumbers.size < count) {
            val number = min + ((hash[hashIndex % hash.size].toInt() and 0xFF) % range)
            uniqueNumbers.add(number)
            hashIndex++
            if (hashIndex > hash.size * 100) {
                throw IllegalStateException("Not enough entropy for unique")
            }
        }
        return uniqueNumbers.toList()
    }

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