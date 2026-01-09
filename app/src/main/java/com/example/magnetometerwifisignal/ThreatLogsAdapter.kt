package com.example.magnetometerwifisignal

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ThreatLogsAdapter(private var logs: List<LogEntry>) :
    RecyclerView.Adapter<ThreatLogsAdapter.LogViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(android.R.id.text1)
        val tvMessage: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        // Using standard Android layout for simplicity, or create a custom row layout
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]

        holder.tvTime.text = timeFormat.format(Date(log.timestamp))
        holder.tvTime.setTextColor(Color.GRAY)
        holder.tvTime.textSize = 10f

        holder.tvMessage.text = log.message
        holder.tvMessage.setTextColor(Color.parseColor(log.colorHex))
        holder.tvMessage.textSize = 12f
        holder.tvMessage.typeface = android.graphics.Typeface.MONOSPACE

        // Dark background for rows
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun getItemCount() = logs.size

    fun updateData(newLogs: List<LogEntry>) {
        logs = newLogs
        notifyDataSetChanged()
    }
}