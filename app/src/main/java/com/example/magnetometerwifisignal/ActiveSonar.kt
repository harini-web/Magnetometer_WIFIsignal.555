package com.example.magnetometerwifisignal

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

data class SonarResult(
    val amplitude: Float,
    val dopplerShift: Int // 0=None, 1=Approaching, -1=Receding
)

class ActiveSonar(private val callback: (SonarResult) -> Unit) {
    private val sampleRate = 44100
    private val targetFreq = 19000.0
    // Doppler Shift for walking speed (~1.3m/s) is approx +/- 150Hz
    private val freqHigh = 19150.0
    private val freqLow = 18850.0

    private var isRunning = false
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread { generateTone() }.start()
        Thread { listenForEcho() }.start()
    }

    fun stop() {
        isRunning = false
        try {
            audioTrack?.pause(); audioTrack?.flush(); audioTrack?.release()
            audioRecord?.stop(); audioRecord?.release()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun generateTone() {
        val numSamples = sampleRate
        val buffer = ShortArray(numSamples)
        // Optimized Sine Generation
        val factor = 2.0 * PI / (sampleRate / targetFreq)
        for (i in 0 until numSamples) {
            buffer[i] = (sin(i * factor) * Short.MAX_VALUE).toInt().toShort()
        }

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(numSamples * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            audioTrack?.write(buffer, 0, numSamples)
            audioTrack?.setLoopPoints(0, numSamples, -1)
            if (isRunning) audioTrack?.play()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun listenForEcho() {
        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            val buffer = ShortArray(bufferSize)
            audioRecord?.startRecording()

            var smoothAmp = 0.0

            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (read > 0) {
                    // Run 3 Filters efficiently
                    val magCenter = calculateGoertzel(buffer, read, targetFreq, sampleRate)
                    val magHigh = calculateGoertzel(buffer, read, freqHigh, sampleRate)
                    val magLow = calculateGoertzel(buffer, read, freqLow, sampleRate)

                    smoothAmp = (smoothAmp * 0.8) + (magCenter * 0.2)

                    // Doppler Logic
                    // We check if sideband energy is significant relative to center
                    var direction = 0
                    val threshold = smoothAmp * 0.2 // Sideband must be 20% of main echo

                    if (magHigh > threshold && magHigh > magLow * 1.1) {
                        direction = 1 // Approaching (Higher pitch)
                    } else if (magLow > threshold && magLow > magHigh * 1.1) {
                        direction = -1 // Receding (Lower pitch)
                    }

                    callback(SonarResult(smoothAmp.toFloat(), direction))
                }
                Thread.sleep(40) // 25Hz Update rate
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // Optimized Goertzel
    private fun calculateGoertzel(samples: ShortArray, numSamples: Int, freq: Double, sRate: Int): Double {
        val k = (0.5 + ((numSamples * freq) / sRate)).toInt()
        val omega = (2.0 * PI * k) / numSamples
        val coeff = 2.0 * cos(omega)
        var q0 = 0.0; var q1 = 0.0; var q2 = 0.0

        for (i in 0 until numSamples) {
            q0 = (coeff * q1) - q2 + samples[i]
            q2 = q1; q1 = q0
        }
        return sqrt((q1 * q1) + (q2 * q2) - q1 * q2 * coeff)
    }
}