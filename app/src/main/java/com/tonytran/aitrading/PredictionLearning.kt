package com.tonytran.aitrading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class PredictionStatus { PENDING, CORRECT, INCORRECT, FLAT, AVOIDED_MOVE }

data class PredictionRecord(
    val id: String,
    val symbol: String,
    val signal: String,
    val confidence: Int,
    val entryPrice: Double,
    val createdAt: Long,
    val dueAt: Long,
    val exitPrice: Double? = null,
    val directionalReturnPct: Double? = null,
    val status: PredictionStatus = PredictionStatus.PENDING,
    val horizonMinutes: Int = 60,
    val regime: String = "UNKNOWN",
    val atrPct: Double = 0.0,
    val btcChangePct: Double? = null
)

data class PredictionStats(
    val total: Int,
    val resolved: Int,
    val correct: Int,
    val incorrect: Int,
    val flat: Int,
    val pending: Int,
    val accuracyPct: Double,
    val averageDirectionalReturnPct: Double
)

object PredictionOutcomeEvaluator {
    fun evaluate(signal: String, rawReturnPct: Double): PredictionStatus = when (signal) {
        "LONG", "SHORT" -> {
            val directional = if (signal == "SHORT") -rawReturnPct else rawReturnPct
            when {
                directional >= 0.15 -> PredictionStatus.CORRECT
                directional <= -0.15 -> PredictionStatus.INCORRECT
                else -> PredictionStatus.FLAT
            }
        }
        "WAIT" -> if (kotlin.math.abs(rawReturnPct) >= 0.50) PredictionStatus.AVOIDED_MOVE
            else PredictionStatus.FLAT
        else -> PredictionStatus.FLAT
    }
}

class PredictionLearningStore(context: Context) {
    private val prefs = context.getSharedPreferences("tony_prediction_learning_v08", Context.MODE_PRIVATE)
    private val horizons = listOf(5, 15, 60, 240)
    private val bucketMs = 5L * 60L * 1000L
    private val maxRecords = 1200

    fun load(): List<PredictionRecord> = runCatching {
        val array = JSONArray(prefs.getString("records", "[]") ?: "[]")
        (0 until array.length()).map { i ->
            val j = array.getJSONObject(i)
            PredictionRecord(
                id = j.getString("id"), symbol = j.getString("symbol"),
                signal = j.getString("signal"), confidence = j.optInt("confidence"),
                entryPrice = j.getDouble("entryPrice"), createdAt = j.getLong("createdAt"),
                dueAt = j.getLong("dueAt"),
                exitPrice = if (j.isNull("exitPrice")) null else j.optDouble("exitPrice"),
                directionalReturnPct = if (j.isNull("directionalReturnPct")) null else j.optDouble("directionalReturnPct"),
                status = runCatching { PredictionStatus.valueOf(j.optString("status", "PENDING")) }
                    .getOrDefault(PredictionStatus.PENDING),
                horizonMinutes = j.optInt("horizonMinutes", 60),
                regime = j.optString("regime", "UNKNOWN"),
                atrPct = j.optDouble("atrPct", 0.0),
                btcChangePct = if (j.isNull("btcChangePct")) null else j.optDouble("btcChangePct")
            )
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun capture(
        coins: List<MarketCoin>,
        btcChangePct: Double? = coins.firstOrNull { it.symbol == "BTCUSDT" }?.changePct,
        now: Long = System.currentTimeMillis()
    ): List<PredictionRecord> {
        val current = load().toMutableList()
        val bucket = now / bucketMs
        val known = current.map { "${it.symbol}:${it.signal}:${it.createdAt / bucketMs}" }.toSet()
        coins.asSequence()
            .filter { it.price.isFinite() && it.price > 0.0 && it.signal in setOf("LONG", "SHORT", "WAIT") }
            .forEach { coin ->
                val key = "${coin.symbol}:${coin.signal}:$bucket"
                if (key !in known) {
                    val regime = when {
                        coin.atrPct >= 3.5 -> "HIGH_VOLATILITY"
                        coin.trend1h == "BULLISH" -> "BULL_TREND"
                        coin.trend1h == "BEARISH" -> "BEAR_TREND"
                        coin.trend1h == "RANGE" -> "RANGE"
                        else -> "UNKNOWN"
                    }
                    horizons.forEach { minutes ->
                        current += PredictionRecord(
                            id = "${coin.symbol}_${coin.signal}_${now}_$minutes",
                            symbol = coin.symbol, signal = coin.signal,
                            confidence = coin.score.coerceIn(0, 100),
                            entryPrice = coin.price, createdAt = now,
                            dueAt = now + minutes * 60_000L,
                            horizonMinutes = minutes, regime = regime,
                            atrPct = coin.atrPct, btcChangePct = btcChangePct
                        )
                    }
                }
            }
        return saveAndReturn(current.sortedByDescending { it.createdAt }.take(maxRecords))
    }

    @Synchronized
    fun resolve(prices: Map<String, Double>, now: Long = System.currentTimeMillis()): List<PredictionRecord> {
        val updated = load().map { record ->
            val livePrice = prices[record.symbol]
            if (record.status != PredictionStatus.PENDING || now < record.dueAt ||
                livePrice == null || !livePrice.isFinite() || livePrice <= 0.0) record
            else {
                val rawPct = (livePrice - record.entryPrice) / record.entryPrice * 100.0
                val status = PredictionOutcomeEvaluator.evaluate(record.signal, rawPct)
                record.copy(
                    exitPrice = livePrice,
                    directionalReturnPct = if (record.signal == "SHORT") -rawPct else rawPct,
                    status = status
                )
            }
        }
        return saveAndReturn(updated)
    }

    fun stats(records: List<PredictionRecord>): PredictionStats {
        val directional = records.filter { it.signal == "LONG" || it.signal == "SHORT" }
        val resolved = directional.filter { it.status != PredictionStatus.PENDING }
        val correct = resolved.count { it.status == PredictionStatus.CORRECT }
        val incorrect = resolved.count { it.status == PredictionStatus.INCORRECT }
        val flat = resolved.count { it.status == PredictionStatus.FLAT }
        val returns = resolved.mapNotNull { it.directionalReturnPct }
        return PredictionStats(
            total = directional.size, resolved = resolved.size, correct = correct,
            incorrect = incorrect, flat = flat,
            pending = directional.count { it.status == PredictionStatus.PENDING },
            accuracyPct = if (correct + incorrect == 0) 0.0 else correct.toDouble() / (correct + incorrect) * 100.0,
            averageDirectionalReturnPct = if (returns.isEmpty()) 0.0 else returns.average()
        )
    }

    fun clear() { prefs.edit().remove("records").apply() }

    private fun saveAndReturn(records: List<PredictionRecord>): List<PredictionRecord> {
        val array = JSONArray()
        records.forEach { r ->
            array.put(JSONObject().apply {
                put("id", r.id); put("symbol", r.symbol); put("signal", r.signal)
                put("confidence", r.confidence); put("entryPrice", r.entryPrice)
                put("createdAt", r.createdAt); put("dueAt", r.dueAt)
                put("exitPrice", r.exitPrice ?: JSONObject.NULL)
                put("directionalReturnPct", r.directionalReturnPct ?: JSONObject.NULL)
                put("status", r.status.name); put("horizonMinutes", r.horizonMinutes)
                put("regime", r.regime); put("atrPct", r.atrPct)
                put("btcChangePct", r.btcChangePct ?: JSONObject.NULL)
            })
        }
        prefs.edit().putString("records", array.toString()).apply()
        return records.sortedByDescending { it.createdAt }
    }
}
