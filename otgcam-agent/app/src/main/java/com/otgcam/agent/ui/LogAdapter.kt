package com.otgcam.agent.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.otgcam.agent.R

/**
 * RecyclerView adapter for displaying timestamped log entries.
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val entries = mutableListOf<String>()

    /**
     * Append a new log entry and auto-scroll support is handled by the layout manager.
     */
    fun addEntry(entry: String) {
        entries.add(entry)
        if (entries.size > 50) {
            entries.removeAt(0)
        }
        notifyDataSetChanged()
    }

    /**
     * Remove all entries.
     */
    fun clear() {
        val size = entries.size
        entries.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    /**
     * View holder for a single log line.
     */
    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLog: TextView = itemView.findViewById(R.id.tvLog)

        fun bind(text: String) {
            tvLog.text = text
        }
    }
}
