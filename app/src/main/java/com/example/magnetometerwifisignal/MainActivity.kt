package com.example.magnetometerwifisignal

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null

    private lateinit var wifiManager: WifiManager
    private var wifiScanReceiverRegistered = false

    private val TAG = "SensorWifiLogger"

    // Runtime permission launcher for location (needed for WiFi scanning)
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                startWifiScan()
            } else {
                Log.w(TAG, "Location permission denied; WiFi scanning will not work")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1) Setup magnetometer
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (magnetometer == null) {
            Log.e(TAG, "No magnetometer sensor found on this device")
        }

        // 2) Setup WiFi manager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 3) Setup buttons
        val btnStart: Button = findViewById(R.id.btnStart)
        val btnStop: Button = findViewById(R.id.btnStop)

        btnStart.setOnClickListener {
            startMagnetometer()
            checkPermissionAndScanWifi()
        }

        btnStop.setOnClickListener {
            stopMagnetometer()
            unregisterWifiReceiverIfNeeded()
        }
    }

    // ---------- Magnetometer ----------

    private fun startMagnetometer() {
        magnetometer?.let { sensor ->
            // SENSOR_DELAY_GAME ~ 50 Hz on many devices, within your 50–100 Hz target.[web:7][web:5]
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.d(TAG, "Magnetometer listener registered")
        }
    }

    private fun stopMagnetometer() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Magnetometer listener unregistered")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            // event.values[0], [1], [2] are µT along X, Y, Z.[web:5][web:9]
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            Log.d(TAG, "Magnetometer: x=$x, y=$y, z=$z")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for now
    }

    // ---------- WiFi scanning ----------

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (!success) {
                Log.w(TAG, "WiFi scan failed or not updated")
                return
            }

            val results: List<ScanResult> = wifiManager.scanResults
            for (result in results) {
                // SSID: network name, level: signal strength (dBm).[web:10][web:13]
                Log.d(TAG, "WiFi: SSID=${result.SSID}, BSSID=${result.BSSID}, level=${result.level} dBm")
            }

            // You can trigger another scan after some delay if you like (simple version: call startWifiScan())
            // But continuous scanning drains battery; use carefully in hackathon demo.
        }
    }

    private fun checkPermissionAndScanWifi() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startWifiScan()
        } else {
            // Ask for permission
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startWifiScan() {
        // Register receiver once
        if (!wifiScanReceiverRegistered) {
            registerReceiver(
                wifiScanReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            )
            wifiScanReceiverRegistered = true
        }

        val started = wifiManager.startScan()
        Log.d(TAG, "WiFi scan started: $started")
    }

    private fun unregisterWifiReceiverIfNeeded() {
        if (wifiScanReceiverRegistered) {
            unregisterReceiver(wifiScanReceiver)
            wifiScanReceiverRegistered = false
            Log.d(TAG, "WiFi scan receiver unregistered")
        }
    }

    override fun onResume() {
        super.onResume()
        // Optionally start magnetometer automatically
        // startMagnetometer()
    }

    override fun onPause() {
        super.onPause()
        stopMagnetometer()
        unregisterWifiReceiverIfNeeded()
    }
}
