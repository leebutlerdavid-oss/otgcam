package com.otgcam.receiver.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.otgcam.receiver.FullscreenImageActivity
import com.otgcam.receiver.R
import com.otgcam.receiver.model.MediaItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for the live media feed. Supports photo and video view types.
 */
class MediaAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<MediaItem>()

    companion object {
        private const val VIEW_TYPE_PHOTO = 1
        private const val VIEW_TYPE_VIDEO = 2
    }

    /**
     * Insert a new item at the top of the feed.
     * @param item The media item to display.
     */
    fun insertItem(item: MediaItem) {
        items.add(0, item)
        notifyItemInserted(0)
    }

    /**
     * Replace the entire dataset (used when restoring from persistence).
     * @param newItems Ordered list with newest first.
     */
    fun setItems(newItems: List<MediaItem>) {
        val oldSize = items.size
        items.clear()
        items.addAll(newItems)
        notifyItemRangeRemoved(0, oldSize)
        notifyItemRangeInserted(0, newItems.size)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is MediaItem.Photo -> VIEW_TYPE_PHOTO
            is MediaItem.Video -> VIEW_TYPE_VIDEO
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PHOTO -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_media_photo, parent, false)
                PhotoViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_media_video, parent, false)
                VideoViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is MediaItem.Photo -> (holder as PhotoViewHolder).bind(item)
            is MediaItem.Video -> (holder as VideoViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    /**
     * View holder for photo items.
     */
    class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)

        fun bind(item: MediaItem.Photo) {
            val file = File(item.localPath)
            ivThumbnail.load(file)
            tvFileName.text = file.name
            tvTimestamp.text = formatTimestamp(item.timestamp)
            tvFileSize.text = formatSize(item.fileSizeBytes)
            itemView.setOnClickListener {
                val context = itemView.context
                val intent = Intent(context, FullscreenImageActivity::class.java).apply {
                    putExtra(FullscreenImageActivity.EXTRA_IMAGE_PATH, item.localPath)
                }
                context.startActivity(intent)
            }
        }
    }

    /**
     * View holder for video items.
     */
    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)

        fun bind(item: MediaItem.Video) {
            val file = File(item.localPath)
            ivThumbnail.load(file)
            tvFileName.text = file.name
            tvTimestamp.text = formatTimestamp(item.timestamp)
            tvFileSize.text = formatSize(item.fileSizeBytes)
            itemView.setOnClickListener {
                val context = itemView.context
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        ),
                        "video/mp4"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.2f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format(Locale.getDefault(), "%.2f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
