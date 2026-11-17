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
    val winRatePct: Double
        get() = if (wins + losses == 0) 0.0 else wins * 100.0 / (wins + losses)
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
    // Matches the default starting wallet: 5 SPOT + 2 FUTURES + 3 reserve.
    const val INITIAL_CAPITAL_USDT = 10.0

    private const val SPOT_FEE_RATE = 0.001
    private const val FUTURES_FEE_RATE = 0.0005

    fun calculate(
        wallet: Wallet,
        trades: List<PaperTrade>,
        prices: Map<String, Double>
    ): PaperPerformanceReport {
        val closed = trades
            .filter { it.status == TradeStatus.CLOSED }
            .sortedBy { it.closedAt ?: it.openedAt }
        val open = trades.filter { it.status == TradeStatus.OPEN }

        // PaperTrade.pnl is already net of both entry and exit fees when closed.
        val wins = closed.count { it.pnl > 0.0 }
        val losses = closed.count { it.pnl < 0.0 }
        val grossProfit = closed.filter { it.pnl > 0.0 }.sumOf { it.pnl }
        val grossLoss = abs(closed.filter { it.pnl < 0.0 }.sumOf { it.pnl })
        val realized = closed.sumOf { it.pnl }

        var unrealized = 0.0
        var openPositionEquity = 0.0

        open.forEach { trade ->
            val marketPrice = prices[trade.symbol]
                ?.takeIf { it.isFinite() && it > 0.0 }

            // If a ticker is temporarily unavailable, mark at entry price rather
            // than silently dropping the position value from account equity.
            val markPrice = marketPrice ?: trade.entry
            val grossMove = when (trade.market) {
                MarketType.SPOT -> (markPrice - trade.entry) * trade.quantity
                MarketType.FUTURES -> {
                    val move = (markPrice - trade.entry) * trade.quantity
                    if (trade.side == Side.SHORT) -move else move
                }
            }

            val exitNotional = markPrice * trade.quantity
            val exitFeeRate = if (trade.market == MarketType.SPOT) {
                SPOT_FEE_RATE
            } else {
                FUTURES_FEE_RATE
            }
            val estimatedExitFee = exitNotional * exitFeeRate
            val estimatedNetPnl = grossMove - trade.fees - estimatedExitFee
            unrealized += estimatedNetPnl

            // SPOT: entry fee was included in the amount debited from the wallet
            // and quantity represents the net asset acquired. Deduct the estimated
            // exit fee from its marked sale value.
            // FUTURES: margin was removed from wallet; add back margin plus net PnL.
            openPositionEquity += when (trade.market) {
                MarketType.SPOT -> exitNotional - estimatedExitFee
                MarketType.FUTURES -> trade.margin + estimatedNetPnl
            }
        }

        // Wallet balances exclude capital tied up in open positions.
        val equity = wallet.spot + wallet.futures + wallet.reserve + openPositionEquity

        // Build the realized-equity curve in close-time order, then append the
        // current marked equity so open-position drawdown is included as well.
        val equityCurve = mutableListOf(INITIAL_CAPITAL_USDT)
        var runningEquity = INITIAL_CAPITAL_USDT
        closed.forEach { trade ->
            runningEquity += trade.pnl
            equityCurve += runningEquity
        }
        equityCurve += equity

        var peak = equityCurve.firstOrNull() ?: INITIAL_CAPITAL_USDT
        var maxDrawdown = 0.0
        equityCurve.forEach { value ->
            peak = max(peak, value)
            if (peak > 0.0) {
                maxDrawdown = max(maxDrawdown, (peak - value) / peak * 100.0)
            }
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
            winRatePct = if (wins + losses == 0) {
                0.0
            } else {
                wins * 100.0 / (wins + losses)
            },
            grossProfit = grossProfit,
            grossLoss = grossLoss,
            // Null means no losing trades yet; the UI should display "—" or "N/A".
            profitFactor = if (grossLoss > 0.0) grossProfit / grossLoss else null,
            maxDrawdownPct = maxDrawdown,
            openPositions = open.size,
            marketBreakdown = breakdown
        )
    }
}
