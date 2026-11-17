package com.tonytran.aitrading

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class TechnicalSnapshot(
    val close: Double,
    val ema20: Double,
    val ema50: Double,
    val rsi: Double,
    val macd: Double,
    val atr: Double,
    val volumeRatio: Double
)

object Indicators {
    fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        var result = values.take(period).average()
        for (i in period until values.size) result = values[i] * k + result * (1.0 - k)
        return result
    }

    fun rsi(closes: List<Double>, period: Int = 14): Double {
        if (closes.size <= period) return 50.0
        var gains = 0.0
        var losses = 0.0
        for (i in closes.size - period until closes.size) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) gains += change else losses -= change
        }
        if (losses == 0.0) return 100.0
        val rs = (gains / period) / (losses / period)
        return 100.0 - 100.0 / (1.0 + rs)
    }

    fun atr(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size <= period) return 0.0
        val ranges = (1 until candles.size).map { i ->
            val c = candles[i]
            max(c.high - c.low, max(abs(c.high - candles[i-1].close), abs(c.low - candles[i-1].close)))
        }
        return ranges.takeLast(period).average()
    }

    fun snapshot(candles: List<Candle>): TechnicalSnapshot? {
        if (candles.size < 60) return null
        val closes = candles.map { it.close }
        val fast = ema(closes, 12)
        val slow = ema(closes, 26)
        val macd = fast - slow
        val previousVolumes = candles.dropLast(1).takeLast(20).map { it.volume }
        val avgVol = previousVolumes.average().takeIf { it > 0 } ?: 1.0
        return TechnicalSnapshot(
            close = closes.last(),
            ema20 = ema(closes, 20),
            ema50 = ema(closes, 50),
            rsi = rsi(closes),
            macd = macd,
            atr = atr(candles),
            volumeRatio = candles.last().volume / avgVol
        )
    }
}

/**
 * Transparent rule-based multi-timeframe decision engine.
 * This is an explainable quantitative baseline, not a trained neural model.
 */
object SignalEngine {
    fun analyze(
        ticker: MarketCoin,
        m5: TechnicalSnapshot?,
        m15: TechnicalSnapshot?,
        h1: TechnicalSnapshot?
    ): MarketCoin {
        if (m5 == null || m15 == null || h1 == null) {
            return ticker.copy(score = 0, signal = "WAIT", analysis = "Thiếu dữ liệu nến")
        }

        var long = 0
        var short = 0
        val reasons = mutableListOf<String>()

        fun trend(s: TechnicalSnapshot, label: String, weight: Int) {
            when {
                s.ema20 > s.ema50 && s.close > s.ema20 -> {
                    long += weight; reasons += "$label tăng"
                }
                s.ema20 < s.ema50 && s.close < s.ema20 -> {
                    short += weight; reasons += "$label giảm"
                }
            }
        }
        trend(m5, "5m", 10)
        trend(m15, "15m", 20)
        trend(h1, "1h", 30)

        if (m15.rsi in 50.0..68.0) { long += 10; reasons += "RSI 15m thuận LONG" }
        if (m15.rsi in 32.0..50.0) { short += 10; reasons += "RSI 15m thuận SHORT" }
        if (m15.rsi > 75 || m15.rsi < 25) reasons += "RSI cực trị: thận trọng"

        if (m15.macd > 0) long += 8 else short += 8
        if (m5.volumeRatio >= 1.5) {
            long += if (m5.close >= m5.ema20) 8 else 0
            short += if (m5.close < m5.ema20) 8 else 0
            reasons += "Volume tăng ${"%.1f".format(m5.volumeRatio)}x"
        } else reasons += "Volume chưa xác nhận"

        val atrPct = if (m15.close > 0) m15.atr / m15.close * 100.0 else 0.0
        if (atrPct in 0.15..3.5) {
            long += 4; short += 4
        } else reasons += "Biến động ngoài vùng lọc"

        val best = max(long, short).coerceIn(0, 100)
        val aligned = (long >= 60 && long > short + 12) || (short >= 60 && short > long + 12)
        val signal = when {
            aligned && long > short -> "LONG"
            aligned && short > long -> "SHORT"
            else -> "WAIT"
        }
        val score = if (signal == "WAIT") best.coerceAtMost(69) else best
        return ticker.copy(
            score = score,
            signal = signal,
            rsi = m15.rsi,
            ema20 = m15.ema20,
            ema50 = m15.ema50,
            macd = m15.macd,
            atrPct = atrPct,
            volumeRatio = m5.volumeRatio,
            trend1h = if (h1.ema20 >= h1.ema50) "BULLISH" else "BEARISH",
            analysis = reasons.distinct().joinToString(" • ")
        )
    }
}
