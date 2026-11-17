package com.tonytran.aitrading

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Opt-in foreground monitor. It only reads public Binance ticker prices and
 * sends local warnings; it never places, closes, or modifies exchange orders.
 */
class BackgroundMonitorService : Service() {
    companion object {
        const val ACTION_START = "com.tonytran.aitrading.MONITOR_START"
        const val ACTION_UPDATE = "com.tonytran.aitrading.MONITOR_UPDATE"
        const val ACTION_STOP = "com.tonytran.aitrading.MONITOR_STOP"
        const val EXTRA_TRADES = "open_trades_json"
        private const val FOREGROUND_ID = 7311
        private const val CHANNEL = "tony_background_monitor"
        private const val ALERT_CHANNEL = "tony_background_alerts"
        private const val PREFS = "tony_background_monitor"
        private const val KEY_FIRED = "fired_alert_keys"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    @Volatile private var positionsJson = "[]"
    private val fired = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        fired.addAll(prefs.getStringSet(KEY_FIRED, emptySet()).orEmpty())
        positionsJson = prefs.getString("positions_snapshot", "[]") ?: "[]"
        startForeground(FOREGROUND_ID, buildStatus("Đang khởi động theo dõi giá công khai"))
        scope.launch {
            while (isActive) {
                try {
                    monitorOnce()
                } catch (_: Exception) {
                    updateStatus("Mất kết nối tạm thời • sẽ thử lại")
                }
                delay(30_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE, ACTION_START -> {
                positionsJson = intent.getStringExtra(EXTRA_TRADES) ?: positionsJson
                prefs.edit().putString("positions_snapshot", positionsJson).apply()
                if (positionsJson == "[]") {
                    updateStatus("Đang chờ lệnh mô phỏng mở")
                } else {
                    updateStatus("Đang theo dõi ${runCatching { JSONArray(positionsJson).length() }.getOrDefault(0)} vị thế")
                }
            }
        }
        return START_STICKY
    }

    private suspend fun monitorOnce() {
        val positions = JSONArray(positionsJson)
        if (positions.length() == 0) {
            updateStatus("Đang chạy • chưa có vị thế mô phỏng mở")
            return
        }
        val symbols = (0 until positions.length()).map { positions.getJSONObject(it).getString("symbol") }.distinct()
        val prices = mutableMapOf<String, Double>()
        for (symbol in symbols) {
            val url = "https://api.binance.com/api/v3/ticker/price?symbol=$symbol"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    prices[symbol] = json.optString("price").toDoubleOrNull() ?: 0.0
                }
            }
        }
        var alerts = 0
        for (i in 0 until positions.length()) {
            val p = positions.getJSONObject(i)
            val symbol = p.getString("symbol")
            val price = prices[symbol] ?: continue
            if (price <= 0.0) continue
            val entry = p.optDouble("entry", 0.0)
            val qty = p.optDouble("quantity", 0.0)
            val margin = p.optDouble("margin", 0.0)
            val market = p.optString("market", "SPOT")
            val side = p.optString("side", "BUY")
            val id = p.optLong("id", 0L)
            if (entry <= 0.0 || qty <= 0.0) continue

            if (market == "SPOT") {
                val change = (price / entry - 1.0) * 100.0
                if (change <= -5.0) alerts += alertOnce("$id:SPOT_LOSS_5", "CẢNH BÁO SPOT • $symbol",
                    "Lệnh #$id giảm ${fmt(change)}% so với entry. Mốc thoát khẩn cấp -10% chỉ được xử lý khi app chính đang chạy.")
                val target = entry * (1.0 + 0.05 + 0.002)
                val progress = (price - entry) / (target - entry) * 100.0
                if (price >= entry && progress >= 80.0 && price < target) alerts += alertOnce("$id:SPOT_NEAR_TP",
                    "SPOT SẮP ĐẠT TAKE PROFIT • $symbol", "Lệnh #$id đã đi được ${fmt(progress)}% quãng đường tới mục tiêu mô phỏng.")
            } else {
                val signedMove = if (side == "SHORT") entry - price else price - entry
                val roi = if (margin > 0.0) signedMove * qty / margin * 100.0 else 0.0
                if (roi <= -30.0) alerts += alertOnce("$id:FUTURES_ROI_30", "CẢNH BÁO FUTURES • $symbol",
                    "Lệnh #$id ROI mô phỏng ${fmt(roi)}% trên margin (ngưỡng -30%).")
                if (roi <= -50.0) alerts += alertOnce("$id:FUTURES_ROI_50", "RỦI RO FUTURES CAO • $symbol",
                    "Lệnh #$id ROI mô phỏng ${fmt(roi)}% trên margin (ngưỡng -50%). Kiểm tra vị thế ngay.")
            }
        }
        updateStatus("Theo dõi ${positions.length()} vị thế • cập nhật mỗi 30 giây")
    }

    private fun alertOnce(key: String, title: String, message: String): Int {
        if (!fired.add(key)) return 0
        prefs.edit().putStringSet(KEY_FIRED, fired.toSet()).apply()
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText("$message\nGiá từ Binance Public API • PAPER TRADING"))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()
        val id = (key.hashCode() and 0x7fffffff)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        return 1
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Theo dõi EA nền", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Cảnh báo EA nền", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            })
        }
    }

    private fun buildStatus(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("TONY TRAN AI TRADING EA").setContentText(text)
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("TONY TRAN AI TRADING EA").setContentText(text)
                .setOngoing(true).build()
        }
    }

    private fun updateStatus(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(FOREGROUND_ID, buildStatus(text))
    }

    private fun fmt(value: Double) = String.format(java.util.Locale.US, "%.2f", value)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
