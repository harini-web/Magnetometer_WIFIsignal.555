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

// --- FUSION LOGIC CLASSES ---
data class FusionResult(
    val mainStatus: String,
    val subStatus: String,
    val colorHex: String,
    val debugTags: String,
    val vibrationLevel: Int
)

class FusionEngine {
    private val HISTORY_SIZE = 20
    private val THRESHOLD_STATIONARY = 0.05f

    var baseMag = 0f; var baseWifi = 0f
    var noiseWifi = 1.0f

    var smoothMag = 0f; var smoothWifi = 0f; var smoothLight = 0f
    var acHumScore = 0f; var linearMotion = 0f; var angularMotion = 0f
    var visibleNetworkCount = 0
    var isSentryModeEnabled = false

    private val wifiHistory = FloatArray(HISTORY_SIZE)
    private val lightHistory = FloatArray(HISTORY_SIZE)
    private var historyIndex = 0

    fun updateData(mag: Float, wifi: Float, light: Float, acHum: Float, linear: Float, angular: Float, netCount: Int) {
        smoothMag = mag; smoothWifi = wifi; smoothLight = light
        acHumScore = acHum; linearMotion = linear; angularMotion = angular
        visibleNetworkCount = netCount
        historyIndex = (historyIndex + 1) % HISTORY_SIZE
        wifiHistory[historyIndex] = smoothWifi
        lightHistory[historyIndex] = smoothLight
    }

    fun calibrate(samples: Int) {
        if (samples == 1) { baseMag = smoothMag; baseWifi = smoothWifi }
        else {
            baseMag = (baseMag * (samples - 1) + smoothMag) / samples
            baseWifi = (baseWifi * (samples - 1) + smoothWifi) / samples
        }
        if (samples > 10) {
            val wVar = calculateStdDev(wifiHistory)
            if (wVar > 0) noiseWifi = (noiseWifi * 0.9f + wVar * 0.1f)
        }
    }

    fun analyze(): FusionResult {
        val tags = mutableListOf<String>()
        val isStationary = linearMotion < THRESHOLD_STATIONARY && angularMotion < THRESHOLD_STATIONARY

        val magDiff = abs(smoothMag - baseMag)
        val wifiVar = calculateStdDev(wifiHistory)
        val lightVar = calculateStdDev(lightHistory)
        val wifiDrop = baseWifi - smoothWifi

        // Crowd Density Labeling
        var densityScore = 0
        if (visibleNetworkCount > 5) densityScore++
        if (visibleNetworkCount > 15) densityScore++
        if (visibleNetworkCount > 30) densityScore++
        if (isStationary && wifiVar > 2.0f) densityScore++

        val densityLabel = when (densityScore) {
            0, 1 -> "Low"
            2, 3 -> "Moderate"
            else -> "Congested"
        }
        tags.add("APs:$visibleNetworkCount")

        // 1. SENTRY MODE (Intrusion Detection)
        if (isSentryModeEnabled) {
            if (!isStationary) return FusionResult("SENTRY ALERT", "Device Moving - Put Down", "#FF9800", "Stabilize Device", 0)

            // Trigger: WiFi Scatter OR Light Shadows
            if ((wifiVar > noiseWifi * 3.0f && wifiVar > 1.5f) || lightVar > 4.0f) {
                return FusionResult("INTRUSION DETECTED", "Motion (Shadows/Signal)", "#E040FB", "Var:${"%.1f".format(wifiVar)}", 255)
            }
            // Trigger: Signal Blocked
            if (wifiDrop > 5.0f && wifiVar < 2.0f) {
                return FusionResult("INTRUSION DETECTED", "Signal Line Blocked", "#AA00FF", "Drop: ${"%.1f".format(wifiDrop)}dB", 100)
            }
        }

        // 2. SWEEP MODE (Counter-Surveillance)
        // Live Wire (AC Hum)
        if (magDiff > 3.0f && acHumScore > 1.0f) {
            val intensity = (magDiff * 5).toInt().coerceIn(50, 255)
            return FusionResult("THREAT DETECTED", "Live Electronics (AC Hum)", "#D32F2F", "Jitter: ${"%.1f".format(acHumScore)}", intensity)
        }
        // Ferrous Metal
        if (magDiff > 15.0f) {
            val intensity = (magDiff * 2).toInt().coerceIn(30, 150)
            return FusionResult("MAGNETIC SOURCE", "Ferrous Metal Detected", "#F44336", "Mag++", intensity)
        }

        return FusionResult("SECURE", "Density: $densityLabel", "#2E7D32", if(isSentryModeEnabled) "Sentry Active" else "Scanning...", 0)
    }

    private fun calculateStdDev(data: FloatArray): Float {
        val mean = data.average().toFloat()
        var sumSq = 0.0f
        var count = 0
        for (f in data) { if (f != 0f) { sumSq += (f - mean).pow(2); count++ } }
        return if (count > 0) sqrt(sumSq / count) else 0f
    }
}

// --- MAIN ACTIVITY ---

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var wifiManager: WifiManager
    private lateinit var vibrator: Vibrator

    private lateinit var cardStatus: LinearLayout
    private lateinit var tvMainStatus: TextView
    private lateinit var tvSubStatus: TextView
    private lateinit var tvMag: TextView
    private lateinit var tvWifi: TextView
    private lateinit var tvLight: TextView
    private lateinit var tvHum: TextView
    private lateinit var tvLog: TextView
    private lateinit var graphView: SimpleGraphView
    private lateinit var swSentryMode: Switch

    private val fusionEngine = FusionEngine()
    private var isLogging = false
    private val calibrationSamples = 50
    private var sampleCount = 0
    private var lastUiUpdate = 0L

    // AC Hum Calc
    private val magHistory = FloatArray(10)
    private var magIndex = 0

    // WiFi
    private var currentWifiCount = 0
    private var isReceiverRegistered = false

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

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        swSentryMode.setOnCheckedChangeListener { _, isChecked ->
            fusionEngine.isSentryModeEnabled = isChecked
            tvLog.text = "> Mode switched: ${if(isChecked) "SENTRY (Intrusion)" else "SWEEP (Counter-Surveillance)"}"
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startFusion() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopFusion() }
    }

    private fun startFusion() {
        if (isLogging) return
        isLogging = true
        sampleCount = 0
        magIndex = 0

        val delay = SensorManager.SENSOR_DELAY_GAME
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sensorManager.registerListener(this, it, delay) }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensorManager.registerListener(this, it, delay) }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensorManager.registerListener(this, it, delay) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        startWifiPoll()
        registerWifiReceiver()
        setStatus("#FFC107", "CALIBRATING", "Hold still...")
    }

    private fun stopFusion() {
        isLogging = false
        sensorManager.unregisterListener(this)
        wifiHandler.removeCallbacksAndMessages(null)
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
        if (now - lastUiUpdate > 100) {
            lastUiUpdate = now
            graphView.addPoint(fusionEngine.smoothMag)
            tvMag.text = "%.1f µT".format(fusionEngine.smoothMag)
            tvHum.text = "%.2f".format(fusionEngine.acHumScore)
        }

        if (sampleCount < calibrationSamples) {
            sampleCount++
            fusionEngine.calibrate(sampleCount)
            if (sampleCount == calibrationSamples) setStatus("#2E7D32", "SECURE", "Environment Learned")
        } else {
            val result = fusionEngine.analyze()
            setStatus(result.colorHex, result.mainStatus, result.subStatus)

            if (now - lastUiUpdate < 150) {
                tvLog.text = "> DEBUG: ${result.debugTags}"
            }
            if (result.vibrationLevel > 0) playHaptic(result.vibrationLevel)
        }
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