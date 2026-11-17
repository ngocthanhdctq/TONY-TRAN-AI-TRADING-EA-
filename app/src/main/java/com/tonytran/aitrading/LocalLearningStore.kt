package com.tonytran.aitrading

import android.content.Context
import org.json.JSONObject

class LocalLearningStore(context: Context) {
    private val prefs = context.getSharedPreferences("tony_local_learning", Context.MODE_PRIVATE)

    fun loadConfig(): StrategyConfig {
        val j = runCatching { JSONObject(prefs.getString("active_config", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return StrategyConfig(
            name = j.optString("name", "Baseline v1"),
            longScoreThreshold = j.optInt("longScoreThreshold", 70),
            shortScoreThreshold = j.optInt("shortScoreThreshold", 70),
            rsiLongMin = j.optDouble("rsiLongMin", 50.0),
            rsiLongMax = j.optDouble("rsiLongMax", 68.0),
            rsiShortMin = j.optDouble("rsiShortMin", 32.0),
            rsiShortMax = j.optDouble("rsiShortMax", 50.0),
            volumeRatioMin = j.optDouble("volumeRatioMin", 1.5),
            atrPctMin = j.optDouble("atrPctMin", 0.15),
            atrPctMax = j.optDouble("atrPctMax", 3.5),
            feeRatePerSide = j.optDouble("feeRatePerSide", 0.001),
            maxValidationDrawdownPct = j.optDouble("maxValidationDrawdownPct", 12.0)
        )
    }

    fun saveConfig(config: StrategyConfig) {
        val j = JSONObject().apply {
            put("name", config.name)
            put("longScoreThreshold", config.longScoreThreshold)
            put("shortScoreThreshold", config.shortScoreThreshold)
            put("rsiLongMin", config.rsiLongMin); put("rsiLongMax", config.rsiLongMax)
            put("rsiShortMin", config.rsiShortMin); put("rsiShortMax", config.rsiShortMax)
            put("volumeRatioMin", config.volumeRatioMin)
            put("atrPctMin", config.atrPctMin); put("atrPctMax", config.atrPctMax)
            put("feeRatePerSide", config.feeRatePerSide)
            put("maxValidationDrawdownPct", config.maxValidationDrawdownPct)
        }
        prefs.edit().putString("active_config", j.toString()).apply()
    }

    fun saveReport(report: BacktestReport) {
        val j = JSONObject().apply {
            put("symbol", report.symbol); put("interval", report.interval)
            put("promoted", report.promoted); put("reason", report.reason)
            put("testedAt", report.testedAt)
            put("baselineReturn", report.baseline.netReturnPct)
            put("candidateReturn", report.candidate.netReturnPct)
            put("candidateDrawdown", report.candidate.maxDrawdownPct)
            put("candidateTrades", report.candidate.trades)
            put("candidateWinRate", report.candidate.winRatePct)
        }
        prefs.edit().putString("last_report", j.toString()).apply()
    }

    fun lastReport(): JSONObject? = runCatching {
        JSONObject(prefs.getString("last_report", null) ?: return null)
    }.getOrNull()
}
