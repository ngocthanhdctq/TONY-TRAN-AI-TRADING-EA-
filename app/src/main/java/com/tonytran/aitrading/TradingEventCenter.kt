package com.tonytran.aitrading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class TradingEventType {
    MARKET_SIGNAL, MARKET_MOVE, PAPER_ORDER_OPEN, PAPER_ORDER_CLOSE,
    RISK_WARNING, FUNDING_UPDATE, SYSTEM, LEARNING
}

data class TradingEvent(
    val id: Long,
    val at: Long,
    val type: TradingEventType,
    val title: String,
    val detail: String,
    val symbol: String = "",
    val tradeId: Long? = null,
    val source: String = "APP",
    val status: String = "INFO",
    val latencyMs: Long? = null
) {
    fun displayTime(): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.MEDIUM
    ).format(Date(at))
}

class TradingEventStore(context: Context) {
    private val prefs = context.getSharedPreferences("tony_trading_event_center_v24", Context.MODE_PRIVATE)
    private val maxEvents = 500

    @Synchronized
    fun add(
        type: TradingEventType,
        title: String,
        detail: String,
        symbol: String = "",
        tradeId: Long? = null,
        source: String = "APP",
        status: String = "INFO",
        latencyMs: Long? = null,
        dedupeKey: String? = null,
        cooldownMs: Long = 0L
    ): TradingEvent {
        val now = System.currentTimeMillis()
        if (!dedupeKey.isNullOrBlank() && cooldownMs > 0) {
            val last = prefs.getLong("dedupe:$dedupeKey", 0L)
            if (now - last < cooldownMs) {
                return TradingEvent(-1, now, type, title, detail, symbol, tradeId, source, status, latencyMs)
            }
            prefs.edit().putLong("dedupe:$dedupeKey", now).apply()
        }
        val list = readJson()
        val nextId = prefs.getLong("next_id", 1L)
        val event = TradingEvent(nextId, now, type, title, detail, symbol, tradeId, source, status, latencyMs)
        list.put(event.toJson())
        while (list.length() > maxEvents) list.remove(0)
        prefs.edit().putString("events", list.toString()).putLong("next_id", nextId + 1L).apply()
        return event
    }

    @Synchronized
    fun all(): List<TradingEvent> {
        val list = readJson()
        return (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { j ->
                val type = runCatching { TradingEventType.valueOf(j.optString("type")) }
                    .getOrDefault(TradingEventType.SYSTEM)
                TradingEvent(
                    j.optLong("id"), j.optLong("at"), type, j.optString("title"),
                    j.optString("detail"), j.optString("symbol"),
                    if (j.isNull("tradeId")) null else j.optLong("tradeId"),
                    j.optString("source", "APP"), j.optString("status", "INFO"),
                    if (j.isNull("latencyMs")) null else j.optLong("latencyMs")
                )
            }
        }.sortedByDescending { it.at }
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove("events").apply()
    }

    private fun readJson(): JSONArray = runCatching {
        JSONArray(prefs.getString("events", "[]") ?: "[]")
    }.getOrDefault(JSONArray())

    private fun TradingEvent.toJson() = JSONObject().apply {
        put("id", id); put("at", at); put("type", type.name)
        put("title", title); put("detail", detail); put("symbol", symbol)
        put("tradeId", tradeId ?: JSONObject.NULL); put("source", source)
        put("status", status); put("latencyMs", latencyMs ?: JSONObject.NULL)
    }
}
