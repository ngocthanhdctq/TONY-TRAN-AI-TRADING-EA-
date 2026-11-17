package com.tonytran.aitrading

object AdaptiveSignalEngine {
    fun analyze(
        ticker: MarketCoin,
        m5: TechnicalSnapshot?,
        m15: TechnicalSnapshot?,
        h1: TechnicalSnapshot?,
        config: StrategyConfig
    ): MarketCoin {
        val base = SignalEngine.analyze(ticker, m5, m15, h1)
        if (m5 == null || m15 == null || h1 == null) return base
        val regime = MarketRegimeDetector.detectFromSnapshot(h1)
        val notes = mutableListOf(base.analysis, "Regime: $regime")
        val signal = when {
            regime == MarketRegime.HIGH_VOLATILITY -> "WAIT"
            regime == MarketRegime.RANGE -> "WAIT"
            base.signal == "LONG" && base.score >= config.longScoreThreshold &&
                m15.rsi in config.rsiLongMin..config.rsiLongMax &&
                m5.volumeRatio >= config.volumeRatioMin -> "LONG"
            base.signal == "SHORT" && base.score >= config.shortScoreThreshold &&
                m15.rsi in config.rsiShortMin..config.rsiShortMax &&
                m5.volumeRatio >= config.volumeRatioMin -> "SHORT"
            else -> "WAIT"
        }
        return base.copy(signal = signal, analysis = notes.filter { it.isNotBlank() }.joinToString(" • "))
    }
}
