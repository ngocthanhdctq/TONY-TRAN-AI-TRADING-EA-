package com.tonytran.aitrading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Public market data only. No API key, secret, account access or order endpoint.
 * REST polling is deliberately used in this starter build for simpler reliability.
 */
class BinancePublicMarket {
    private val client = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun fetchTickers(): List<MarketCoin> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.binance.com/api/v3/ticker/24hr")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Binance HTTP ${response.code}")
            val array = JSONArray(response.body?.string() ?: "[]")
            val output = ArrayList<MarketCoin>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val symbol = item.optString("symbol")
                if (!symbol.endsWith("USDT")) continue
                if (symbol.contains("UPUSDT") || symbol.contains("DOWNUSDT")) continue
                val price = item.optString("lastPrice").toDoubleOrNull() ?: continue
                if (price <= 0.0) continue
                output += MarketCoin(
                    symbol = symbol,
                    price = price,
                    changePct = item.optString("priceChangePercent").toDoubleOrNull() ?: 0.0,
                    quoteVolume = item.optString("quoteVolume").toDoubleOrNull() ?: 0.0
                )
            }
            output.sortedByDescending { it.quoteVolume }
        }
    }

    suspend fun fetchHistoricalKlines(
        symbol: String,
        interval: String,
        startTime: Long,
        endTime: Long,
        maxCandles: Int = 100_000
    ): List<Candle> = withContext(Dispatchers.IO) {
        val result = ArrayList<Candle>()
        var cursor = startTime
        var pages = 0
        while (cursor < endTime && result.size < maxCandles && pages < 150) {
            val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=$interval&startTime=$cursor&endTime=$endTime&limit=1000"
            val request = Request.Builder().url(url).get().build()
            val page = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Binance historical klines HTTP ${response.code}")
                val array = JSONArray(response.body?.string() ?: "[]")
                (0 until array.length()).map { i ->
                    val row = array.getJSONArray(i)
                    Candle(row.getLong(0), row.getString(1).toDouble(),
                        row.getString(2).toDouble(), row.getString(3).toDouble(),
                        row.getString(4).toDouble(), row.getString(5).toDouble())
                }
            }
            if (page.isEmpty()) break
            result.addAll(page)
            val next = page.last().openTime + intervalMillis(interval)
            if (next <= cursor) break
            cursor = next
            pages++
            kotlinx.coroutines.delay(120)
        }
        result.distinctBy { it.openTime }.sortedBy { it.openTime }.take(maxCandles)
    }

    private fun intervalMillis(interval: String): Long = when (interval) {
        "5m" -> 5 * 60_000L
        "15m" -> 15 * 60_000L
        "1h" -> 60 * 60_000L
        "4h" -> 4 * 60 * 60_000L
        else -> error("Unsupported interval: $interval")
    }

}
