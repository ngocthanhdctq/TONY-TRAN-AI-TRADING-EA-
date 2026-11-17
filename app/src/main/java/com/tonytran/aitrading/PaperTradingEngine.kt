package com.tonytran.aitrading

import kotlin.math.abs

/**
 * Local paper ledger. All fills are simulated at the latest displayed ticker.
 * Fee assumptions are configurable placeholders, not a statement of the
 * user's actual Binance fee tier.
 */
data class TradeEvent(
    val trade: PaperTrade,
    val eventType: String, // OPEN or CLOSE
    val eventAt: Long = System.currentTimeMillis()
)

data class TradeRiskEvent(
    val trade: PaperTrade,
    val riskType: String,
    val currentPrice: Double,
    val metricValue: Double,
    val threshold: Double,
    val eventAt: Long = System.currentTimeMillis()
)

data class PaperLedgerSnapshot(
    val wallet: Wallet,
    val trades: List<PaperTrade>,
    val nextId: Long
)

class PaperTradingEngine(
    initialSnapshot: PaperLedgerSnapshot? = null,
    private val onLedgerChanged: ((PaperLedgerSnapshot) -> Unit)? = null
) {
    var wallet = initialSnapshot?.wallet ?: Wallet()
        private set
    private val trades = (initialSnapshot?.trades ?: emptyList()).toMutableList()
    private val pendingEvents = mutableListOf<TradeEvent>()
    private val pendingRiskEvents = mutableListOf<TradeRiskEvent>()
    private val firedRiskKeys = mutableSetOf<String>()

    fun drainRiskEvents(): List<TradeRiskEvent> {
        val events = pendingRiskEvents.toList()
        pendingRiskEvents.clear()
        return events
    }

    fun drainEvents(): List<TradeEvent> {
        val events = pendingEvents.toList()
        pendingEvents.clear()
        return events
    }
    private var nextId = maxOf(initialSnapshot?.nextId ?: 1L, (trades.maxOfOrNull { it.id } ?: 0L) + 1L)
    private val spotFeeRate = 0.001
    private val futuresFeeRate = 0.0005

    fun history(): List<PaperTrade> = trades.toList()
    fun openTrades(): List<PaperTrade> = trades.filter { it.status == TradeStatus.OPEN }
    fun snapshot(): PaperLedgerSnapshot = PaperLedgerSnapshot(wallet, trades.toList(), nextId)

    private fun persistLedger() {
        // Keep a bounded local ledger while retaining every open position.
        val openIds = openTrades().map { it.id }.toSet()
        val closed = trades.filter { it.status == TradeStatus.CLOSED }.takeLast(500)
        val retained = trades.filter { it.id in openIds } + closed
        trades.clear()
        trades.addAll(retained.sortedBy { it.id })
        onLedgerChanged?.invoke(snapshot())
    }

    fun openSpot(symbol: String, price: Double, amountUsdt: Double): String {
        if (!price.isFinite() || price <= 0.0) return "Giá vào lệnh không hợp lệ."
        if (!isValidSymbol(symbol)) return "Mã giao dịch không hợp lệ."
        val guard = SafetyCore.checkSpot(amountUsdt, wallet.spot, openTrades().size)
        if (!guard.allowed) return guard.reason
        val fee = amountUsdt * spotFeeRate
        val qty = (amountUsdt - fee) / price
        wallet = wallet.copy(spot = wallet.spot - amountUsdt)
        val opened = PaperTrade(nextId++, symbol, MarketType.SPOT, Side.BUY, price,
            qty, amountUsdt, 1, System.currentTimeMillis(), fees = fee)
        trades += opened
        pendingEvents += TradeEvent(opened, "OPEN")
        persistLedger()
        return "Đã mở lệnh Spot mô phỏng $symbol."
    }

    fun openFutures(symbol: String, price: Double, margin: Double, side: Side): String {
        if (!price.isFinite() || price <= 0.0) return "Giá vào lệnh không hợp lệ."
        if (!isValidSymbol(symbol)) return "Mã giao dịch không hợp lệ."
        if (side != Side.LONG && side != Side.SHORT) return "Chọn LONG hoặc SHORT."
        val leverage = 10
        val guard = SafetyCore.checkFutures(margin, leverage, wallet.futures, openTrades().size)
        if (!guard.allowed) return guard.reason
        val notional = margin * leverage
        val fee = notional * futuresFeeRate
        if (fee >= margin) return "Phí giả định vượt quá margin."
        val qty = notional / price
        wallet = wallet.copy(futures = wallet.futures - margin)
        val opened = PaperTrade(nextId++, symbol, MarketType.FUTURES, side, price,
            qty, margin, leverage, System.currentTimeMillis(), fees = fee)
        trades += opened
        pendingEvents += TradeEvent(opened, "OPEN")
        persistLedger()
        return "Đã mở lệnh Futures $side $symbol (Isolated 10x) mô phỏng."
    }

    fun close(id: Long, price: Double, note: String = "Đóng thủ công"): String {
        if (!price.isFinite() || price <= 0.0) return "Giá đóng lệnh không hợp lệ."
        val index = trades.indexOfFirst { it.id == id && it.status == TradeStatus.OPEN }
        if (index < 0) return "Không tìm thấy lệnh đang mở."
        val t = trades[index]
        val gross = when (t.market) {
            MarketType.SPOT -> (price - t.entry) * t.quantity
            MarketType.FUTURES -> {
                val move = (price - t.entry) * t.quantity
                if (t.side == Side.SHORT) -move else move
            }
        }
        val exitNotional = price * t.quantity
        val exitFee = exitNotional * if (t.market == MarketType.SPOT) spotFeeRate else futuresFeeRate
        val net = gross - exitFee - t.fees
        val updated = t.copy(status = TradeStatus.CLOSED, exit = price, pnl = net,
            fees = t.fees + exitFee, note = note, closedAt = System.currentTimeMillis())
        trades[index] = updated
        pendingEvents += TradeEvent(updated, "CLOSE")
        wallet = when (t.market) {
            MarketType.SPOT -> wallet.copy(spot = wallet.spot + (exitNotional - exitFee))
            MarketType.FUTURES -> wallet.copy(futures = wallet.futures + t.margin + net)
        }
        persistLedger()
        return "Đã đóng lệnh #$id. PnL mô phỏng: %.4f USDT".format(net)
    }

    fun monitor(prices: Map<String, Double>) {
        openTrades().forEach { t ->
            val price = prices[t.symbol] ?: return@forEach
            if (t.market == MarketType.SPOT && price <= t.entry * 0.90) {
                close(t.id, price, "Quy tắc thoát Spot -10%")
            } else if (t.market == MarketType.SPOT) {
                val netTarget = t.entry * (1.0 + 0.05 + 2 * spotFeeRate)
                val lossPct = (price / t.entry) - 1.0
                if (lossPct <= -0.05) emitRiskOnce(t, "SPOT_LOSS_5", price, lossPct * 100.0, -5.0)
                val nearTargetPrice = t.entry + (netTarget - t.entry) * 0.80
                if (price >= nearTargetPrice && price < netTarget) {
                    val progress = ((price - t.entry) / (netTarget - t.entry)) * 100.0
                    emitRiskOnce(t, "SPOT_NEAR_TP", price, progress, 80.0)
                }
                if (price >= netTarget) close(t.id, price, "Mục tiêu Spot xấp xỉ +5% net")
            } else {
                val signedMove = if (t.side == Side.SHORT) t.entry - price else price - t.entry
                val roi = signedMove * t.quantity / t.margin
                if (roi <= -0.30) emitRiskOnce(t, "FUTURES_ROI_MINUS_30", price, roi * 100.0, -30.0)
                if (roi <= -0.50) emitRiskOnce(t, "FUTURES_ROI_MINUS_50", price, roi * 100.0, -50.0)
                // User target: +50% ROI on allocated margin. Fees/funding are estimates.
                if (roi >= 0.50) close(t.id, price, "Mục tiêu Futures +50% ROI trước điều chỉnh funding")
                // Risk alerts are ROI-based approximations, not exchange liquidation-price calculations.
            }
        }
    }

    private fun isValidSymbol(symbol: String): Boolean =
        symbol.length in 3..24 && symbol.all { it in 'A'..'Z' || it in '0'..'9' }

    private fun emitRiskOnce(
        trade: PaperTrade,
        riskType: String,
        price: Double,
        metricValue: Double,
        threshold: Double
    ) {
        val key = "${trade.id}:$riskType"
        if (firedRiskKeys.add(key)) {
            pendingRiskEvents += TradeRiskEvent(trade, riskType, price, metricValue, threshold)
        }
    }

    fun reset() {
        wallet = Wallet()
        trades.clear()
        pendingEvents.clear()
        pendingRiskEvents.clear()
        firedRiskKeys.clear()
        nextId = 1L
        persistLedger()
    }
}
