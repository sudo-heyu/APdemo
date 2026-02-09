package com.heyu.apdemo2.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.heyu.apdemo2.R
import com.heyu.apdemo2.model.AccessPoint

class AccessPointAdapter(
    private var accessPoints: List<AccessPoint> = emptyList()
) : RecyclerView.Adapter<AccessPointAdapter.ViewHolder>() {
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ssidText: TextView = view.findViewById(R.id.tv_ssid)
        val signalIcon: ImageView = view.findViewById(R.id.iv_signal)
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
        
        holder.ssidText.text = ap.ssid
        holder.rssiText.text = "${ap.rssi} dBm"
        setSignalIcon(holder.signalIcon, ap.getSignalLevel())
        
        // 设置分数，字体颜色已在布局文件中设为 pale_blue
        holder.scoreText.text = ap.score?.toString() ?: "--"
        
        // 设置理由和展开逻辑
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
        
        // 移除整个 itemView 的点击监听，不再支持单击 AP 查询详情
        holder.itemView.setOnClickListener(null)
    }
    
    override fun getItemCount(): Int = accessPoints.size
    
    /**
     * 更新数据：优先显示有评分的，然后按信号强度排序
     */
    fun updateData(newData: List<AccessPoint>) {
        accessPoints = newData.sortedWith(compareByDescending<AccessPoint> { it.score != null }
            .thenByDescending { it.rssi })
        notifyDataSetChanged()
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
