package com.example.magnetometerwifisignal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var wifiManager: WifiManager

    // UI
    private lateinit var cardStatus: LinearLayout
    private lateinit var tvMainStatus: TextView
    private lateinit var tvSubStatus: TextView
    private lateinit var tvMag: TextView
    private lateinit var tvWifi: TextView
    private lateinit var tvLight: TextView
    private lateinit var tvSonar: TextView
    private lateinit var tvTags: TextView
    private lateinit var graphView: SimpleGraphView
    private lateinit var swPersonMode: Switch

    private val fusionEngine = FusionEngine()
    private var activeSonar: ActiveSonar? = null

    private var isLogging = false
    private val calibrationSamples = 50
    private var sampleCount = 0
    private var lastUiUpdate = 0L

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
        tvSonar = findViewById(R.id.tvSonar)
        tvTags = findViewById(R.id.tvLog)
        graphView = findViewById(R.id.graphView)
        swPersonMode = findViewById(R.id.swPersonMode)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Setup Sonar (Now with Doppler)
        activeSonar = ActiveSonar { result ->
            // Pass full result to engine
            fusionEngine.smoothSonar = result.amplitude
            fusionEngine.dopplerDir = result.dopplerShift

            runOnUiThread {
                var text = "Amp: ${result.amplitude.toInt()}"
                if (result.dopplerShift == 1) text += " [Near]"
                if (result.dopplerShift == -1) text += " [Far]"
                tvSonar.text = text
            }
        }

        // Setup Switch
        swPersonMode.setOnCheckedChangeListener { _, isChecked ->
            fusionEngine.isPersonModeEnabled = isChecked
            tvTags.text = "> Mode changed: Person Detect = $isChecked"
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startFusion() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopFusion() }
    }

    private fun startFusion() {
        if (isLogging) return
        if (!checkPermissions()) return

        isLogging = true
        sampleCount = 0

        val delay = SensorManager.SENSOR_DELAY_GAME
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sensorManager.registerListener(this, it, delay) }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensorManager.registerListener(this, it, delay) }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensorManager.registerListener(this, it, delay) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        startWifiPoll()
        activeSonar?.start()

        setStatus("#FFC107", "CALIBRATING", "Hold still...")
    }

    private fun stopFusion() {
        isLogging = false
        sensorManager.unregisterListener(this)
        activeSonar?.stop()
        wifiHandler.removeCallbacksAndMessages(null)
        setStatus("#9E9E9E", "IDLE", "System Stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isLogging || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val mag = sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2])
                val smoothMag = (fusionEngine.smoothMag * 0.9f) + (mag * 0.1f)
                fusionEngine.smoothMag = smoothMag
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
        }

        if (sampleCount < calibrationSamples) {
            sampleCount++
            fusionEngine.calibrate(sampleCount, calibrationSamples)
            if (sampleCount == calibrationSamples) setStatus("#4CAF50", "SCANNING", "Baselines Locked")
        } else {
            val result = fusionEngine.analyze()
            setStatus(result.colorHex, result.mainStatus, result.subStatus)
            runOnUiThread { tvTags.text = "INFO: ${result.debugTags}" }
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

    private fun checkPermissions(): Boolean {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 101)
            return false
        }
        return true
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}