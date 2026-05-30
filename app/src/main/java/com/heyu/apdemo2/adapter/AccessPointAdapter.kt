package com.heyu.apdemo2.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.heyu.apdemo2.R
import com.heyu.apdemo2.model.AccessPoint

class AccessPointAdapter(
    private var scannedAccessPoints: List<AccessPoint> = emptyList()
) : RecyclerView.Adapter<AccessPointAdapter.ViewHolder>() {

    interface OnItemClickListener {
        fun onItemClick(ap: AccessPoint)
    }

    var onItemClickListener: OnItemClickListener? = null
    var pinnedSsid: String? = null
        private set

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ssidText: TextView = view.findViewById(R.id.tv_ssid)
        val signalIcon: ImageView = view.findViewById(R.id.iv_signal)
        val connectedIcon: ImageView = view.findViewById(R.id.iv_connected)
        val rssiText: TextView = view.findViewById(R.id.tv_rssi)
        val scoreText: TextView = view.findViewById(R.id.tv_score)
        val expandIcon: ImageView = view.findViewById(R.id.iv_expand)
        val reasonText: TextView = view.findViewById(R.id.tv_reason)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_access_point, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ap = scannedAccessPoints[position]

        val isPinned = ap.ssid == pinnedSsid

        holder.ssidText.text = ap.ssid
        holder.rssiText.text = "${ap.rssi} dBm"
        setSignalIcon(holder.signalIcon, ap.getSignalLevel())

        // Connected indicator icon + pinned background
        holder.connectedIcon.visibility = if (isPinned) View.VISIBLE else View.GONE
        holder.itemView.setBackgroundColor(
            if (isPinned)
                ContextCompat.getColor(holder.itemView.context, R.color.pinned_bg)
            else
                Color.TRANSPARENT
        )

        holder.scoreText.text = ap.score?.toString() ?: "--"

        if (ap.reason.isNullOrEmpty()) {
            holder.expandIcon.visibility = View.GONE
            holder.reasonText.visibility = View.GONE
        } else {
            holder.expandIcon.visibility = View.VISIBLE
            holder.reasonText.text = ap.reason

            if (ap.isExpanded) {
                holder.reasonText.visibility = View.VISIBLE
                holder.expandIcon.setImageResource(R.drawable.ic_expand_less)
            } else {
                holder.reasonText.visibility = View.GONE
                holder.expandIcon.setImageResource(R.drawable.ic_expand_more)
            }

            holder.expandIcon.setOnClickListener {
                ap.isExpanded = !ap.isExpanded
                notifyItemChanged(position)
            }
        }

        // Click entire item -> connect/disconnect
        holder.itemView.setOnClickListener {
            onItemClickListener?.onItemClick(ap)
        }
    }

    override fun getItemCount(): Int = scannedAccessPoints.size

    /**
     * Update scanned data (sorted by score descending, then rssi)
     */
    fun updateData(newData: List<AccessPoint>) {
        scannedAccessPoints = newData.sortedWith(
            compareByDescending<AccessPoint> { it.score }
                .thenByDescending { it.rssi }
        )
        notifyDataSetChanged()
    }

    /**
     * Set (or clear) pinned SSID.
     */
    fun setPinned(ssid: String?) {
        pinnedSsid = ssid
        notifyDataSetChanged()
    }

    fun findBySsid(ssid: String): AccessPoint? {
        return scannedAccessPoints.find { it.ssid == ssid }
    }

    private fun setSignalIcon(imageView: ImageView, level: Int) {
        val iconRes = when (level) {
            4 -> R.drawable.ic_signal_4
            3 -> R.drawable.ic_signal_3
            2 -> R.drawable.ic_signal_2
            1 -> R.drawable.ic_signal_1
            else -> R.drawable.ic_signal_0
        }
        imageView.setImageResource(iconRes)
    }
}
