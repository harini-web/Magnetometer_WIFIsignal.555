package com.example.magnetometerwifisignal

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ThreatLogAdapter(private val threats: List<LogEntry>) :
    RecyclerView.Adapter<ThreatLogAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvThreatTime: TextView = itemView.findViewById(R.id.tvThreatTime)
        val tvThreatType: TextView = itemView.findViewById(R.id.tvThreatType)
        val tvThreatSeverity: TextView = itemView.findViewById(R.id.tvThreatSeverity)
        val tvThreatDetails: TextView = itemView.findViewById(R.id.tvThreatDetails)
        val tvThreatMetrics: TextView = itemView.findViewById(R.id.tvThreatMetrics)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_threat_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val threat = threats[position]
        holder.tvThreatTime.text = threat.timestamp
        holder.tvThreatType.text = threat.type
        holder.tvThreatDetails.text = threat.message

        // Severity based on threat type
        val severity = when {
            threat.type.contains("INTRUSION") -> "CRITICAL"
            threat.type.contains("THREAT") -> "HIGH"
            else -> "MEDIUM"
        }
        holder.tvThreatSeverity.text = severity
    }

    override fun getItemCount() = threats.size
}
