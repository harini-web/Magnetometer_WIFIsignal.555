package com.example.magnetometerwifisignal

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ThreatLogsActivity : AppCompatActivity() {

    private lateinit var tvThreatCount: TextView
    private lateinit var tvTotalThreats: TextView
    private lateinit var tvActiveAlerts: TextView
    private lateinit var recyclerThreats: RecyclerView
    private lateinit var btnClear: Button
    private lateinit var btnExport: Button

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter: ThreatLogsAdapter
    private var isUpdating = true

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isUpdating) {
                refreshData()
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_threat_logs)

        tvThreatCount = findViewById(R.id.tvThreatCount)
        tvTotalThreats = findViewById(R.id.tvTotalThreats)
        tvActiveAlerts = findViewById(R.id.tvActiveAlerts)
        recyclerThreats = findViewById(R.id.recyclerThreats)
        btnClear = findViewById(R.id.btnClearThreats)
        btnExport = findViewById(R.id.btnExportThreats)

        adapter = ThreatLogsAdapter(LogRepository.realtimeLogs)
        recyclerThreats.layoutManager = LinearLayoutManager(this)
        recyclerThreats.adapter = adapter

        btnClear.setOnClickListener {
            LogRepository.clear()
            refreshData()
            Toast.makeText(this, "Logs Cleared", Toast.LENGTH_SHORT).show()
        }

        btnExport.setOnClickListener {
            Toast.makeText(this, "Export feature coming soon", Toast.LENGTH_SHORT).show()
        }

        handler.post(updateRunnable)
    }

    private fun refreshData() {
        adapter.updateData(LogRepository.realtimeLogs.toList())

        val count = LogRepository.getThreatCount()
        val active = LogRepository.getActiveAlertCount()

        tvThreatCount.text = "($count logs)"
        tvTotalThreats.text = count.toString()
        tvActiveAlerts.text = active.toString()

        // AUTO-SCROLL TO TOP (Shows newest items)
        recyclerThreats.scrollToPosition(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        isUpdating = false
        handler.removeCallbacks(updateRunnable)
    }
}