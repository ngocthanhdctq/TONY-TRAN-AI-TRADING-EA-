package com.tonytran.aitrading

import kotlin.math.abs
import kotlin.math.max

data class MarketPerformance(
    val market: MarketType,
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val realizedPnl: Double
) {
    val winRatePct: Double get() = if (wins + losses == 0) 0.0 else wins * 100.0 / (wins + losses)
}

data class PaperPerformanceReport(
    val initialCapital: Double,
    val realizedPnl: Double,
    val unrealizedPnl: Double,
    val estimatedEquity: Double,
    val closedTrades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val grossProfit: Double,
    val grossLoss: Double,
    val profitFactor: Double?,
    val maxDrawdownPct: Double,
    val openPositions: Int,
    val marketBreakdown: List<MarketPerformance>
)

object PaperAnalytics {
    const val INITIAL_CAPITAL_USDT = 10.0

    fun calculate(
        wallet: Wallet,
        trades: List<PaperTrade>,
        prices: Map<String, Double>
    ): PaperPerformanceReport {
        val closed = trades.filter { it.status == TradeStatus.CLOSED }
        val open = trades.filter { it.status == TradeStatus.OPEN }

        val wins = closed.count { it.pnl > 0.0 }
        val losses = closed.count { it.pnl < 0.0 }
        val grossProfit = closed.filter { it.pnl > 0.0 }.sumOf { it.pnl }
        val grossLoss = abs(closed.filter { it.pnl < 0.0 }.sumOf { it.pnl })
        val realized = closed.sumOf { it.pnl }

        var unrealized = 0.0
        var openMarkedValue = 0.0
        open.forEach { trade ->
            val price = prices[trade.symbol]?.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
            val grossMove = when (trade.market) {
                MarketType.SPOT -> (price - trade.entry) * trade.quantity
                MarketType.FUTURES -> {
                    val move = (price - trade.entry) * trade.quantity
                    if (trade.side == Side.SHORT) -move else move
                }
            }
            val exitNotional = price * trade.quantity
            val estimatedExitFee = exitNotional * if (trade.market == MarketType.SPOT) 0.001 else 0.0005
            unrealized += grossMove - trade.fees - estimatedExitFee
            openMarkedValue += when (trade.market) {
                MarketType.SPOT -> exitNotional
                MarketType.FUTURES -> trade.margin + grossMove - trade.fees - estimatedExitFee
            }
        }

        // Equity is marked from the currently available wallet plus open-position value.
        // Open positions without a current ticker are not marked and are disclosed in UI.
        val equity = wallet.spot + wallet.futures + wallet.reserve + openMarkedValue
        val equityCurve = mutableListOf(INITIAL_CAPITAL_USDT)
        var runningEquity = INITIAL_CAPITAL_USDT
        closed.sortedBy { it.closedAt ?: it.openedAt }.forEach {
            runningEquity += it.pnl
            equityCurve += runningEquity
        }
        if (open.isNotEmpty()) equityCurve += equity
        var peak = equityCurve.firstOrNull() ?: INITIAL_CAPITAL_USDT
        var maxDd = 0.0
        equityCurve.forEach { value ->
            peak = max(peak, value)
            if (peak > 0.0) maxDd = max(maxDd, (peak - value) / peak * 100.0)
        }

        val breakdown = MarketType.values().map { market ->
            val group = closed.filter { it.market == market }
            MarketPerformance(
                market = market,
                trades = group.size,
                wins = group.count { it.pnl > 0.0 },
                losses = group.count { it.pnl < 0.0 },
                realizedPnl = group.sumOf { it.pnl }
            )
        }

        return PaperPerformanceReport(
            initialCapital = INITIAL_CAPITAL_USDT,
            realizedPnl = realized,
            unrealizedPnl = unrealized,
            estimatedEquity = equity,
            closedTrades = closed.size,
            wins = wins,
            losses = losses,
            winRatePct = if (wins + losses == 0) 0.0 else wins * 100.0 / (wins + losses),
            grossProfit = grossProfit,
            grossLoss = grossLoss,
            profitFactor = if (grossLoss > 0.0) grossProfit / grossLoss else null,
            maxDrawdownPct = maxDd,
            openPositions = open.size,
            marketBreakdown = breakdown
        )
    }
}
