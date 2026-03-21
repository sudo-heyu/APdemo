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
    private var accessPoints: List<AccessPoint> = emptyList()
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
        val ap = accessPoints[position]
        val isPinned = ap.ssid == pinnedSsid

        holder.ssidText.text = ap.ssid
        holder.rssiText.text = "${ap.rssi} dBm"
        setSignalIcon(holder.signalIcon, ap.getSignalLevel())

        // 已连接指示图标 + 置顶背景
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

        // 点击整个 item → 连接/取消连接
        holder.itemView.setOnClickListener {
            onItemClickListener?.onItemClick(ap)
        }
    }

    override fun getItemCount(): Int = accessPoints.size

    /**
     * 更新数据：置顶 AP 排第一，然后有评分的优先，再按信号强度排序。
     */
    fun updateData(newData: List<AccessPoint>) {
        accessPoints = sortWithPinned(newData)
        notifyDataSetChanged()
    }

    /**
     * 设置（或清除）置顶 SSID，并对当前列表重新排序。
     */
    fun setPinned(ssid: String?) {
        pinnedSsid = ssid
        accessPoints = sortWithPinned(accessPoints)
        notifyDataSetChanged()
    }

    fun findBySsid(ssid: String): AccessPoint? = accessPoints.find { it.ssid == ssid }

    private fun sortWithPinned(data: List<AccessPoint>): List<AccessPoint> =
        data.sortedWith(
            compareByDescending<AccessPoint> { it.ssid == pinnedSsid }
                .thenByDescending { it.score != null }
                .thenByDescending { it.rssi }
        )

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
