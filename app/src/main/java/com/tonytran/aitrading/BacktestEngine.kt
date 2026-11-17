package com.tonytran.aitrading

import kotlin.math.max
import kotlin.math.min

object BacktestEngine {
    private data class TradeResult(val netPct: Double)

    fun run(candles: List<Candle>, config: StrategyConfig): BacktestMetrics {
        if (candles.size < 100) return BacktestMetrics(0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val closeValues = candles.map { it.close }
        var inPosition = false
        var entry = 0.0
        val results = mutableListOf<TradeResult>()
        var equity = 1.0
        var peak = 1.0
        var maxDd = 0.0

        // Indicators at candle i use only data up to i. Signal executes at next candle open.
        for (i in 60 until candles.lastIndex) {
            val window = candles.subList(0, i + 1)
            val snap = Indicators.snapshot(window) ?: continue
            val regime = MarketRegimeDetector.detect(window)
            val trendLong = snap.close > snap.ema20 && snap.ema20 > snap.ema50
            val momentumOk = snap.rsi in config.rsiLongMin..config.rsiLongMax
            val volumeOk = snap.volumeRatio >= config.volumeRatioMin
            val atrPct = if (snap.close > 0) snap.atr / snap.close * 100.0 else 0.0
            val volatilityOk = atrPct in config.atrPctMin..config.atrPctMax
            val longSignal = regime == MarketRegime.BULL_TREND && trendLong && momentumOk && volumeOk && volatilityOk

            val nextOpen = candles[i + 1].open
            if (!inPosition && longSignal) {
                entry = nextOpen
                inPosition = true
            } else if (inPosition && (!trendLong || snap.rsi > 72.0 || regime == MarketRegime.HIGH_VOLATILITY)) {
                val exit = nextOpen
                val net = ((exit / entry) - 1.0) * 100.0 - 2.0 * config.feeRatePerSide * 100.0
                results += TradeResult(net)
                equity *= (1.0 + net / 100.0)
                peak = max(peak, equity)
                maxDd = max(maxDd, (peak - equity) / peak * 100.0)
                inPosition = false
            }
        }
        if (inPosition) {
            val exit = candles.last().close
            val net = ((exit / entry) - 1.0) * 100.0 - 2.0 * config.feeRatePerSide * 100.0
            results += TradeResult(net)
            equity *= (1.0 + net / 100.0)
            peak = max(peak, equity)
            maxDd = max(maxDd, (peak - equity) / peak * 100.0)
        }
        val wins = results.count { it.netPct > 0 }
        val grossProfit = results.filter { it.netPct > 0 }.sumOf { it.netPct }
        val grossLoss = -results.filter { it.netPct < 0 }.sumOf { it.netPct }
        val totalReturn = (equity - 1.0) * 100.0
        return BacktestMetrics(
            trades = results.size,
            winRatePct = if (results.isEmpty()) 0.0 else wins * 100.0 / results.size,
            netReturnPct = totalReturn,
            maxDrawdownPct = maxDd,
            profitFactor = if (grossLoss == 0.0) if (grossProfit > 0) 99.0 else 0.0 else grossProfit / grossLoss,
            averageTradePct = results.map { it.netPct }.average().let { if (it.isNaN()) 0.0 else it }
        )
    }

    fun validateAndPromote(
        candles: List<Candle>,
        baseline: StrategyConfig,
        candidate: StrategyConfig
    ): BacktestReport {
        val split = (candles.size * 0.70).toInt().coerceIn(60, candles.size)
        val validation = candles.drop(split)
        val baseMetrics = run(validation, baseline)
        val candidateMetrics = run(validation, candidate)
        val enoughTrades = candidateMetrics.trades >= 5
        val drawdownOk = candidateMetrics.maxDrawdownPct <= candidate.maxValidationDrawdownPct
        val returnImproved = candidateMetrics.netReturnPct > baseMetrics.netReturnPct
        val promoted = enoughTrades && drawdownOk && returnImproved
        val reason = when {
            !enoughTrades -> "Không đủ 5 giao dịch kiểm chứng; giữ cấu hình cũ."
            !drawdownOk -> "Drawdown vượt ngưỡng; từ chối cấu hình mới."
            !returnImproved -> "Hiệu quả ngoài mẫu không tốt hơn baseline; giữ cấu hình cũ."
            else -> "Đạt điều kiện ngoài mẫu; có thể đề xuất cấu hình mới."
        }
        return BacktestReport("LOCAL_DATASET", "selected", baseMetrics, candidateMetrics, promoted, reason)
    }
}
