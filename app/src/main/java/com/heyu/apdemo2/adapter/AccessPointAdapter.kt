package com.heyu.apdemo2.adapter

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.heyu.apdemo2.R
import com.heyu.apdemo2.model.AccessPoint

/**
 * AP列表适配器
 * 用于在RecyclerView中展示WiFi热点信息（仅SSID和RSSI）
 */
class AccessPointAdapter(
    private var accessPoints: List<AccessPoint> = emptyList(),
    private val onItemClick: (AccessPoint) -> Unit
) : RecyclerView.Adapter<AccessPointAdapter.ViewHolder>() {
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ssidText: TextView = view.findViewById(R.id.tv_ssid)
        val signalIcon: ImageView = view.findViewById(R.id.iv_signal)
        val rssiText: TextView = view.findViewById(R.id.tv_rssi)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_access_point, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ap = accessPoints[position]
        
        // 设置SSID
        holder.ssidText.text = ap.ssid
        
        // 设置信号图标
        setSignalIcon(holder.signalIcon, ap.getSignalLevel())
        
        // 设置RSSI显示
        holder.rssiText.text = "${ap.rssi} dBm"
        
        // 设置点击事件
        holder.itemView.setOnClickListener {
            onItemClick(ap)
        }
    }
    
    override fun getItemCount(): Int = accessPoints.size
    
    /**
     * 更新数据
     */
    fun updateData(newData: List<AccessPoint>) {
        // 按信号强度排序（降序）
        val sortedData = newData.sortedByDescending { it.rssi }
        accessPoints = sortedData
        notifyDataSetChanged()
    }
    
    /**
     * 设置信号强度图标
     */
    private fun setSignalIcon(imageView: ImageView, level: Int) {
        val iconRes = when (level) {
            4 -> R.drawable.ic_signal_4  // 信号很强
            3 -> R.drawable.ic_signal_3  // 信号良好
            2 -> R.drawable.ic_signal_2  // 信号一般
            1 -> R.drawable.ic_signal_1  // 信号较弱
            else -> R.drawable.ic_signal_0 // 信号很差
        }
        imageView.setImageResource(iconRes)
    }
    
    /**
     * RecyclerView项目间距装饰器（带分割线）
     */
    class ItemDecoration : RecyclerView.ItemDecoration() {
        private val dividerPaint = Paint().apply {
            color = 0xFFDDDDDD.toInt() // 淡灰色
            strokeWidth = 1f
        }
        
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            
            // 设置左右边距
            outRect.left = 16
            outRect.right = 16
            
            // 第一个项目顶部间距
            if (position == 0) {
                outRect.top = 8
            }
            
            // 所有项目底部间距（为分割线预留空间）
            outRect.bottom = 1
        }
        
        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val left = parent.paddingLeft + 16
            val right = parent.width - parent.paddingRight - 16
            
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val params = child.layoutParams as RecyclerView.LayoutParams
                
                val top = child.bottom + params.bottomMargin
                val bottom = top + 1
                
                c.drawLine(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), dividerPaint)
            }
        }
    }
}