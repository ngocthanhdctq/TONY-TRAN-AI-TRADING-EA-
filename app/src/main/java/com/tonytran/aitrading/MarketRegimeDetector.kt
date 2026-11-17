package com.tonytran.aitrading

import kotlin.math.abs

object MarketRegimeDetector {
    fun detect(candles: List<Candle>): MarketRegime {
        val snapshot = Indicators.snapshot(candles) ?: return MarketRegime.UNKNOWN
        val recent = candles.takeLast(12)
        val rangePct = if (recent.isNotEmpty() && snapshot.close > 0)
            (recent.maxOf { it.high } - recent.minOf { it.low }) / snapshot.close * 100.0 else 0.0
        return classify(snapshot, rangePct)
    }

    fun detectFromSnapshot(snapshot: TechnicalSnapshot): MarketRegime = classify(snapshot, 0.0)

    private fun classify(snapshot: TechnicalSnapshot, rangePct: Double): MarketRegime {
        val atrPct = if (snapshot.close > 0) snapshot.atr / snapshot.close * 100.0 else 0.0
        val spread = if (snapshot.ema50 > 0)
            (snapshot.ema20 - snapshot.ema50) / snapshot.ema50 * 100.0 else 0.0
        if (atrPct >= 3.5 || rangePct >= 8.0) return MarketRegime.HIGH_VOLATILITY
        if (spread > 0.12 && snapshot.close > snapshot.ema20) return MarketRegime.BULL_TREND
        if (spread < -0.12 && snapshot.close < snapshot.ema20) return MarketRegime.BEAR_TREND
        if (abs(spread) <= 0.12) return MarketRegime.RANGE
        return MarketRegime.UNKNOWN
    }
}
