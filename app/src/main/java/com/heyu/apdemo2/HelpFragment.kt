package com.heyu.apdemo2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView

class HelpFragment : Fragment() {

    companion object {
        const val FRAGMENT_TAG = "help"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_help, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialCardView>(R.id.card_background).setOnClickListener {
            showDetail("无法后台运行？", BACKGROUND_CONTENT)
        }
        view.findViewById<MaterialCardView>(R.id.card_auto_connect).setOnClickListener {
            showDetail("无法自动连接？", AUTO_CONNECT_CONTENT)
        }
    }

    private fun showDetail(title: String, content: String) {
        val ctx = requireContext()
        val scrollView = ScrollView(ctx)
        val tv = TextView(ctx).apply {
            text = content
            textSize = 14f
            setTextColor(0xFF444444.toInt())
            setLineSpacing(0f, 1.5f)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        scrollView.addView(tv)

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ── 帮助内容 ──────────────────────────────────────────────────────────────

    private val BACKGROUND_CONTENT = """
本应用使用前台服务 + WakeLock 保持后台运行不中断，但国内各厂商系统会额外限制后台活动。请按您的手机品牌进行设置：

【小米 / 红米 / MIUI / HyperOS】
• 设置 → 应用设置 → 应用管理 → 找到本应用
• 点击「省电策略」→ 选择「无限制」
• 设置 → 权限 → 自启动 → 开启本应用

【华为 / 荣耀 / HarmonyOS】
• 设置 → 应用 → 应用启动管理 → 找到本应用
• 关闭「自动管理」→ 手动勾选：
  ✓ 允许自启动
  ✓ 允许后台活动
  ✓ 允许关联启动

【OPPO / 一加 / 真我 / ColorOS】
• 设置 → 电池 → 耗电保护 → 找到本应用 → 关闭限制
• 设置 → 应用管理 → 本应用 → 省电 → 后台运行不受限

【VIVO / OriginOS / FuntouchOS】
• 设置 → 电池 → 后台高耗电 → 允许本应用
• i管家 → 软件管理 → 权限管理 → 找到本应用 → 允许后台运行

【三星 / One UI】
• 设置 → 应用程序 → 找到本应用 → 电池 → 选择「无限制」

【通用方法】
• 设置 → 应用 → 找到本应用 → 电池 → 不限制后台活动
• 在最近任务界面，长按本应用卡片 → 锁定（防止被清理）
    """.trimIndent()

    private val AUTO_CONNECT_CONTENT = """
点击列表中的 WiFi 后，系统会在当前页面弹出一个小窗口，确认后即可完成连接，无需跳转到 WiFi 设置页面。

如果弹窗没有出现，请检查：
• WiFi 是否已开启
• 目标网络是否在信号覆盖范围内

如果弹窗出现但连接失败：
• 确认密码输入无误（至少 8 位）
• 尝试忘记该网络后重新连接
• 部分企业级加密（WPA3-Enterprise）暂不支持

如果您希望开启更顺畅的「一键切换」体验，可前往手机的无障碍设置，开启本应用的无障碍服务权限。开启后点击 WiFi 将直接完成连接，无需任何弹窗确认。

各品牌开启路径如下：

【小米 / 红米 / MIUI / HyperOS】
设置 → 更多设置 → 无障碍 → 已安装的应用 → APdemo → 开启

【华为 / 荣耀 / HarmonyOS】
设置 → 辅助功能 → 无障碍功能 → 已安装的服务 → APdemo → 开启

【OPPO / 一加 / 真我 / ColorOS】
设置 → 其他设置 → 辅助功能 → 无障碍 → 已安装的应用 → APdemo → 开启

【VIVO / OriginOS / FuntouchOS】
设置 → 更多设置 → 无障碍 → 已下载的应用 → APdemo → 开启
（若列表中找不到，请先卸载重装应用后再查找）

【三星 / One UI】
设置 → 辅助功能 → 已安装的应用 → APdemo → 开启

【通用】
设置 → 无障碍（或辅助功能）→ 已安装的服务 → APdemo → 开启
    """.trimIndent()
}
