package com.tonytran.aitrading

data class MarketCoin(
    val symbol: String,
    val price: Double,
    val changePct: Double,
    val quoteVolume: Double,
    val score: Int = 0,
    val signal: String = "WAIT",
    val rsi: Double = 50.0,
    val ema20: Double = 0.0,
    val ema50: Double = 0.0,
    val macd: Double = 0.0,
    val atrPct: Double = 0.0,
    val volumeRatio: Double = 0.0,
    val trend1h: String = "UNKNOWN",
    val analysis: String = "Chưa phân tích"
)

enum class MarketType { SPOT, FUTURES }
enum class Side { BUY, SELL, LONG, SHORT }
enum class TradeStatus { OPEN, CLOSED }

data class PaperTrade(
    val id: Long,
    val symbol: String,
    val market: MarketType,
    val side: Side,
    val entry: Double,
    val quantity: Double,
    val margin: Double,
    val leverage: Int,
    val openedAt: Long,
    val status: TradeStatus = TradeStatus.OPEN,
    val exit: Double? = null,
    val pnl: Double = 0.0,
    val fees: Double = 0.0,
    val note: String = "",
    val closedAt: Long? = null
)

data class Wallet(
    val spot: Double = 5.0,
    val futures: Double = 2.0,
    val reserve: Double = 3.0
) {
    val total: Double get() = spot + futures + reserve
}
