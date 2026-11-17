
package com.tonytran.aitrading

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class TradeNotificationManager(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "tony_trade_events"
        private const val CHANNEL_NAME = "Thông báo giao dịch"
    }

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo chi tiết khi EA mở hoặc đóng lệnh mô phỏng"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun show(event: TradeEvent) {
        val t = event.trade
        val isOpen = event.eventType == "OPEN"
        val market = if (t.market == MarketType.SPOT) "SPOT" else "FUTURES"
        val direction = when (t.side) {
            Side.BUY -> "BUY"
            Side.SELL -> "SELL"
            Side.LONG -> "LONG"
            Side.SHORT -> "SHORT"
        }
        val title = if (isOpen) "EA ĐÃ MỞ LỆNH • ${t.symbol}" else "EA ĐÃ ĐÓNG LỆNH • ${t.symbol}"
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(event.eventAt))
        val lines = mutableListOf<String>()
        lines += "Mã lệnh: #${t.id}"
        lines += "Thị trường: $market • Hướng: $direction"
        lines += "Khối lượng: ${fmt(t.quantity)} ${t.symbol.removeSuffix("USDT")}"
        lines += "Giá vào: ${fmt(t.entry)} USDT"
        if (isOpen) {
            lines += if (t.market == MarketType.SPOT) {
                "Giá trị vốn: ${fmt(t.margin)} USDT"
            } else {
                "Margin: ${fmt(t.margin)} USDT • Đòn bẩy: ${t.leverage}x Isolated"
            }
            lines += "Phí mở ước tính: ${fmt(t.fees)} USDT"
        } else {
            lines += "Giá đóng: ${fmt(t.exit ?: 0.0)} USDT"
            lines += "PnL ròng mô phỏng: ${if (t.pnl >= 0) "+" else ""}${fmt(t.pnl)} USDT"
            lines += "Tổng phí ước tính: ${fmt(t.fees)} USDT"
            lines += "Lý do đóng: ${t.note.ifBlank { "Không xác định" }}"
        }
        lines += "Thời gian: $time"
        lines += "PAPER TRADING • Không phải lệnh tiền thật"

        val text = lines.joinToString("\n")
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(lines.take(3).joinToString(" • "))
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(android.app.Notification.CATEGORY_STATUS)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        }
        runCatching { manager.notify((t.id * 10 + if (isOpen) 1 else 2).toInt(), notification) }
    }

    fun showRisk(event: TradeRiskEvent) {
        val t = event.trade
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(event.eventAt))
        val title: String
        val detail: String
        when (event.riskType) {
            "SPOT_LOSS_5" -> {
                title = "CẢNH BÁO SPOT • ${t.symbol}"
                detail = "Vị thế Spot đang giảm khoảng ${fmt(event.metricValue)}% so với giá vào. Mốc thoát khẩn cấp hiện cấu hình là -10%; đây là cảnh báo sớm -5%."
            }
            "SPOT_NEAR_TP" -> {
                title = "SPOT SẮP ĐẠT TAKE PROFIT • ${t.symbol}"
                detail = "Giá đã đi được khoảng ${fmt(event.metricValue)}% quãng đường tới mục tiêu Spot xấp xỉ +5% net."
            }
            "FUTURES_ROI_MINUS_30" -> {
                title = "CẢNH BÁO RỦI RO FUTURES • ${t.symbol}"
                detail = "ROI mô phỏng trên margin: ${fmt(event.metricValue)}%. Đã chạm ngưỡng cảnh báo -30%."
            }
            "FUTURES_ROI_MINUS_50" -> {
                title = "RỦI RO FUTURES CAO • ${t.symbol}"
                detail = "ROI mô phỏng trên margin: ${fmt(event.metricValue)}%. Đã chạm ngưỡng cảnh báo mạnh -50%. Cân nhắc kiểm tra vị thế ngay."
            }
            else -> {
                title = "CẢNH BÁO RỦI RO • ${t.symbol}"
                detail = "Đã chạm ngưỡng ${fmt(event.threshold)}."
            }
        }
        val market = if (t.market == MarketType.SPOT) "SPOT" else "FUTURES"
        val direction = t.side.name
        val pnl = if (t.market == MarketType.SPOT) {
            (event.currentPrice - t.entry) * t.quantity
        } else {
            val move = (event.currentPrice - t.entry) * t.quantity
            if (t.side == Side.SHORT) -move else move
        }
        val body = listOf(
            "Mã lệnh: #${t.id} • $market • $direction",
            "Giá vào: ${fmt(t.entry)} USDT",
            "Giá hiện tại: ${fmt(event.currentPrice)} USDT",
            "PnL gộp ước tính: ${if (pnl >= 0) "+" else ""}${fmt(pnl)} USDT",
            detail,
            "Thời gian: $time",
            "PAPER TRADING • Không phải lệnh tiền thật"
        ).joinToString("\n")
        val idSuffix = event.riskType.hashCode() and 0x7fffffff
        val notificationId = ((t.id * 1000L + (idSuffix % 997)) % Int.MAX_VALUE).toInt()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setCategory(android.app.Notification.CATEGORY_ERROR)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build()
        }
        runCatching { manager.notify(notificationId, notification) }
    }

    private fun fmt(value: Double): String =
        String.format(Locale.US, "%.8f", value)
}
