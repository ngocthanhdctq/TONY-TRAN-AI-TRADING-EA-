package com.tonytran.aitrading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only PAPER ledger persistence. Uses app-private SharedPreferences;
 * no account credentials or exchange private data are stored here.
 */
class PaperLedgerStore(context: Context) {
    private val prefs = context.getSharedPreferences("tony_paper_ledger_v27", Context.MODE_PRIVATE)
    private val encryptedPrefs = context.getSharedPreferences("tony_paper_ledger_secure_v29", Context.MODE_PRIVATE)
    private val crypto = KeystoreAesGcm("tony_paper_ledger_aes_gcm_v29")

    fun load(): PaperLedgerSnapshot? {
        val encrypted = encryptedPrefs.getString("snapshot_enc", null)
        val raw = if (encrypted != null) {
            runCatching { crypto.decrypt(encrypted) }.getOrNull() ?: return null
        } else {
            // One-time migration from the previous app-private plaintext snapshot.
            prefs.getString("snapshot", null) ?: return null
        }
        val snapshot = runCatching {
            val root = JSONObject(raw)
            val walletJson = root.getJSONObject("wallet")
            val wallet = Wallet(
                spot = walletJson.getDouble("spot"),
                futures = walletJson.getDouble("futures"),
                reserve = walletJson.getDouble("reserve")
            )
            require(wallet.spot.isFinite() && wallet.futures.isFinite() && wallet.reserve.isFinite())
            require(wallet.spot >= 0.0 && wallet.futures >= 0.0 && wallet.reserve >= 0.0)

            val array = root.optJSONArray("trades") ?: JSONArray()
            val restored = mutableListOf<PaperTrade>()
            for (i in 0 until array.length()) {
                val j = array.optJSONObject(i) ?: continue
                val market = runCatching { MarketType.valueOf(j.getString("market")) }.getOrNull() ?: continue
                val side = runCatching { Side.valueOf(j.getString("side")) }.getOrNull() ?: continue
                val status = runCatching { TradeStatus.valueOf(j.getString("status")) }.getOrNull() ?: continue
                val entry = j.optDouble("entry", Double.NaN)
                val quantity = j.optDouble("quantity", Double.NaN)
                val margin = j.optDouble("margin", Double.NaN)
                if (!entry.isFinite() || entry <= 0.0 || !quantity.isFinite() || quantity <= 0.0 ||
                    !margin.isFinite() || margin <= 0.0) continue
                val exitValue = if (j.isNull("exit")) null else j.optDouble("exit", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                restored += PaperTrade(
                    id = j.getLong("id"),
                    symbol = j.getString("symbol").take(24),
                    market = market,
                    side = side,
                    entry = entry,
                    quantity = quantity,
                    margin = margin,
                    leverage = j.optInt("leverage", 1).coerceIn(1, 10),
                    openedAt = j.optLong("openedAt"),
                    status = status,
                    exit = exitValue,
                    pnl = j.optDouble("pnl", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    fees = j.optDouble("fees", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                    note = j.optString("note").take(300),
                    closedAt = if (j.isNull("closedAt")) null else j.optLong("closedAt").takeIf { it > 0L }
                )
            }
            PaperLedgerSnapshot(wallet, restored.takeLast(1000), maxOf(1L, root.optLong("nextId", 1L)))
        }.getOrNull()
        if (encrypted == null && snapshot != null) {
            // Encrypt successfully before removing the legacy copy.
            if (runCatching { save(snapshot) }.isSuccess) prefs.edit().remove("snapshot").apply()
        }
        return snapshot
    }

    fun save(snapshot: PaperLedgerSnapshot) {
        val root = JSONObject().apply {
            put("savedAt", System.currentTimeMillis())
            put("nextId", snapshot.nextId)
            put("wallet", JSONObject().apply {
                put("spot", snapshot.wallet.spot)
                put("futures", snapshot.wallet.futures)
                put("reserve", snapshot.wallet.reserve)
            })
            put("trades", JSONArray().apply {
                snapshot.trades.takeLast(1000).forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("symbol", t.symbol)
                        put("market", t.market.name)
                        put("side", t.side.name)
                        put("entry", t.entry)
                        put("quantity", t.quantity)
                        put("margin", t.margin)
                        put("leverage", t.leverage)
                        put("openedAt", t.openedAt)
                        put("status", t.status.name)
                        if (t.exit == null) put("exit", JSONObject.NULL) else put("exit", t.exit)
                        put("pnl", t.pnl)
                        put("fees", t.fees)
                        put("note", t.note.take(300))
                        if (t.closedAt == null) put("closedAt", JSONObject.NULL) else put("closedAt", t.closedAt)
                    })
                }
            })
        }
        val ciphertext = crypto.encrypt(root.toString())
        encryptedPrefs.edit().putString("snapshot_enc", ciphertext).apply()
    }

    fun clear() {
        encryptedPrefs.edit().remove("snapshot_enc").apply()
        prefs.edit().remove("snapshot").apply()
    }
}
