package com.tonytran.aitrading

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Read-only signed account queries. This class deliberately exposes no order/withdrawal methods. */
data class BinanceAssetBalance(val asset: String, val free: Double, val locked: Double, val wallet: Double = 0.0, val unrealized: Double = 0.0)
data class BinanceOpenPosition(val symbol: String, val amount: Double, val entryPrice: Double, val unrealized: Double, val leverage: Int, val isolated: Boolean)
data class BinanceReadOnlySnapshot(val spot: List<BinanceAssetBalance>, val futures: List<BinanceAssetBalance>, val positions: List<BinanceOpenPosition>, val fetchedAt: Long)

class BinanceCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("tony_binance_readonly_v31", Context.MODE_PRIVATE)
    private val crypto = KeystoreAesGcm("tony_binance_readonly_aes_gcm_v31")
    fun hasCredentials(): Boolean = prefs.contains("credentials_enc")
    fun save(apiKey: String, secret: String) {
        require(apiKey.length in 16..256 && secret.length in 16..256) { "API Key/Secret không đúng định dạng cơ bản." }
        val packed = org.json.JSONObject().put("key", apiKey).put("secret", secret).toString()
        prefs.edit().putString("credentials_enc", crypto.encrypt(packed)).apply()
    }
    fun load(): Pair<String, String> {
        val raw = prefs.getString("credentials_enc", null) ?: error("Chưa lưu API Key.")
        val json = JSONObject(crypto.decrypt(raw))
        return json.getString("key") to json.getString("secret")
    }
    fun clear() { prefs.edit().remove("credentials_enc").apply() }
}

class BinanceReadOnlyClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun fetchAccount(apiKey: String, secret: String): BinanceReadOnlySnapshot = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank() && secret.isNotBlank()) { "Thiếu thông tin API." }
        val spotJson = signedGet("https://api.binance.com/api/v3/account", apiKey, secret)
        val futuresJson = signedGet("https://fapi.binance.com/fapi/v2/account", apiKey, secret)
        val positionsJson = signedGetArray("https://fapi.binance.com/fapi/v2/positionRisk", apiKey, secret)
        val spot = mutableListOf<BinanceAssetBalance>()
        val spotArray = spotJson.optJSONArray("balances") ?: org.json.JSONArray()
        for (i in 0 until spotArray.length()) {
            val item = spotArray.getJSONObject(i)
            val free = item.optString("free").toDoubleOrNull() ?: 0.0
            val locked = item.optString("locked").toDoubleOrNull() ?: 0.0
            if (free != 0.0 || locked != 0.0) spot += BinanceAssetBalance(item.optString("asset"), free, locked)
        }
        val futures = mutableListOf<BinanceAssetBalance>()
        val futuresArray = futuresJson.optJSONArray("assets") ?: org.json.JSONArray()
        for (i in 0 until futuresArray.length()) {
            val item = futuresArray.getJSONObject(i)
            val wallet = item.optString("walletBalance").toDoubleOrNull() ?: 0.0
            val unrealized = item.optString("unrealizedProfit").toDoubleOrNull() ?: 0.0
            val margin = item.optString("marginBalance").toDoubleOrNull() ?: wallet + unrealized
            if (wallet != 0.0 || unrealized != 0.0 || margin != 0.0) futures += BinanceAssetBalance(item.optString("asset"), 0.0, 0.0, wallet, unrealized)
        }
        val positions = mutableListOf<BinanceOpenPosition>()
        for (i in 0 until positionsJson.length()) {
            val item = positionsJson.getJSONObject(i)
            val amount = item.optString("positionAmt").toDoubleOrNull() ?: 0.0
            if (amount == 0.0) continue
            positions += BinanceOpenPosition(
                item.optString("symbol"), amount,
                item.optString("entryPrice").toDoubleOrNull() ?: 0.0,
                item.optString("unRealizedProfit").toDoubleOrNull() ?: 0.0,
                item.optString("leverage").toIntOrNull() ?: 1,
                item.optString("isolated").equals("true", true)
            )
        }
        BinanceReadOnlySnapshot(spot.sortedBy { it.asset }, futures.sortedBy { it.asset }, positions, System.currentTimeMillis())
    }

    private fun signedGet(base: String, apiKey: String, secret: String): JSONObject =
        JSONObject(signedRequest(base, apiKey, secret))

    private fun signedGetArray(base: String, apiKey: String, secret: String): org.json.JSONArray =
        org.json.JSONArray(signedRequest(base, apiKey, secret))

    private fun signedRequest(base: String, apiKey: String, secret: String): String {
        val query = "recvWindow=5000&timestamp=${System.currentTimeMillis()}"
        val signature = hmacSha256(secret, query)
        val request = Request.Builder().url("$base?$query&signature=$signature")
            .header("X-MBX-APIKEY", apiKey).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(body).optInt("code") }.getOrNull()
                val reason = when (code) {
                    -2015 -> "API Key không hợp lệ, thiếu quyền đọc hoặc bị giới hạn IP."
                    -1021 -> "Sai lệch thời gian thiết bị; hãy bật ngày giờ tự động."
                    -1022 -> "Chữ ký không hợp lệ; kiểm tra API Secret trên thiết bị."
                    else -> "Binance từ chối yêu cầu (HTTP ${response.code}${code?.let { ", mã $it" } ?: ""})."
                }
                error(reason)
            }
            return body
        }
    }

    private fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
