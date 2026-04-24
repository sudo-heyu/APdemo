package com.heyu.apdemo2.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.heyu.apdemo2.R
import com.heyu.apdemo2.roaming.RoamingLogManager

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
            showDetail("Cannot run in background?", BACKGROUND_CONTENT)
        }
        view.findViewById<MaterialCardView>(R.id.card_auto_connect).setOnClickListener {
            showAutoConnectDetail()
        }
        view.findViewById<MaterialCardView>(R.id.card_roaming_log)?.setOnClickListener {
            showRoamingLog()
        }
    }

    private val logHtmlStyle = """
        body { font-family: monospace; font-size: 11px; color: #222; padding: 8px; line-height: 1.6; }
        table { border-collapse: collapse; margin: 4px 0; font-size: 11px; }
        th, td { border: 1px solid #999; padding: 3px 6px; text-align: center; white-space: nowrap; }
        th { background: #e0e0e0; font-weight: bold; }
        tr:nth-child(even) { background: #f5f5f5; }
    """.trimIndent()

    private fun logsToHtml(logText: String): String {
        val body = if (logText.isBlank() || logText == "No logs available") "No logs available"
                   else logText.replace("\n", "<br>")
        return """<html><head><style>$logHtmlStyle</style></head>
            <body><div id="log">$body</div>
            <script>
            var userScrolled = false;
            function atBottom() {
                return (window.innerHeight + window.scrollY) >= (document.body.scrollHeight - 30);
            }
            window.addEventListener('scroll', function() {
                userScrolled = !atBottom();
            });
            function append(html) {
                var d = document.getElementById('log');
                d.insertAdjacentHTML('beforeend', '<br>' + html);
                if (!userScrolled) window.scrollTo(0, document.body.scrollHeight);
            }
            window.onload = function() { window.scrollTo(0, document.body.scrollHeight); };
            </script></body></html>"""
    }

    private fun String.jsEscape(): String = this
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")

    private fun showRoamingLog() {
        val ctx = requireContext()
        val logManager = RoamingLogManager.getInstance(ctx)
        val logs = logManager.getLogs()

        val webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            loadDataWithBaseURL(null, logsToHtml(logs), "text/html", "UTF-8", null)
        }

        val listener = RoamingLogManager.OnLogListener { logLine ->
            activity?.runOnUiThread {
                val escaped = logLine.jsEscape()
                webView.evaluateJavascript("append('$escaped')", null)
            }
        }
        logManager.addListener(listener)

        AlertDialog.Builder(ctx)
            .setTitle("Roaming Algorithm Log")
            .setView(webView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                logManager.clearLogs()
                webView.loadDataWithBaseURL(null, logsToHtml("No logs available"), "text/html", "UTF-8", null)
            }
            .setOnDismissListener {
                logManager.removeListener(listener)
                webView.destroy()
            }
            .show()
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
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAutoConnectDetail() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val padH = (32 * dp).toInt()
        val padV = (16 * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Text description
        root.addView(TextView(ctx).apply {
            text = AUTO_CONNECT_CONTENT
            textSize = 14f
            setTextColor(0xFF444444.toInt())
            setLineSpacing(0f, 1.5f)
            setPadding(padH, padV, padH, padV)
        })

        // 4 guide images
        val guides = listOf(
            Pair(R.drawable.guild_1, "Step 1: Tap \"Go to Settings\""),
            Pair(R.drawable.guild_2, "Step 2: Find \"WiFi Quick Switch\""),
            Pair(R.drawable.guild_3, "Step 3: Enable \"WiFi Quick Switch\""),
            Pair(R.drawable.guild_4, "Step 4: Tap \"Allow\"")
        )

        for ((drawableId, caption) in guides) {
            root.addView(TextView(ctx).apply {
                text = caption
                textSize = 13f
                setTextColor(0xFF555555.toInt())
                setPadding(padH, (8 * dp).toInt(), padH, (4 * dp).toInt())
            })
            root.addView(ImageView(ctx).apply {
                setImageResource(drawableId)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { lp ->
                    lp.setMargins(padH, 0, padH, padV)
                }
            })
        }

        AlertDialog.Builder(ctx)
            .setTitle("Cannot auto-connect?")
            .setView(ScrollView(ctx).also { it.addView(root) })
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Help Content ──────────────────────────────────────────────────────────────

    private val BACKGROUND_CONTENT = """
This app uses foreground service + WakeLock to keep running in background without interruption, but domestic manufacturers' systems may impose additional restrictions on background activities. Please configure according to your phone brand:

【Xiaomi / Redmi / MIUI / HyperOS】
• Settings → App settings → App management → Find this app
• Tap "Battery saver" → Select "Unrestricted"
• Settings → Permissions → Autostart → Enable this app

【Huawei / Honor / HarmonyOS】
• Settings → Apps → App launch → Find this app
• Disable "Manage automatically" → Manually check:
  ✓ Allow auto-launch
  ✓ Allow background activity
  ✓ Allow secondary launch

【OPPO / OnePlus / realme / ColorOS】
• Settings → Battery → Battery protection → Find this app → Disable restriction
• Settings → App management → This app → Battery → Allow background activity

【VIVO / OriginOS / FuntouchOS】
• Settings → Battery → High background power consumption → Allow this app
• iManager → App management → Permission management → Find this app → Allow background running

【Samsung / One UI】
• Settings → Apps → Find this app → Battery → Select "Unrestricted"

【General Method】
• Settings → Apps → Find this app → Battery → Don't restrict background activity
• In recent apps view, long press this app's card → Lock (prevent clearing)
    """.trimIndent()

    private val AUTO_CONNECT_CONTENT = """
After tapping a WiFi in the list, the system will pop up a small window on the current page, and you can complete the connection after confirmation, without jumping to the WiFi settings page.

If the popup doesn't appear, please check:
• Is WiFi enabled
• Is the target network within signal coverage

If the popup appears but connection fails:
• Confirm password is correct (at least 8 characters)
• Try forgetting the network then reconnect
• Some enterprise encryption (WPA3-Enterprise) is not supported

If you want a smoother "one-tap switch" experience, you can go to the phone's accessibility settings and enable this app's accessibility service permission. After enabling, tapping WiFi will complete the connection directly without any popup confirmation.

Path for each brand:

【Xiaomi / Redmi / MIUI / HyperOS】
Settings → Additional settings → Accessibility → Installed apps → APdemo → Enable

【Huawei / Honor / HarmonyOS】
Settings → Accessibility features → Accessibility → Installed services → APdemo → Enable

【OPPO / OnePlus / realme / ColorOS】
Settings → Additional settings → Accessibility → Accessibility → Installed apps → APdemo → Enable

【VIVO / OriginOS / FuntouchOS】
Settings → More settings → Accessibility → Downloaded apps → APdemo → Enable
(If not found in list, please uninstall and reinstall the app first)

【Samsung / One UI】
Settings → Accessibility → Installed apps → APdemo → Enable

【General】
Settings → Accessibility (or Accessibility features) → Installed services → APdemo → Enable
    """.trimIndent()
}
