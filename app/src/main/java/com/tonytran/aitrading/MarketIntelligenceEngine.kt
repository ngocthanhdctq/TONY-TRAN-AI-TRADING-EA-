package com.tonytran.aitrading

import kotlin.math.abs

data class MarketIntelligenceItem(
    val symbol: String,
    val decision: String,
    val score: Int,
    val btcContext: String,
    val regime: MarketRegime,
    val fundingPct: Double?,
    val reasons: List<String>,
    val caution: String
)

data class MarketIntelligenceReport(
    val btcChangePct: Double?,
    val breadthPct: Double,
    val highVolatilityCount: Int,
    val fundingCoverage: Int,
    val items: List<MarketIntelligenceItem>,
    val generatedAt: Long
)

/**
 * Transparent multi-factor market context engine.
 * Heuristic scoring only; not a trained model or a probability forecast.
 */
object MarketIntelligenceEngine {
    fun analyze(coins: List<MarketCoin>, funding: FundingScanResult?): MarketIntelligenceReport {
        val btc = coins.firstOrNull { it.symbol == "BTCUSDT" }
        val btcChange = btc?.changePct
        val advancing = coins.count { it.changePct > 0.0 }
        val breadth = if (coins.isEmpty()) 0.0 else advancing * 100.0 / coins.size
        val volatileCount = coins.count { it.atrPct >= 2.5 || abs(it.changePct) >= 8.0 }
        val fundingBySymbol = funding?.markets.orEmpty().associateBy { it.symbol }

        val items = coins.take(40).map { coin ->
            val fundingMarket = fundingBySymbol[coin.symbol]
            val regime = when {
                coin.atrPct >= 3.5 -> MarketRegime.HIGH_VOLATILITY
                coin.trend1h == "BULLISH" -> MarketRegime.BULL_TREND
                coin.trend1h == "BEARISH" -> MarketRegime.BEAR_TREND
                coin.trend1h == "RANGE" -> MarketRegime.RANGE
                else -> MarketRegime.UNKNOWN
            }
            val reasons = mutableListOf<String>()
            var score = coin.score.coerceIn(0, 100)
            var veto = false

            val btcState = when {
                btcChange == null -> "BTC chưa có dữ liệu"
                btcChange >= 1.0 -> "BTC tăng mạnh (${fmt(btcChange)}%)"
                btcChange <= -1.0 -> "BTC giảm mạnh (${fmt(btcChange)}%)"
                else -> "BTC biến động hẹp (${fmt(btcChange)}%)"
            }
            if (btcChange != null) {
                if (coin.signal == "LONG" && btcChange <= -1.5) {
                    score -= 20; reasons += "BTC giảm mạnh, bất lợi cho vị thế LONG"
                } else if (coin.signal == "SHORT" && btcChange >= 1.5) {
                    score -= 20; reasons += "BTC tăng mạnh, bất lợi cho vị thế SHORT"
                } else {
                    score += 5; reasons += "Bối cảnh BTC không xung đột rõ với tín hiệu"
                }
            } else {
                score -= 10; reasons += "Thiếu dữ liệu BTC, giảm độ tin cậy"
            }

            if (coin.trend1h == "BULLISH" && coin.signal == "LONG") {
                score += 10; reasons += "Xu hướng 1h đồng thuận LONG"
            } else if (coin.trend1h == "BEARISH" && coin.signal == "SHORT") {
                score += 10; reasons += "Xu hướng 1h đồng thuận SHORT"
            } else if (coin.signal != "WAIT") {
                score -= 12; reasons += "Tín hiệu chưa đồng thuận với xu hướng 1h"
            }

            if (coin.quoteVolume < 5_000_000.0) {
                score -= 15; reasons += "Thanh khoản 24h thấp hơn ngưỡng tham chiếu 5 triệu USDT"
            } else if (coin.quoteVolume >= 50_000_000.0) {
                score += 5; reasons += "Thanh khoản 24h tương đối tốt"
            }

            if (coin.atrPct >= 3.5) {
                score -= 25; veto = true; reasons += "ATR% cao: tránh mở vị thế mới trong biến động mạnh"
            } else if (coin.atrPct >= 2.5) {
                score -= 12; reasons += "Biến động ATR% cao, cần giảm rủi ro"
            }

            if (fundingMarket != null) {
                val rate = fundingMarket.fundingRatePct
                if (abs(rate) >= 0.10) {
                    score -= 12; reasons += "Funding ${fmt(rate)}% rất lệch, chi phí/áp lực vị thế có thể cao"
                } else if (abs(rate) >= 0.05) {
                    score -= 5; reasons += "Funding đang lệch ${fmt(rate)}%"
                } else {
                    reasons += "Funding chưa lệch đáng kể (${fmt(rate)}%)"
                }
            } else {
                veto = true
                reasons += "Chưa có dữ liệu Funding cho cặp này: giữ WAIT"
            }

            if (coin.signal == "WAIT") reasons += "Bộ phân tích kỹ thuật gốc đang WAIT"
            val finalScore = score.coerceIn(0, 100)
            val decision = when {
                veto -> "WAIT"
                coin.signal == "WAIT" -> "WAIT"
                finalScore >= 75 -> coin.signal
                else -> "WAIT"
            }
            MarketIntelligenceItem(
                coin.symbol, decision, finalScore, btcState, regime,
                fundingMarket?.fundingRatePct, reasons,
                if (decision == "WAIT") "Chưa đủ điều kiện để mở lệnh theo bộ lọc hiện tại."
                else "Tín hiệu đủ điều kiện tham khảo; xác nhận nến đóng, spread và rủi ro trước PAPER."
            )
        }.sortedWith(compareByDescending<MarketIntelligenceItem> { it.score }.thenBy { it.symbol })

        return MarketIntelligenceReport(
            btcChange, breadth, volatileCount, fundingBySymbol.size, items, System.currentTimeMillis()
        )
    }

    private fun fmt(value: Double) = "%.3f".format(java.util.Locale.US, value)
}
