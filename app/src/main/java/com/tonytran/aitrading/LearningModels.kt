package com.tonytran.aitrading

enum class MarketRegime { BULL_TREND, BEAR_TREND, RANGE, HIGH_VOLATILITY, UNKNOWN }
enum class StrategyStyle { TREND_FOLLOWING, MEAN_REVERSION, BREAKOUT }

data class StrategyConfig(
    val name: String = "Baseline v1",
    val longScoreThreshold: Int = 70,
    val shortScoreThreshold: Int = 70,
    val rsiLongMin: Double = 50.0,
    val rsiLongMax: Double = 68.0,
    val rsiShortMin: Double = 32.0,
    val rsiShortMax: Double = 50.0,
    val volumeRatioMin: Double = 1.5,
    val atrPctMin: Double = 0.15,
    val atrPctMax: Double = 3.5,
    val feeRatePerSide: Double = 0.001,
    val maxValidationDrawdownPct: Double = 12.0
)

data class BacktestMetrics(
    val trades: Int,
    val winRatePct: Double,
    val netReturnPct: Double,
    val maxDrawdownPct: Double,
    val profitFactor: Double,
    val averageTradePct: Double
)

data class BacktestReport(
    val symbol: String,
    val interval: String,
    val baseline: BacktestMetrics,
    val candidate: BacktestMetrics,
    val promoted: Boolean,
    val reason: String,
    val testedAt: Long = System.currentTimeMillis()
)
