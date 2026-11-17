package com.tonytran.aitrading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class FundingLearningRecord(
    val key: String,
    val symbol: String,
    val settlementTime: Long,
    val fundingPct: Double,
    val score: Int,
    val expectedNet: Double,
    val decision: String,
    val entryPrice: Double,
    val spreadPct: Double,
    val side: Side,
    val marginUsdt: Double,
    val leverage: Int,
    val quoteVolume: Double = 0.0,
    val changePct24h: Double = 0.0,
    val secondsToSettlementAtScan: Long = 0L,
    val pricePnlUsdt: Double? = null,
    val adverseMovePct: Double? = null,
    val outcomeNet: Double? = null,
    val resolved: Boolean = false
)

data class FundingLearningStats(
    val total: Int,
    val resolved: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val realizedNet: Double,
    val recommendedMinScore: Int?,
    val message: String,
    val acceptedCount: Int,
    val acceptedWinRatePct: Double,
    val rejectedCount: Int,
    val rejectedPositivePct: Double,
    val averageForecastError: Double,
    val patternInsight: String,
    val adverseRiskPct: Double?,
    val holdoutCount: Int,
    val holdoutWinRatePct: Double?,
    val walkForwardMessage: String
)

/**
 * V2.0 counterfactual outcome ledger.
 * Tracks all scanner-qualified opportunities (including analyzer rejects), then
 * resolves them from the first available market mark after settlement.
 * This is a delayed mark-price simulation, not exchange income or fill data.
 */
class FundingLearningEngine(context: Context) {
    private val prefs = context.getSharedPreferences("tony_funding_learning_v2", Context.MODE_PRIVATE)
    private val records = mutableListOf<FundingLearningRecord>()

    init { restore() }

    private fun restore() {
        runCatching {
            val arr = JSONArray(prefs.getString("records", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                records += FundingLearningRecord(
                    key = j.optString("key"), symbol = j.optString("symbol"),
                    settlementTime = j.optLong("settlementTime"), fundingPct = j.optDouble("fundingPct"),
                    score = j.optInt("score"), expectedNet = j.optDouble("expectedNet"),
                    decision = j.optString("decision"), entryPrice = j.optDouble("entryPrice"),
                    spreadPct = j.optDouble("spreadPct"), side = Side.valueOf(j.optString("side")),
                    marginUsdt = j.optDouble("marginUsdt"), leverage = j.optInt("leverage", 10),
                    quoteVolume = j.optDouble("quoteVolume"), changePct24h = j.optDouble("changePct24h"),
                    secondsToSettlementAtScan = j.optLong("secondsToSettlementAtScan"),
                    pricePnlUsdt = if (j.isNull("pricePnlUsdt")) null else j.optDouble("pricePnlUsdt"),
                    adverseMovePct = if (j.isNull("adverseMovePct")) null else j.optDouble("adverseMovePct"),
                    outcomeNet = if (j.isNull("outcomeNet")) null else j.optDouble("outcomeNet"),
                    resolved = j.optBoolean("resolved")
                )
            }
        }
    }

    private fun persist() {
        val arr = JSONArray()
        records.takeLast(3000).forEach { r ->
            arr.put(JSONObject().apply {
                put("key", r.key); put("symbol", r.symbol); put("settlementTime", r.settlementTime)
                put("fundingPct", r.fundingPct); put("score", r.score); put("expectedNet", r.expectedNet)
                put("decision", r.decision); put("entryPrice", r.entryPrice); put("spreadPct", r.spreadPct)
                put("side", r.side.name); put("marginUsdt", r.marginUsdt); put("leverage", r.leverage)
                put("quoteVolume", r.quoteVolume); put("changePct24h", r.changePct24h)
                put("secondsToSettlementAtScan", r.secondsToSettlementAtScan)
                put("pricePnlUsdt", r.pricePnlUsdt ?: JSONObject.NULL)
                put("adverseMovePct", r.adverseMovePct ?: JSONObject.NULL)
                put("outcomeNet", r.outcomeNet ?: JSONObject.NULL); put("resolved", r.resolved)
            })
        }
        prefs.edit().putString("records", arr.toString()).apply()
    }

    @Synchronized
    fun recordScan(scan: FundingScanResult, margin: Double) {
        // Resolve pending observations first, using the first available mark at/after settlement.
        var changed = false
        records.indices.forEach { index ->
            val r = records[index]
            if (!r.resolved && scan.scannedAt >= r.settlementTime) {
                val market = scan.markets.firstOrNull { it.symbol == r.symbol }
                if (market != null && market.markPrice > 0.0 && r.entryPrice > 0.0) {
                    val qty = (r.marginUsdt * r.leverage) / r.entryPrice
                    val priceMove = if (r.side == Side.SHORT) r.entryPrice - market.markPrice
                        else market.markPrice - r.entryPrice
                    val pricePnl = priceMove * qty
                    val notional = r.marginUsdt * r.leverage
                    val funding = notional * abs(r.fundingPct) / 100.0
                    val fees = notional * 0.0010 +
                        notional * r.spreadPct / 100.0 +
                        notional * 0.0002
                    val notionalSafe = notional.coerceAtLeast(0.0000001)
                    val movePct = pricePnl / notionalSafe * 100.0
                    records[index] = r.copy(pricePnlUsdt = pricePnl, adverseMovePct = movePct,
                        outcomeNet = funding + pricePnl - fees, resolved = true)
                    changed = true
                }
            }
        }

        // Candidates are scanner-qualified, regardless of AI accept/reject decision.
        // Thus rejected opportunities are retained for counterfactual evaluation.
        scan.candidates.forEach { market ->
            val key = "${market.symbol}:${market.nextFundingTime}"
            if (records.any { it.key == key }) return@forEach
            val leverage = 10 // fixed comparison baseline, not a promise of executable leverage
            val assessment = FundingOpportunityAnalyzer.assess(market, margin, leverage)
            records += FundingLearningRecord(
                key = key, symbol = market.symbol, settlementTime = market.nextFundingTime,
                fundingPct = market.fundingRatePct, score = assessment.score,
                expectedNet = assessment.expectedNetUsdt, decision = assessment.decision,
                entryPrice = market.markPrice, spreadPct = market.spreadPct,
                side = market.side, marginUsdt = margin.coerceAtLeast(0.0), leverage = leverage,
                quoteVolume = market.quoteVolume, changePct24h = market.changePct24h,
                secondsToSettlementAtScan = ((market.nextFundingTime - scan.scannedAt) / 1000L).coerceAtLeast(0L)
            )
            changed = true
        }
        if (records.size > 3000) records.subList(0, records.size - 3000).clear()
        if (changed) persist()
    }

    // Kept for compatibility with the existing engine callback. In V2.0 outcomes
    // are resolved independently for accepted AND rejected opportunities by scans.
    @Synchronized
    fun resolve(position: FundingPaperPosition) {
        if (position.status != "CLOSED") return
        val key = "${position.symbol}:${position.settlementTime}"
        val index = records.indexOfLast { it.key == key && !it.resolved }
        if (index >= 0) {
            val old = records[index]
            val pricePct = if (old.marginUsdt * old.leverage > 0.0) position.pricePnl / (old.marginUsdt * old.leverage) * 100.0 else 0.0
            records[index] = old.copy(pricePnlUsdt = position.pricePnl, adverseMovePct = pricePct,
                outcomeNet = position.netPnl, resolved = true)
            persist()
        }
    }

    @Synchronized
    fun stats(): FundingLearningStats {
        val done = records.filter { it.resolved && it.outcomeNet != null }
        val wins = done.count { (it.outcomeNet ?: 0.0) > 0.0 }
        val losses = done.size - wins
        val winRate = if (done.isEmpty()) 0.0 else wins * 100.0 / done.size
        val accepted = done.filter { it.decision == "ĐỦ ĐIỀU KIỆN PAPER" }
        val rejected = done.filter { it.decision != "ĐỦ ĐIỀU KIỆN PAPER" }
        val acceptedWin = if (accepted.isEmpty()) 0.0 else
            accepted.count { (it.outcomeNet ?: 0.0) > 0.0 } * 100.0 / accepted.size
        val rejectedPositive = if (rejected.isEmpty()) 0.0 else
            rejected.count { (it.outcomeNet ?: 0.0) > 0.0 } * 100.0 / rejected.size
        val avgError = if (done.isEmpty()) 0.0 else
            done.map { abs((it.outcomeNet ?: 0.0) - it.expectedNet) }.average()
        val net = done.sumOf { it.outcomeNet ?: 0.0 }

        // A recommendation is only a research hint. Require 50 resolved observations,
        // >=15 per score band and >=55% win rate; never change AUTO threshold.
        var recommendation: Int? = null
        if (done.size >= 50) {
            val bands = (60..85 step 5).map { t -> t to done.filter { it.score >= t } }
                .filter { it.second.size >= 15 }
            val best = bands.maxByOrNull { (_, sample) ->
                sample.count { (it.outcomeNet ?: 0.0) > 0.0 }.toDouble() / sample.size
            }
            if (best != null) {
                val wr = best.second.count { (it.outcomeNet ?: 0.0) > 0.0 }.toDouble() / best.second.size
                if (wr >= 0.55) recommendation = best.first
            }
        }
        val msg = when {
            done.size < 50 -> "Cần ít nhất 50 cơ hội đã có kết quả để đề xuất ngưỡng nghiên cứu."
            recommendation == null -> "Chưa có ngưỡng điểm đạt tiêu chí mẫu và tỷ lệ thắng."
            else -> "Ngưỡng ${recommendation}/100 là gợi ý nghiên cứu; không tự áp dụng."
        }
        // Pattern insight: compact, transparent cohorts; require at least 8 observations.
        val patternGroups = done.groupBy { r ->
            val fundingBand = when {
                abs(r.fundingPct) < 1.5 -> "<1.5%"
                abs(r.fundingPct) < 2.5 -> "1.5–2.49%"
                else -> "≥2.5%"
            }
            val spreadBand = if (r.spreadPct <= 0.03) "spread thấp" else "spread cao"
            val volatilityBand = if (abs(r.changePct24h) <= 4.0) "biến động thấp" else "biến động cao"
            "$fundingBand • $spreadBand • $volatilityBand"
        }
        val bestPattern = patternGroups.entries
            .filter { it.value.size >= 8 }
            .maxByOrNull { entry ->
                entry.value.count { (it.outcomeNet ?: 0.0) > 0.0 }.toDouble() / entry.value.size
            }
        val patternInsight = bestPattern?.let { entry ->
            val positive = entry.value.count { (it.outcomeNet ?: 0.0) > 0.0 }
            "${entry.key}: $positive/${entry.value.size} kết quả dương (${ "%.1f".format(positive * 100.0 / entry.value.size) }%). Chỉ là thống kê lịch sử."
        } ?: "Chưa đủ 8 mẫu trong cùng nhóm funding/spread/biến động để nhận diện mẫu đáng tin."

        // Empirical adverse tail: 10th percentile of directional price PnL / notional.
        val moves = done.mapNotNull { r -> r.adverseMovePct }.sorted()
        val adverseRiskPct = if (moves.size >= 20) moves[((moves.size - 1) * 0.10).toInt()] else null

        // Chronological 70/30 walk-forward. Choose threshold on older data, test once on newer data.
        var holdoutCount = 0
        var holdoutWinRate: Double? = null
        var walkForwardMessage = "Cần ít nhất 50 kết quả và tối thiểu 10 mẫu train/15 mẫu holdout để kiểm định ngoài mẫu."
        if (done.size >= 50) {
            val chronological = done.sortedBy { it.settlementTime }
            val split = (chronological.size * 0.70).toInt().coerceIn(1, chronological.size - 1)
            val train = chronological.take(split)
            val holdout = chronological.drop(split)
            val chosen = (55..85 step 5).map { threshold ->
                val sample = train.filter { it.score >= threshold }
                threshold to sample
            }.filter { it.second.size >= 10 }
                .maxByOrNull { (_, sample) ->
                    sample.count { (it.outcomeNet ?: 0.0) > 0.0 }.toDouble() / sample.size
                }
            if (chosen != null) {
                val tested = holdout.filter { it.score >= chosen.first }
                holdoutCount = tested.size
                if (tested.size >= 15) {
                    holdoutWinRate = tested.count { (it.outcomeNet ?: 0.0) > 0.0 } * 100.0 / tested.size
                    val holdoutNet = tested.sumOf { it.outcomeNet ?: 0.0 }
                    walkForwardMessage = "Train chọn ngưỡng ${chosen.first}/100; holdout ${tested.size} mẫu, tỷ lệ dương ${"%.1f".format(holdoutWinRate)}%, Net ${"%.5f".format(holdoutNet)} USDT. Chỉ tham khảo, không tự áp dụng."
                } else {
                    walkForwardMessage = "Đã chọn ngưỡng ${chosen.first}/100 trên train nhưng holdout mới có $holdoutCount mẫu; chưa đủ 15 để kết luận."
                }
            } else {
                walkForwardMessage = "Không có ngưỡng nào đủ 10 mẫu trong phần train; chưa thể kiểm định."
            }
        }

        return FundingLearningStats(
            total = records.size, resolved = done.size, wins = wins, losses = losses,
            winRatePct = winRate, realizedNet = net, recommendedMinScore = recommendation,
            message = msg, acceptedCount = accepted.size, acceptedWinRatePct = acceptedWin,
            rejectedCount = rejected.size, rejectedPositivePct = rejectedPositive,
            averageForecastError = avgError, patternInsight = patternInsight,
            adverseRiskPct = adverseRiskPct, holdoutCount = holdoutCount,
            holdoutWinRatePct = holdoutWinRate, walkForwardMessage = walkForwardMessage
        )
    }
}
