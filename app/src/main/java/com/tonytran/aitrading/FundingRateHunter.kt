package com.tonytran.aitrading

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class FundingMarket(
    val symbol: String,
    val fundingRatePct: Double,
    val nextFundingTime: Long,
    val markPrice: Double,
    val quoteVolume: Double,
    val changePct24h: Double,
    val spreadPct: Double
) {
    val side: Side get() = if (fundingRatePct >= 0.0) Side.SHORT else Side.LONG
    val absFundingPct: Double get() = abs(fundingRatePct)
}

data class FundingScanResult(
    val markets: List<FundingMarket>,
    val candidates: List<FundingMarket>,
    // Estimated Binance server timestamp at scan completion.
    val scannedAt: Long,
    val localScannedAt: Long = System.currentTimeMillis(),
    val serverOffsetMs: Long = 0L,
    val serverTimeRttMs: Long = 0L,
    val scanDurationMs: Long = 0L
)

data class FundingPaperPosition(
    val id: Long,
    val symbol: String,
    val side: Side,
    val entryPrice: Double,
    val quantity: Double,
    val margin: Double,
    val leverage: Int,
    val fundingRatePct: Double,
    val spreadPct: Double,
    val settlementTime: Long,
    val openedAt: Long,
    val status: String = "OPEN",
    val exitPrice: Double? = null,
    val fundingReceived: Double = 0.0,
    val pricePnl: Double = 0.0,
    val feesAndCosts: Double = 0.0,
    val netPnl: Double = 0.0,
    val note: String = ""
)

data class FundingProcessResult(
    val opened: FundingPaperPosition? = null,
    val closed: FundingPaperPosition? = null,
    val message: String
)

data class FundingOpportunityAssessment(
    val score: Int,
    val expectedFundingUsdt: Double,
    val estimatedCostsUsdt: Double,
    val adversePriceRiskUsdt: Double,
    val expectedNetUsdt: Double,
    val adverseMovePct: Double,
    val decision: String,
    val reasons: List<String>
)

/**
 * Transparent rule-based scoring, not a trained AI model or a price forecast.
 * Uses funding, spread, 24h volatility proxy, liquidity and round-trip cost estimates.
 */
object FundingOpportunityAnalyzer {
    fun assess(m: FundingMarket, marginUsdt: Double, leverage: Int): FundingOpportunityAssessment {
        val safeMargin = marginUsdt.coerceAtLeast(0.0)
        val safeLeverage = leverage.coerceIn(1, 10)
        val notional = safeMargin * safeLeverage
        val funding = notional * m.absFundingPct / 100.0
        val feeCost = notional * 0.0010 // estimated 0.05% taker each side
        val spreadCost = notional * m.spreadPct / 100.0
        val slippageCost = notional * 0.0002
        val costs = feeCost + spreadCost + slippageCost

        // Conservative heuristic proxy, not ATR or a statistical forecast.
        val adverseMovePct = (max(0.15, min(abs(m.changePct24h) * 0.08, 5.0)) + m.spreadPct / 2.0)
        val adverseRisk = notional * adverseMovePct / 100.0
        val net = funding - costs - adverseRisk

        val fundingScore = (m.absFundingPct / 2.0 * 30.0).coerceIn(0.0, 30.0)
        val liquidityScore = ((m.quoteVolume / 100_000_000.0) * 25.0).coerceIn(0.0, 25.0)
        val spreadScore = (25.0 - m.spreadPct * 250.0).coerceIn(0.0, 25.0)
        val volatilityScore = (20.0 - adverseMovePct * 4.0).coerceIn(0.0, 20.0)
        val score = (fundingScore + liquidityScore + spreadScore + volatilityScore).toInt().coerceIn(0, 100)

        val reasons = mutableListOf<String>()
        if (net <= 0.0) reasons += "Lợi nhuận ròng kỳ vọng không dương sau dự phòng rủi ro giá."
        if (score < 65) reasons += "Điểm cơ hội dưới ngưỡng 65/100."
        if (m.spreadPct > 0.08) reasons += "Spread cao, dễ ăn mòn funding."
        if (abs(m.changePct24h) > 8.0) reasons += "Biến động 24h lớn."
        if (m.quoteVolume < 50_000_000.0) reasons += "Thanh khoản chưa đạt mức ưu tiên."
        if (reasons.isEmpty()) reasons += "Net kỳ vọng dương và các bộ lọc chất lượng đạt yêu cầu."
        val approved = net > 0.0 && score >= 65
        return FundingOpportunityAssessment(
            score, funding, costs, adverseRisk, net, adverseMovePct,
            if (approved) "ĐỦ ĐIỀU KIỆN PAPER" else "BỎ QUA",
            reasons
        )
    }
}

class BinanceFundingClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // Reuse slow-changing public market data so the near-settlement loop
    // spends its requests on funding and server-clock refreshes.
    private data class CacheEntry(val body: String, val savedAt: Long)
    private val cache = mutableMapOf<String, CacheEntry>()

    private fun getJson(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Binance Futures HTTP ${response.code}")
            return response.body?.string() ?: "[]"
        }
    }

    private fun cachedJson(key: String, url: String, ttlMs: Long): String {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            val entry = cache[key]
            if (entry != null && now - entry.savedAt < ttlMs) return entry.body
        }
        val fresh = getJson(url)
        synchronized(cache) { cache[key] = CacheEntry(fresh, System.currentTimeMillis()) }
        return fresh
    }

    suspend fun scan(minFundingPct: Double): FundingScanResult = withContext(Dispatchers.IO) {
        val scanStartedElapsed = android.os.SystemClock.elapsedRealtime()
        val premium = JSONArray(getJson("https://fapi.binance.com/fapi/v1/premiumIndex"))
        // 24h statistics do not need to be downloaded every second.
        val ticker = JSONArray(cachedJson("ticker24h",
            "https://fapi.binance.com/fapi/v1/ticker/24hr", 10_000L))
        // Keep order-book filter data fresh, but avoid a request on every loop.
        val book = JSONArray(cachedJson("bookTicker",
            "https://fapi.binance.com/fapi/v1/ticker/bookTicker", 2_000L))
        val exchangeInfo = org.json.JSONObject(cachedJson("exchangeInfo",
            "https://fapi.binance.com/fapi/v1/exchangeInfo", 6L * 60L * 60L * 1000L))
        val timeRequestStart = System.currentTimeMillis()
        val serverTimeJson = org.json.JSONObject(getJson("https://fapi.binance.com/fapi/v1/time"))
        val timeRequestEnd = System.currentTimeMillis()
        val serverTime = serverTimeJson.optLong("serverTime", timeRequestEnd)
        val serverRtt = timeRequestEnd - timeRequestStart
        val estimatedServerNow = serverTime + (serverRtt / 2L)
        val tradingSymbols = mutableSetOf<String>()
        val symbolsJson = exchangeInfo.optJSONArray("symbols") ?: JSONArray()
        for (i in 0 until symbolsJson.length()) {
            val s = symbolsJson.getJSONObject(i)
            if (s.optString("status") == "TRADING" &&
                s.optString("contractType") == "PERPETUAL" &&
                s.optString("quoteAsset") == "USDT") {
                tradingSymbols += s.optString("symbol")
            }
        }

        val volume = mutableMapOf<String, Pair<Double, Double>>()
        for (i in 0 until ticker.length()) {
            val t = ticker.getJSONObject(i)
            val symbol = t.optString("symbol")
            volume[symbol] = (t.optString("quoteVolume").toDoubleOrNull() ?: 0.0) to
                (t.optString("priceChangePercent").toDoubleOrNull() ?: 0.0)
        }
        val spreads = mutableMapOf<String, Double>()
        for (i in 0 until book.length()) {
            val b = book.getJSONObject(i)
            val bid = b.optString("bidPrice").toDoubleOrNull() ?: 0.0
            val ask = b.optString("askPrice").toDoubleOrNull() ?: 0.0
            val mid = (bid + ask) / 2.0
            if (mid > 0.0 && ask >= bid) {
                spreads[b.optString("symbol")] = (ask - bid) / mid * 100.0
            }
        }

        val all = ArrayList<FundingMarket>()
        val now = estimatedServerNow
        for (i in 0 until premium.length()) {
            val p = premium.getJSONObject(i)
            val symbol = p.optString("symbol")
            if (symbol !in tradingSymbols) continue
            val rate = (p.optString("lastFundingRate").toDoubleOrNull() ?: 0.0) * 100.0
            val next = p.optLong("nextFundingTime", 0L)
            val mark = p.optString("markPrice").toDoubleOrNull() ?: 0.0
            val stats = volume[symbol] ?: continue
            val spread = spreads[symbol] ?: 999.0
            if (next <= now || mark <= 0.0) continue
            all += FundingMarket(symbol, rate, next, mark, stats.first, stats.second, spread)
        }
        val candidates = all.filter { m ->
            m.absFundingPct >= minFundingPct &&
                m.quoteVolume >= 20_000_000.0 &&
                m.spreadPct <= 0.10 &&
                abs(m.changePct24h) <= 12.0 &&
                (m.nextFundingTime - now) in 0L..30L * 60L * 1000L
        }.sortedByDescending { it.absFundingPct }
        val localCompletedAt = System.currentTimeMillis()
        val duration = android.os.SystemClock.elapsedRealtime() - scanStartedElapsed
        FundingScanResult(
            markets = all,
            candidates = candidates,
            scannedAt = now,
            localScannedAt = localCompletedAt,
            serverOffsetMs = estimatedServerNow - localCompletedAt,
            serverTimeRttMs = serverRtt,
            scanDurationMs = duration
        )
    }
}

/**
 * Paper-only automated funding strategy.
 * It targets an entry window around 5 seconds before settlement, then
 * simulates payment at the settlement timestamp and closes on the first
 * subsequent successful market scan. This is paper timing, not exchange proof.
 */
class FundingRateHunterEngine(context: Context? = null) {
    private val statePrefs = context?.getSharedPreferences("tony_funding_state", Context.MODE_PRIVATE)
    private val learning = context?.let { FundingLearningEngine(it) }
    fun learningStats(): FundingLearningStats? = learning?.stats()
    private val positions = mutableListOf<FundingPaperPosition>()
    private val seenSignals = mutableMapOf<String, Int>()
    private var nextId = 1L
    private var lastMessage = "Auto Funding đang tắt."

    init {
        restoreState()
    }

    private fun restoreState() {
        val raw = statePrefs?.getString("engine_state", null) ?: return
        runCatching {
            val root = org.json.JSONObject(raw)
            nextId = root.optLong("nextId", 1L).coerceAtLeast(1L)
            lastMessage = root.optString("lastMessage", "Đã khôi phục trạng thái Funding Hunter.")
            positions.clear()
            val list = root.optJSONArray("positions") ?: JSONArray()
            for (i in 0 until list.length()) {
                val j = list.getJSONObject(i)
                positions += FundingPaperPosition(
                    id = j.optLong("id"),
                    symbol = j.optString("symbol"),
                    side = Side.valueOf(j.optString("side")),
                    entryPrice = j.optDouble("entryPrice"),
                    quantity = j.optDouble("quantity"),
                    margin = j.optDouble("margin"),
                    leverage = j.optInt("leverage"),
                    fundingRatePct = j.optDouble("fundingRatePct"),
                    spreadPct = j.optDouble("spreadPct"),
                    settlementTime = j.optLong("settlementTime"),
                    openedAt = j.optLong("openedAt"),
                    status = j.optString("status", "OPEN"),
                    exitPrice = if (j.isNull("exitPrice")) null else j.optDouble("exitPrice"),
                    fundingReceived = j.optDouble("fundingReceived"),
                    pricePnl = j.optDouble("pricePnl"),
                    feesAndCosts = j.optDouble("feesAndCosts"),
                    netPnl = j.optDouble("netPnl"),
                    note = j.optString("note")
                )
            }
            seenSignals.clear()
            val signals = root.optJSONObject("seenSignals") ?: org.json.JSONObject()
            val keys = signals.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                seenSignals[key] = signals.optInt(key)
            }
        }
    }

    private fun persistState() {
        val prefs = statePrefs ?: return
        val root = org.json.JSONObject()
        val list = JSONArray()
        positions.forEach { p ->
            list.put(org.json.JSONObject().apply {
                put("id", p.id); put("symbol", p.symbol); put("side", p.side.name)
                put("entryPrice", p.entryPrice); put("quantity", p.quantity)
                put("margin", p.margin); put("leverage", p.leverage)
                put("fundingRatePct", p.fundingRatePct); put("spreadPct", p.spreadPct)
                put("settlementTime", p.settlementTime); put("openedAt", p.openedAt)
                put("status", p.status); put("exitPrice", p.exitPrice ?: org.json.JSONObject.NULL)
                put("fundingReceived", p.fundingReceived); put("pricePnl", p.pricePnl)
                put("feesAndCosts", p.feesAndCosts); put("netPnl", p.netPnl); put("note", p.note)
            })
        }
        val signals = org.json.JSONObject()
        seenSignals.forEach { (key, value) -> signals.put(key, value) }
        root.put("nextId", nextId)
        root.put("lastMessage", lastMessage)
        root.put("positions", list)
        root.put("seenSignals", signals)
        prefs.edit().putString("engine_state", root.toString()).apply()
    }
    fun history(): List<FundingPaperPosition> = positions.toList()
    fun openPositions(): List<FundingPaperPosition> = positions.filter { it.status == "OPEN" }
    fun status(): String = lastMessage

    fun process(
        scan: FundingScanResult,
        autoEnabled: Boolean,
        marginUsdt: Double = 2.0,
        maxPaperLeverage: Int = 10
    ): FundingProcessResult {
        val now = scan.scannedAt
        learning?.recordScan(scan, marginUsdt)
        val open = openPositions().firstOrNull()
        if (open != null) {
            val market = scan.markets.firstOrNull { it.symbol == open.symbol }
            if (market != null && now >= open.settlementTime) {
                val exit = market.markPrice
                val priceMove = if (open.side == Side.SHORT) open.entryPrice - exit else exit - open.entryPrice
                val pricePnl = priceMove * open.quantity
                val notional = open.entryPrice * open.quantity
                val funding = notional * abs(open.fundingRatePct) / 100.0
                val tradingFees = notional * 0.0005 + (exit * open.quantity) * 0.0005
                val spreadCost = notional * open.spreadPct / 100.0
                val slippageBuffer = notional * 0.0002
                val costs = tradingFees + spreadCost + slippageBuffer
                val net = funding + pricePnl - costs
                val closed = open.copy(
                    status = "CLOSED",
                    exitPrice = exit,
                    fundingReceived = funding,
                    pricePnl = pricePnl,
                    feesAndCosts = costs,
                    netPnl = net,
                    note = "Đã mô phỏng đủ điều kiện tại timestamp funding; đóng ngay ở lần quét kế tiếp. Không xác nhận thanh toán thực tế."
                )
                val index = positions.indexOfFirst { it.id == open.id }
                if (index >= 0) positions[index] = closed
                learning?.resolve(closed)
                lastMessage = "Đã đóng mô phỏng ${open.symbol}; funding giả lập ${fmt(funding)} USDT, PnL ròng ${fmt(net)} USDT."
                persistState()
                return FundingProcessResult(closed = closed, message = lastMessage)
            }
            lastMessage = "Đang giữ ${open.symbol}; chờ thời điểm funding ${open.settlementTime}."
            persistState()
            return FundingProcessResult(message = lastMessage)
        }

        if (!autoEnabled) {
            lastMessage = "Auto Funding đang tắt."
            persistState()
            return FundingProcessResult(message = lastMessage)
        }
        if (!marginUsdt.isFinite() || marginUsdt <= 0.0 || marginUsdt > 100_000.0 || scan.candidates.isEmpty()) {
            lastMessage = if (scan.candidates.isEmpty()) "Chưa có cặp đạt bộ lọc funding và thanh khoản."
                else "Margin mô phỏng không hợp lệ (0–100.000 USDT)."
            persistState()
            return FundingProcessResult(message = lastMessage)
        }

        val chosen = scan.candidates.firstOrNull { candidate ->
            val remaining = candidate.nextFundingTime - now
            val signalKey = "${candidate.symbol}:${candidate.nextFundingTime}"
            val count = (seenSignals[signalKey] ?: 0) + 1
            seenSignals[signalKey] = count
            val provisionalLeverage = riskAwareLeverage(candidate, maxPaperLeverage)
            val assessment = FundingOpportunityAnalyzer.assess(candidate, marginUsdt, provisionalLeverage)
            remaining in 3_000L..7_000L && count >= 2 &&
                assessment.decision == "ĐỦ ĐIỀU KIỆN PAPER"
        }
        if (chosen == null) {
            lastMessage = "Đang quét ${scan.candidates.size} ứng viên; chỉ vào trong cửa sổ 3–7 giây trước funding (mục tiêu T−5s), cần 2 lần xác nhận."
            persistState()
            return FundingProcessResult(message = lastMessage)
        }

        val leverage = riskAwareLeverage(chosen, maxPaperLeverage)
        val notional = marginUsdt * leverage
        val quantity = notional / chosen.markPrice
        val opened = FundingPaperPosition(
            id = nextId++,
            symbol = chosen.symbol,
            side = chosen.side,
            entryPrice = chosen.markPrice,
            quantity = quantity,
            margin = marginUsdt,
            leverage = leverage,
            fundingRatePct = chosen.fundingRatePct,
            spreadPct = chosen.spreadPct,
            settlementTime = chosen.nextFundingTime,
            openedAt = now
        )
        positions += opened
        val entryAssessment = FundingOpportunityAnalyzer.assess(chosen, marginUsdt, leverage)
        lastMessage = "Mở PAPER ${chosen.side} ${chosen.symbol} • ${leverage}x • điểm ${entryAssessment.score}/100 • Net ước tính ${fmt(entryAssessment.expectedNetUsdt)} USDT • chờ timestamp funding."
        persistState()
        return FundingProcessResult(opened = opened, message = lastMessage)
    }

    private fun riskAwareLeverage(m: FundingMarket, exchangeMaxPlaceholder: Int): Int {
        // Risk budget: a modeled adverse move should consume no more than 10% of margin.
        // 24h move is only a rough volatility proxy; not a liquidation model.
        val adverseMovePct = max(0.35, min(abs(m.changePct24h) * 0.08, 5.0)) + m.spreadPct / 2.0
        val riskCap = floor(10.0 / adverseMovePct).toInt().coerceIn(1, 10)
        return min(exchangeMaxPlaceholder.coerceAtLeast(1), riskCap).coerceIn(1, 10)
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.5f", v)
}


/**
 * Persists Funding Hunter preferences. AUTO is deliberately NOT auto-resumed
 * after process restart; the previous state is shown as a reminder and requires
 * an explicit user tap to resume.
 */
class FundingSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tony_funding_settings", Context.MODE_PRIVATE)

    fun loadThreshold(): String = prefs.getString("threshold_pct", "1.5") ?: "1.5"
    fun saveThreshold(value: String) {
        prefs.edit().putString("threshold_pct", value).apply()
    }
    fun loadMargin(): String = prefs.getString("margin_usdt", "2") ?: "2"
    fun saveMargin(value: String) {
        prefs.edit().putString("margin_usdt", value).apply()
    }
    fun saveAutoEnabled(value: Boolean) {
        prefs.edit().putBoolean("last_auto_enabled", value).apply()
    }
    fun wasAutoEnabled(): Boolean = prefs.getBoolean("last_auto_enabled", false)
}
