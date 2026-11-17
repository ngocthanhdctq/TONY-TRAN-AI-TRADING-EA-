package com.tonytran.aitrading

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Explainable, rule-based advisory analysis built from public Binance candles. */
data class AssistantTimeframeView(
    val timeframe: String,
    val close: Double,
    val ema20: Double,
    val ema50: Double,
    val rsi: Double,
    val atr: Double,
    val volumeRatio: Double,
    val trend: String
)

data class AssistantMarketReport(
    val symbol: String,
    val generatedAt: Long,
    val currentPrice: Double,
    val decision: String,
    val score: Int,
    val trendSummary: String,
    val timeframeViews: List<AssistantTimeframeView>,
    val support: Double?,
    val resistance: Double?,
    val entryReference: Double?,
    val stopLossReference: Double?,
    val takeProfitReference: Double?,
    val riskReward: Double?,
    val reasons: List<String>,
    val cautions: List<String>
)

object MarketAssistantAnalysis {
    fun analyze(
        ticker: MarketCoin,
        candles5m: List<Candle>,
        candles15m: List<Candle>,
        candles1h: List<Candle>,
        candles4h: List<Candle>
    ): AssistantMarketReport {
        val inputs = listOf("5m" to candles5m, "15m" to candles15m, "1h" to candles1h, "4h" to candles4h)
        val views = inputs.mapNotNull { (tf, candles) ->
            val s = Indicators.snapshot(candles) ?: return@mapNotNull null
            AssistantTimeframeView(
                tf, s.close, s.ema20, s.ema50, s.rsi, s.atr, s.volumeRatio,
                when {
                    s.close > s.ema20 && s.ema20 > s.ema50 -> "TĂNG"
                    s.close < s.ema20 && s.ema20 < s.ema50 -> "GIẢM"
                    else -> "TRUNG LẬP"
                }
            )
        }
        val reasons = mutableListOf<String>()
        val cautions = mutableListOf<String>()
        if (views.size < 4) cautions += "Thiếu dữ liệu nến ở một hoặc nhiều khung thời gian; cần ít nhất 60 nến mỗi khung."

        var bullish = 0
        var bearish = 0
        views.forEach { view ->
            val weight = when (view.timeframe) { "5m" -> 1; "15m" -> 2; "1h" -> 3; else -> 4 }
            when (view.trend) {
                "TĂNG" -> bullish += weight
                "GIẢM" -> bearish += weight
            }
            reasons += "${view.timeframe}: ${view.trend}, RSI ${fmt(view.rsi)}, EMA20 ${fmtPrice(view.ema20)}, EMA50 ${fmtPrice(view.ema50)}"
        }
        val total = bullish + bearish
        val directional = if (total == 0) 0 else (abs(bullish - bearish) * 100 / total).coerceIn(0, 100)
        val aligned = views.size == 4 && (bullish == total || bearish == total)
        val decision = when {
            views.size < 4 -> "WAIT"
            aligned && bullish >= 8 -> "BUY"
            aligned && bearish >= 8 -> "SELL"
            else -> "WAIT"
        }
        if (aligned) reasons += "Cả 4 khung thời gian đang đồng thuận ${if (bullish > bearish) "tăng" else "giảm"}."
        else reasons += "Các khung thời gian chưa đồng thuận hoàn toàn; ưu tiên chờ xác nhận."

        val sourceCandles = candles15m.takeLast(48)
        val support = sourceCandles.takeIf { it.size >= 10 }?.minOf { it.low }
        val resistance = sourceCandles.takeIf { it.size >= 10 }?.maxOf { it.high }
        val atr = Indicators.snapshot(candles15m)?.atr ?: 0.0
        val entry = ticker.price.takeIf { it > 0.0 }
        val stop = if (decision == "BUY" && atr > 0 && entry != null) entry - 1.5 * atr
            else if (decision == "SELL" && atr > 0 && entry != null) entry + 1.5 * atr else null
        val risk = if (entry != null && stop != null) abs(entry - stop) else 0.0
        val target = when {
            decision == "BUY" && entry != null && risk > 0 -> entry + 2.0 * risk
            decision == "SELL" && entry != null && risk > 0 -> entry - 2.0 * risk
            else -> null
        }
        if (decision == "WAIT") cautions += "Không tạo mức Entry/SL/TP đề xuất khi tín hiệu chưa đủ điều kiện."
        if (views.any { it.rsi >= 70.0 || it.rsi <= 30.0 }) cautions += "RSI ở vùng cực trị trên ít nhất một khung; tránh đuổi giá."
        if (views.any { it.atr > 0 && it.close > 0 && it.atr / it.close * 100 >= 3.5 }) cautions += "Biến động ATR cao; cân nhắc giảm quy mô hoặc đứng ngoài."
        if (ticker.quoteVolume < 5_000_000.0) cautions += "Khối lượng giao dịch 24h thấp hơn ngưỡng tham chiếu 5 triệu USDT."
        val rr = if (risk > 0 && target != null) abs(target - entry!!) / risk else null
        return AssistantMarketReport(
            ticker.symbol, System.currentTimeMillis(), ticker.price, decision, directional,
            when { bullish > bearish -> "Thiên hướng tăng"; bearish > bullish -> "Thiên hướng giảm"; else -> "Chưa rõ xu hướng" },
            views, support, resistance, if (decision == "WAIT") null else entry, stop, target, rr,
            reasons, cautions
        )
    }

    fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)
    fun fmtPrice(value: Double): String = String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
}
