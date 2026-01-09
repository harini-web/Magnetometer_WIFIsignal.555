package com.example.magnetometerwifisignal

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

// --- MAIN ACTIVITY ---

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var wifiManager: WifiManager
    private lateinit var vibrator: Vibrator

    // UI Elements
    private lateinit var cardStatus: LinearLayout
    private lateinit var tvMainStatus: TextView
    private lateinit var tvSubStatus: TextView
    private lateinit var tvMag: TextView
    private lateinit var tvWifi: TextView
    private lateinit var tvLight: TextView
    private lateinit var tvHum: TextView
    private lateinit var tvLog: TextView // Shows 30s Summary History
    private lateinit var graphView: SimpleGraphView

    private lateinit var scrollLog: android.widget.ScrollView
    private lateinit var swSentryMode: Switch

    // Logic Engine
    private val fusionEngine = FusionEngine()

    // State
    private var isLogging = false
    private val calibrationSamples = 50
    private var sampleCount = 0
    private var lastUiUpdate = 0L

    // 30-Second Aggregation Buffer
    private val resultBuffer = mutableListOf<FusionResult>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // AC Hum Calculation (Jitter)
    private val magHistory = FloatArray(10)
    private var magIndex = 0

    // WiFi Scanning State
    private var currentWifiCount = 0
    private var isReceiverRegistered = false

    // 30s Timer Runnable
    private val summaryRunnable = object : Runnable {
        override fun run() {
            if (isLogging) {
                processSummaryPeriod()
                // Re-run in 30 seconds
                mainHandler.postDelayed(this, 30000)
            }
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isLogging) return
            val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (success) {
                try {
                    if (ContextCompat.checkSelfPermission(context!!, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        currentWifiCount = wifiManager.scanResults.size
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind UI
        cardStatus = findViewById(R.id.cardStatus)
        tvMainStatus = findViewById(R.id.tvMainStatus)
        tvSubStatus = findViewById(R.id.tvSubStatus)
        tvMag = findViewById(R.id.tvMag)
        tvWifi = findViewById(R.id.tvWifi)
        tvLight = findViewById(R.id.tvLight)
        tvHum = findViewById(R.id.tvHum)
        tvLog = findViewById(R.id.tvLog)
        graphView = findViewById(R.id.graphView)
        swSentryMode = findViewById(R.id.swSentryMode)
        scrollLog = findViewById(R.id.scrollLog)

        // Init Hardware
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Toggle Listener
        swSentryMode.setOnCheckedChangeListener { _, isChecked ->
            fusionEngine.isSentryModeEnabled = isChecked
            LogRepository.addRealtime("Mode Switch: ${if(isChecked) "SENTRY" else "SWEEP"}", "#FFFFFF")
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startFusion() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopFusion() }

        // OPEN THREAT LOGS ACTIVITY
        findViewById<Button>(R.id.btnLogs).setOnClickListener {
            startActivity(Intent(this, ThreatLogsActivity::class.java))
        }
    }

    private fun startFusion() {
        if (isLogging) return
        isLogging = true
        sampleCount = 0
        magIndex = 0

        LogRepository.clear()
        resultBuffer.clear()

        val delay = SensorManager.SENSOR_DELAY_GAME
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sensorManager.registerListener(this, it, delay) }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensorManager.registerListener(this, it, delay) }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensorManager.registerListener(this, it, delay) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        startWifiPoll()
        registerWifiReceiver()

        // Start the 30s aggregation loop
        mainHandler.postDelayed(summaryRunnable, 30000)

        setStatus("#FFC107", "CALIBRATING", "Learning Environment...")
    }

    private fun stopFusion() {
        isLogging = false
        sensorManager.unregisterListener(this)
        wifiHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacks(summaryRunnable) // Stop 30s timer
        vibrator.cancel()
        if (isReceiverRegistered) {
            unregisterReceiver(wifiScanReceiver)
            isReceiverRegistered = false
        }
        setStatus("#9E9E9E", "SYSTEM IDLE", "Services Stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isLogging || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val mag = sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2])

                // AC Hum Jitter Calc
                magIndex = (magIndex + 1) % magHistory.size
                magHistory[magIndex] = mag
                val min = magHistory.minOrNull() ?: 0f
                val max = magHistory.maxOrNull() ?: 0f
                fusionEngine.acHumScore = max - min

                fusionEngine.smoothMag = (fusionEngine.smoothMag * 0.9f) + (mag * 0.1f)
                processFrame()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val acc = sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2])
                val linear = abs(acc - 9.81f)
                fusionEngine.linearMotion = (fusionEngine.linearMotion * 0.8f) + (linear * 0.2f)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val rot = sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2])
                fusionEngine.angularMotion = (fusionEngine.angularMotion * 0.8f) + (rot * 0.2f)
            }
            Sensor.TYPE_LIGHT -> {
                fusionEngine.smoothLight = event.values[0]
                tvLight.text = "%.0f lx".format(event.values[0])
            }
        }
    }

    private fun processFrame() {
        val now = System.currentTimeMillis()
        // 1. Fast Graph Update (10Hz)
        if (now - lastUiUpdate > 100) {
            lastUiUpdate = now
            graphView.addPoint(fusionEngine.smoothMag)
            tvMag.text = "%.1f µT".format(fusionEngine.smoothMag)
            tvHum.text = "%.2f".format(fusionEngine.acHumScore)
        }

        if (sampleCount < calibrationSamples) {
            sampleCount++
            fusionEngine.calibrate(sampleCount)
            if (sampleCount == calibrationSamples) setStatus("#2E7D32", "SECURE", "Baseline Locked")
        } else {
            val result = fusionEngine.analyze()

            // --- LOGGING LOGIC ---
            // 1. Add to Realtime Log (For ThreatLogsActivity)
            // We log everything that isn't just "Secure/Scanning" to capture transients
            if (result.mainStatus != "SECURE" && result.mainStatus != "SCANNING") {
                LogRepository.addRealtime("${result.mainStatus}: ${result.subStatus} [${result.debugTags}]", result.colorHex)
            } else if (now % 2000 < 50) {
                // Heartbeat log every ~2s if secure
                LogRepository.addRealtime("Scanning... (Env Safe)", "#4CAF50")
            }

            // 2. Add to 30s Buffer (For Dashboard)
            resultBuffer.add(result)

            // 3. Immediate Feedback (Haptics) - We still vibrate instantly!
            if (result.vibrationLevel > 0) playHaptic(result.vibrationLevel)
        }
    }

    // --- 30 SECOND AGGREGATION LOGIC ---
    private fun processSummaryPeriod() {
        if (resultBuffer.isEmpty()) return

        var maxPriority = 0
        var dominantResult = resultBuffer.last()

        for (res in resultBuffer) {
            val priority = when (res.mainStatus) {
                "INTRUSION DETECTED", "INTRUSION ALERT" -> 5
                "THREAT DETECTED" -> 4
                "MAGNETIC SOURCE" -> 3
                "SENTRY WARNING" -> 2
                else -> 1
            }
            if (priority >= maxPriority) {
                maxPriority = priority
                dominantResult = res
            }
        }

        setStatus(dominantResult.colorHex, dominantResult.mainStatus, dominantResult.subStatus)

        // Log Logic
        val summaryMsg = "${dominantResult.mainStatus}: ${dominantResult.subStatus}"
        LogRepository.addSummary(summaryMsg, dominantResult.colorHex)
        tvLog.text = LogRepository.getFormattedSummaryLog()

        // AUTO-SCROLL TO TOP (To see newest)
        scrollLog.post {
            scrollLog.fullScroll(android.view.View.FOCUS_UP)
        }

        resultBuffer.clear()
    }

    private fun playHaptic(intensity: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, intensity))
        } else {
            vibrator.vibrate(50)
        }
    }

    private fun registerWifiReceiver() {
        if (!isReceiverRegistered) {
            registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            isReceiverRegistered = true
            wifiManager.startScan()
        }
    }

    private val wifiHandler = Handler(Looper.getMainLooper())
    private fun startWifiPoll() {
        wifiHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isLogging) return
                val rssi = wifiManager.connectionInfo.rssi
                val smooth = (fusionEngine.smoothWifi * 0.8f) + (rssi * 0.2f)
                fusionEngine.smoothWifi = smooth
                tvWifi.text = "${smooth.toInt()} dBm"

                if (System.currentTimeMillis() % 10000 < 500) wifiManager.startScan()

                fusionEngine.updateData(
                    fusionEngine.smoothMag, fusionEngine.smoothWifi, fusionEngine.smoothLight,
                    fusionEngine.acHumScore, fusionEngine.linearMotion, fusionEngine.angularMotion,
                    currentWifiCount
                )
                wifiHandler.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun setStatus(colorHex: String, title: String, sub: String) {
        runOnUiThread {
            cardStatus.setBackgroundColor(Color.parseColor(colorHex))
            tvMainStatus.text = title
            tvSubStatus.text = sub
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

// --- FUSION LOGIC (Embed for simplicity) ---
// (Same FusionEngine class as provided previously, ensure it is present in the file)