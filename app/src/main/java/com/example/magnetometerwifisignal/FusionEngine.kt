package com.example.magnetometerwifisignal

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

// The result object returned every frame
data class FusionResult(
    val mainStatus: String,      // e.g., "INTRUSION ALERT"
    val subStatus: String,       // e.g., "Motion Detected (WiFi Scatter)"
    val colorHex: String,        // UI Color
    val debugTags: String,       // Technical logs for the bottom view
    val vibrationLevel: Int      // 0-255 haptic intensity
)

class FusionEngine {

    // --- CONFIGURATION ---
    private val HISTORY_SIZE = 20 // Approx 2-4 seconds of history depending on poll rate
    private val THRESHOLD_STATIONARY = 0.05f

    // --- BASELINES (Learned during calibration) ---
    var baseMag = 0f
    var baseWifi = 0f

    // --- NOISE FLOORS (Variance during calibration) ---
    var noiseWifi = 1.0f
    var noiseLight = 2.0f

    // --- LIVE INPUTS (Updated by MainActivity) ---
    var smoothMag = 0f
    var smoothWifi = 0f
    var smoothLight = 0f
    var acHumScore = 0f     // 50Hz/60Hz Jitter
    var linearMotion = 0f
    var angularMotion = 0f
    var visibleNetworkCount = 0 // From WiFi Scan

    // --- TOGGLE STATE ---
    var isSentryModeEnabled = false

    // --- HISTORY BUFFERS (For Variance Calculation) ---
    private val wifiHistory = FloatArray(HISTORY_SIZE)
    private val lightHistory = FloatArray(HISTORY_SIZE)
    private var historyIndex = 0

    // Called by MainActivity sensor loop
    fun updateData(mag: Float, wifi: Float, light: Float, acHum: Float, linear: Float, angular: Float, netCount: Int) {
        smoothMag = mag
        smoothWifi = wifi
        smoothLight = light
        acHumScore = acHum
        linearMotion = linear
        angularMotion = angular
        visibleNetworkCount = netCount

        // Circular Buffer Update
        historyIndex = (historyIndex + 1) % HISTORY_SIZE
        wifiHistory[historyIndex] = smoothWifi
        lightHistory[historyIndex] = smoothLight
    }

    // Called during the first 10 seconds
    fun calibrate(samples: Int) {
        if (samples == 1) {
            baseMag = smoothMag; baseWifi = smoothWifi
        } else {
            // Running Average
            baseMag = (baseMag * (samples - 1) + smoothMag) / samples
            baseWifi = (baseWifi * (samples - 1) + smoothWifi) / samples
        }

        // Learn the "Silence" of the room
        if (samples > 10) {
            val wVar = calculateStdDev(wifiHistory)
            if (wVar > 0) noiseWifi = (noiseWifi * 0.9f + wVar * 0.1f)
        }
    }

    // The Main Logic Brain
    fun analyze(): FusionResult {
        val tags = mutableListOf<String>()

        // 1. MOTION CHECK (Are we holding the phone?)
        val isStationary = linearMotion < THRESHOLD_STATIONARY && angularMotion < THRESHOLD_STATIONARY

        // 2. CALCULATE METRICS
        val magDiff = abs(smoothMag - baseMag)
        val wifiVar = calculateStdDev(wifiHistory)
        val lightVar = calculateStdDev(lightHistory)
        val wifiDrop = baseWifi - smoothWifi

        // 3. CROWD DENSITY CALCULATION
        // Logic: Infrastructure (APs) + Dynamic Absorption (Variance)
        var densityScore = 0

        // Static Infrastructure Score
        if (visibleNetworkCount > 5) densityScore += 1
        if (visibleNetworkCount > 15) densityScore += 1
        if (visibleNetworkCount > 30) densityScore += 1

        // Dynamic Body Blocking Score (Only valid if phone is still)
        // If variance is high but phone is still, bodies are moving through signals
        if (isStationary && wifiVar > 2.0f) densityScore += 1

        val densityLabel = when (densityScore) {
            0, 1 -> "Low Density"
            2, 3 -> "Moderate Crowd"
            else -> "High Congestion"
        }

        // Debug Tags
        if (magDiff > 10.0) tags.add("Mag++")
        if (acHumScore > 1.0) tags.add("AC-Hum")
        if (lightVar > 5.0) tags.add("Shadow")
        tags.add("APs:$visibleNetworkCount")

        // =========================================================
        // LOGIC BRANCH A: SENTRY MODE (Intrusion / Safety)
        // =========================================================
        if (isSentryModeEnabled) {
            if (!isStationary) {
                return FusionResult("SENTRY WARNING", "Device Moving - Put Down", "#FF9800", "Stabilize Device", 0)
            }

            // Trigger 1: Motion (Shadows or Signal Scattering)
            // We use 3x the learned noise floor to prevent false alarms
            val isWifiActive = wifiVar > (noiseWifi * 3.0f).coerceAtLeast(1.5f)
            val isShadows = lightVar > 4.0f

            if (isWifiActive || isShadows) {
                val reason = if(isShadows) "Light Shadow" else "WiFi Scattering"
                return FusionResult("INTRUSION DETECTED", "Motion ($reason)", "#E040FB", "Var:${"%.1f".format(wifiVar)} | $densityLabel", 255)
            }

            // Trigger 2: Signal Blocked (Person standing between you and router)
            if (wifiDrop > 5.0f && wifiVar < 2.0f) {
                return FusionResult("INTRUSION DETECTED", "Signal Line Blocked", "#AA00FF", "Drop: ${"%.1f".format(wifiDrop)}dB", 100)
            }
        }

        // =========================================================
        // LOGIC BRANCH B: SWEEP MODE (Counter-Surveillance)
        // =========================================================

        // Trigger 1: Powered Electronics ("Live Wire")
        // Logic: High Magnetic field AND high 50Hz/60Hz Jitter (Hum)
        if (magDiff > 3.0f && acHumScore > 1.0f) {
            val intensity = (magDiff * 5).toInt().coerceIn(50, 255)
            return FusionResult("THREAT DETECTED", "Live Electronics (AC Hum)", "#D32F2F", "Jitter: ${"%.1f".format(acHumScore)}", intensity)
        }

        // Trigger 2: Ferrous Magnet (Speaker/Mount/Latch)
        // Logic: Strong Magnetic field, but stable (No Hum)
        if (magDiff > 15.0f) {
            val intensity = (magDiff * 2).toInt().coerceIn(30, 150)
            return FusionResult("MAGNETIC SOURCE", "Ferrous Metal Detected", "#F44336", "Mag++", intensity)
        }

        // Default State
        val modeText = if(isSentryModeEnabled) "Sentry Active" else "Scanning..."
        return FusionResult("SECURE", "Env: $densityLabel", "#2E7D32", modeText, 0)
    }

    // Helper: Standard Deviation
    private fun calculateStdDev(data: FloatArray): Float {
        val mean = data.average().toFloat()
        var sumSq = 0.0f
        var count = 0
        for (f in data) {
            if (f != 0f) {
                sumSq += (f - mean).pow(2)
                count++
            }
        }
        return if (count > 0) sqrt(sumSq / count) else 0f
    }
}