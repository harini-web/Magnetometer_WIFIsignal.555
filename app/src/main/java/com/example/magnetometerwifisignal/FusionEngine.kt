package com.example.magnetometerwifisignal

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class FusionResult(
    val mainStatus: String,
    val subStatus: String,
    val colorHex: String,
    val debugTags: String
)

class FusionEngine {

    // --- TOGGLE STATE ---
    var isPersonModeEnabled = false

    // --- INPUTS ---
    var smoothMag = 0f
    var smoothSonar = 0f
    var dopplerDir = 0 // 0, 1, -1
    var smoothWifi = 0f
    var smoothLight = 0f
    var linearMotion = 0f
    var angularMotion = 0f
    var acHumScore = 0f // Feature from before

    // --- BASELINES ---
    var baseMag = 0f
    var baseSonar = 0f
    var baseWifi = 0f

    // --- BUFFERS (For Person Detection Variance) ---
    private val wifiHistory = FloatArray(20)
    private val lightHistory = FloatArray(20)
    private var historyIndex = 0
    private var noiseWifi = 1.0f

    fun updateData(mag: Float, sonarResult: SonarResult, wifi: Float, light: Float, linear: Float, angular: Float) {
        smoothMag = mag
        smoothSonar = sonarResult.amplitude
        dopplerDir = sonarResult.dopplerShift
        smoothWifi = wifi
        smoothLight = light
        linearMotion = linear
        angularMotion = angular

        historyIndex = (historyIndex + 1) % 20
        wifiHistory[historyIndex] = smoothWifi
        lightHistory[historyIndex] = smoothLight
    }

    fun calibrate(samples: Int, totalSamples: Int) {
        if (samples == 1) {
            baseMag = smoothMag; baseSonar = smoothSonar; baseWifi = smoothWifi
        } else {
            baseMag = (baseMag * (samples - 1) + smoothMag) / samples
            baseSonar = (baseSonar * (samples - 1) + smoothSonar) / samples
            baseWifi = (baseWifi * (samples - 1) + smoothWifi) / samples
        }
        // Learn noise floor
        if (samples > 10) {
            val wVar = calculateStdDev(wifiHistory)
            if (wVar > 0) noiseWifi = (noiseWifi * 0.9f + wVar * 0.1f)
        }
    }

    fun analyze(): FusionResult {
        val tags = mutableListOf<String>()

        // 1. MOTION CHECK
        if (linearMotion > 0.5f || angularMotion > 0.5f) {
            return FusionResult("DEVICE HANDLING", "Moving...", "#FF9800", "")
        }

        val magDiff = abs(smoothMag - baseMag)
        val sonarRatio = if (baseSonar > 0) smoothSonar / baseSonar else 1.0f
        val wifiVar = calculateStdDev(wifiHistory)
        val lightVar = calculateStdDev(lightHistory)

        // Tags
        if (magDiff > 5.0) tags.add("Mag++")
        if (sonarRatio > 1.2) tags.add("Echo++")
        if (sonarRatio < 0.8) tags.add("Echo--")
        if (dopplerDir == 1) tags.add("Dop:Near")
        if (dopplerDir == -1) tags.add("Dop:Far")

        // ----------------------------------------------------
        // LOGIC BRANCH A: PERSON DETECTION (Only if Enabled)
        // ----------------------------------------------------
        if (isPersonModeEnabled) {
            // Doppler is strong evidence of a person walking
            if (dopplerDir != 0) {
                val action = if (dopplerDir == 1) "Approaching" else "Moving Away"
                return FusionResult("PERSON DETECTED", "$action (Doppler)", "#9C27B0", tags.joinToString(" "))
            }

            // Standard Variance check
            val isWifiActive = wifiVar > (noiseWifi * 3.0f).coerceAtLeast(1.5f)
            val isShadows = lightVar > 4.0f

            if (isWifiActive || isShadows) {
                return FusionResult("PERSON DETECTED", "Motion in Room", "#9C27B0", tags.joinToString(" "))
            }
        }

        // ----------------------------------------------------
        // LOGIC BRANCH B: OBJECT CLASSIFICATION (Always On)
        // ----------------------------------------------------

        // 1. Ferrous Metal
        if (magDiff > 10.0f) {
            return FusionResult("OBJECT DETECTED", "Ferrous Metal", "#D32F2F", tags.joinToString(" "))
        }

        // 2. Hard Non-Metal (Glass/Wood) - Strong Echo, No Mag
        if (magDiff < 5.0f && sonarRatio > 1.15f) {
            return FusionResult("OBJECT DETECTED", "Hard Surface (Non-Metal)", "#F44336", tags.joinToString(" "))
        }

        // 3. Soft/Absorptive (Fabric/Hand) - Weak Echo
        if (sonarRatio < 0.85f) {
            return FusionResult("OBJECT DETECTED", "Soft Material / Hand", "#2196F3", tags.joinToString(" "))
        }

        // 4. Smooth Object (Very Stable Echo + No Scattering)
        if (abs(1.0f - sonarRatio) < 0.05f && magDiff < 2.0f && sonarRatio != 0f) {
            // Close to 1.0 means reflecting exactly as calibrated (likely flat wall/table)
            // This is a subtle state usually just "Clear", but if specifically probing:
            // return FusionResult("OBJECT DETECTED", "Smooth Surface", "#00BCD4", tags.joinToString(" "))
        }

        return FusionResult("SCANNING", "Area Secure", "#4CAF50", if(isPersonModeEnabled) "Person Mode ON" else "Object Mode ONLY")
    }

    private fun calculateStdDev(data: FloatArray): Float {
        val mean = data.average().toFloat()
        var sumSq = 0.0f
        var count = 0
        for (f in data) { if (f != 0f) { sumSq += (f - mean).pow(2); count++ } }
        return if (count > 0) sqrt(sumSq / count) else 0f
    }
}