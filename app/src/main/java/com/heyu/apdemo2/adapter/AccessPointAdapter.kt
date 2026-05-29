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

    companion object {
        // Demo APs for screenshot (置顶显示，按分数从高到低排列)
        private val DEMO_APS = listOf(
            AccessPoint(
                ssid = "Ideal",
                bssid = "00:00:00:00:00:01",
                rssi = -68,
                frequency = 2437,
                capabilities = "[WPA2-PSK-CCMP]",
                score = 97,
                reason = "Your Wi-Fi connection status is excellent, and the network quality is very good. The current internet speed is fast, the signal strength is excellent, and there is very little surrounding interference, providing a smooth overall experience.\n"
            ),
            AccessPoint(
                ssid = "High-Density Congestion",
                bssid = "00:00:00:00:00:02",
                rssi = -45,
                frequency = 5180,
                capabilities = "[WPA2-PSK-CCMP]",
                score = 74,
                reason = "Your Wi-Fi connection quality is average. The main reason is that the current network environment is somewhat congested, which may occasionally affect internet speed. However, your signal is strong, interference is minimal, and the overall connection is stable.\n"
            ),
            AccessPoint(
                ssid = "Backhaul Constrained",
                bssid = "00:00:00:00:00:03",
                rssi = -60,
                frequency = 2437,
                capabilities = "[WPA2-PSK-CCMP]",
                score = 72,
                reason = "Your Wi-Fi connection quality is average. Although the signal is strong and interference is very low, the network is occasionally congested, causing the internet speed to be sometimes unstable.\n"
            ),
            AccessPoint(
                ssid = "Co-channel Interference",
                bssid = "00:00:00:00:00:04",
                rssi = -55,
                frequency = 2412,
                capabilities = "[WPA2-PSK-CCMP]",
                score = 59,
                reason = "Your Wi-Fi connection quality is average. Although the signal is strong and interference is very low, the network is occasionally congested, causing the internet speed to be slow at times.\n"
            ),
            AccessPoint(
                ssid = "Legacy Standard",
                bssid = "00:00:00:00:00:05",
                rssi = -58,
                frequency = 2412,
                capabilities = "[WPA-PSK-CCMP]",
                score = 49,
                reason = "Your Wi-Fi connection quality is poor. The main reason is that the current network environment is very congested, causing unstable data transmission speeds that can be very slow at times. However, your device has strong signal reception and very little interference.\n"
            ),
            AccessPoint(
                ssid = "Internet Outage",
                bssid = "00:00:00:00:00:06",
                rssi = -42,
                frequency = 2437,
                capabilities = "[WPA-PSK-CCMP]",
                score = 30,
                reason = "Your Wi-Fi connection quality is poor. The main reason is that the current network environment is very congested, causing unstable data transmission speeds that can be very slow at times.\n"
            )
        )

        private const val DEMO_COUNT = 6
    }

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
        // 位置 0-5: Demo APs, 位置 6+: Scanned APs
        val ap = if (position < DEMO_COUNT) {
            DEMO_APS[position]
        } else {
            scannedAccessPoints[position - DEMO_COUNT]
        }

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

    // Demo APs (6) + Scanned APs
    override fun getItemCount(): Int = DEMO_COUNT + scannedAccessPoints.size

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
        // Search in demo APs first
        DEMO_APS.find { it.ssid == ssid }?.let { return it }
        // Then in scanned APs
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
