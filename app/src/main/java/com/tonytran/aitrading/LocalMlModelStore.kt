package com.tonytran.aitrading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LocalMlModelStore(context: Context) {
    private val prefs = context.getSharedPreferences("tony_ml_model", Context.MODE_PRIVATE)

    fun save(model: MlModel) {
        val json = JSONObject().apply {
            put("weights", JSONArray(model.weights))
            put("bias", model.bias)
            put("trainedAt", model.trainedAt)
            put("symbol", model.symbol)
            put("interval", model.interval)
            put("version", model.version)
        }
        prefs.edit().putString("model", json.toString()).apply()
    }

    fun load(): MlModel? = runCatching {
        val j = JSONObject(prefs.getString("model", null) ?: return null)
        val arr = j.getJSONArray("weights")
        val weights = (0 until arr.length()).map { arr.getDouble(it) }
        MlModel(weights, j.getDouble("bias"), j.getLong("trainedAt"),
            j.getString("symbol"), j.getString("interval"), j.optString("version", "LocalLogisticV1"))
    }.getOrNull()

    fun clear() { prefs.edit().remove("model").apply() }
}
