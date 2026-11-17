package com.tonytran.aitrading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central guard for PAPER orders and controlled strategy learning.
 * Hard limits are code-owned: user settings and AI proposals cannot raise them.
 * No live exchange execution is implemented by this class.
 */
data class SafetyPolicy(
    val paperOnly: Boolean = true,
    val maxLeverage: Int = 10,
    val maxFuturesMarginPerOrder: Double = 2.0,
    val maxOpenPositions: Int = 3,
    val maxSpotOrderUsdt: Double = 5.0,
    val allowAutoCapitalIncrease: Boolean = false,
    val allowMartingale: Boolean = false,
    val requireUserApprovalForStrategyPromotion: Boolean = true
)

data class SafetyDecision(val allowed: Boolean, val reason: String)

enum class LearningStage {
    DRAFT, BACKTEST, WALK_FORWARD, PENDING_APPROVAL, APPROVED, REJECTED, ROLLED_BACK
}

data class LearningProposal(
    val id: String,
    val source: String,
    val summary: String,
    val stage: LearningStage,
    val createdAt: Long = System.currentTimeMillis(),
    val validationPassed: Boolean = false,
    val metrics: String = ""
)

object SafetyCore {
    // These are immutable product guardrails, not AI-tunable settings.
    const val HARD_MAX_LEVERAGE = 10
    const val HARD_MAX_OPEN_POSITIONS = 3
    const val HARD_MAX_FUTURES_MARGIN_USDT = 2.0
    const val HARD_MAX_SPOT_ORDER_USDT = 5.0

    fun checkSpot(amount: Double, available: Double, openCount: Int): SafetyDecision = when {
        amount.isNaN() || amount.isInfinite() || amount < 5.0 ->
            SafetyDecision(false, "Số tiền Spot không hợp lệ hoặc thấp hơn mức mô phỏng tối thiểu 5 USDT.")
        amount > HARD_MAX_SPOT_ORDER_USDT ->
            SafetyDecision(false, "Safety Core chặn: Spot tối đa hiện tại là 5 USDT mỗi lệnh.")
        amount > available -> SafetyDecision(false, "Số dư Spot không đủ.")
        openCount >= HARD_MAX_OPEN_POSITIONS ->
            SafetyDecision(false, "Đã đạt giới hạn số vị thế mở do Safety Core đặt ra.")
        else -> SafetyDecision(true, "Đạt kiểm tra Safety Core.")
    }

    fun checkFutures(margin: Double, leverage: Int, available: Double, openCount: Int): SafetyDecision = when {
        margin.isNaN() || margin.isInfinite() || margin <= 0.0 ->
            SafetyDecision(false, "Margin không hợp lệ.")
        margin > HARD_MAX_FUTURES_MARGIN_USDT ->
            SafetyDecision(false, "Safety Core chặn: margin tối đa 2 USDT mỗi lệnh.")
        leverage !in 1..HARD_MAX_LEVERAGE ->
            SafetyDecision(false, "Đòn bẩy vượt giới hạn cứng 1x–10x.")
        margin > available -> SafetyDecision(false, "Số dư Futures không đủ.")
        openCount >= HARD_MAX_OPEN_POSITIONS ->
            SafetyDecision(false, "Đã đạt giới hạn số vị thế mở.")
        else -> SafetyDecision(true, "Đạt kiểm tra Safety Core.")
    }

    fun validatePromotion(report: BacktestReport, walkForwardPassed: Boolean): SafetyDecision = when {
        !report.promoted -> SafetyDecision(false, "Backtest ngoài mẫu không đạt; giữ cấu hình hiện tại.")
        !walkForwardPassed -> SafetyDecision(false, "Walk-forward chưa đạt; không được đề xuất áp dụng.")
        report.candidate.trades < 5 -> SafetyDecision(false, "Số giao dịch kiểm chứng chưa đủ.")
        report.candidate.maxDrawdownPct > 12.0 -> SafetyDecision(false, "Drawdown vượt giới hạn an toàn 12%.")
        else -> SafetyDecision(true, "Đạt kiểm định kỹ thuật. Vẫn cần người dùng xác nhận trước khi áp dụng.")
    }
}

class SafetyAuditStore(context: Context) {
    private val prefs = context.getSharedPreferences("tony_safety_core_v22", Context.MODE_PRIVATE)

    fun record(event: String, detail: String) {
        val list = runCatching { JSONArray(prefs.getString("audit", "[]") ?: "[]") }.getOrDefault(JSONArray())
        list.put(JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("event", event.take(80))
            put("detail", detail.take(500))
        })
        while (list.length() > 300) list.remove(0)
        prefs.edit().putString("audit", list.toString()).apply()
    }

    fun auditLog(): List<String> {
        val list = runCatching { JSONArray(prefs.getString("audit", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { "${it.optLong("at")}|${it.optString("event")}|${it.optString("detail")}" }
        }.asReversed()
    }

    fun saveProposal(proposal: LearningProposal) {
        val list = runCatching { JSONArray(prefs.getString("proposals", "[]") ?: "[]") }.getOrDefault(JSONArray())
        list.put(JSONObject().apply {
            put("id", proposal.id); put("source", proposal.source)
            put("summary", proposal.summary); put("stage", proposal.stage.name)
            put("createdAt", proposal.createdAt); put("validationPassed", proposal.validationPassed)
            put("metrics", proposal.metrics)
        })
        while (list.length() > 100) list.remove(0)
        prefs.edit().putString("proposals", list.toString()).apply()
        record("LEARNING_PROPOSAL", "${proposal.id}:${proposal.stage}")
    }

    fun proposals(): List<LearningProposal> {
        val list = runCatching { JSONArray(prefs.getString("proposals", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { j ->
                val stage = runCatching { LearningStage.valueOf(j.optString("stage")) }.getOrDefault(LearningStage.DRAFT)
                LearningProposal(j.optString("id"), j.optString("source"), j.optString("summary"),
                    stage, j.optLong("createdAt"), j.optBoolean("validationPassed"), j.optString("metrics"))
            }
        }.asReversed()
    }
}
