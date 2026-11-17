package com.tonytran.aitrading

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class HistoricalSample(
    val features: DoubleArray,
    val label: Int,
    val timestamp: Long
)

data class MlModel(
    val weights: List<Double>,
    val bias: Double,
    val trainedAt: Long,
    val symbol: String,
    val interval: String,
    val version: String = "LocalLogisticV1"
) {
    fun probabilityUp(features: DoubleArray): Double {
        if (features.size != weights.size) return 0.5
        var z = bias
        for (i in features.indices) z += weights[i] * features[i]
        return 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
    }
}

data class HistoricalTrainingReport(
    val symbol: String,
    val candles: Int,
    val trainSamples: Int,
    val validationSamples: Int,
    val validationAccuracy: Double,
    val baselineAccuracy: Double,
    val precisionUp: Double,
    val recallUp: Double,
    val promoted: Boolean,
    val message: String
)

/**
 * Builds time-ordered features from historical OHLCV.
 * Features use current/past candles only; labels use a future horizon.
 */
object HistoricalLearning {
    private const val HORIZON = 4
    private const val LABEL_EDGE_PCT = 0.18 // after estimated round-trip friction buffer

    fun buildSamples(candles: List<Candle>, btcCandles: List<Candle>? = null): List<HistoricalSample> {
        if (candles.size < 180) return emptyList()
        val samples = mutableListOf<HistoricalSample>()
        val btcByTime = btcCandles?.associateBy { it.openTime }.orEmpty()

        for (i in 100 until candles.size - HORIZON) {
            val past = candles.subList(0, i + 1)
            val snap = Indicators.snapshot(past) ?: continue
            val now = candles[i]
            val future = candles[i + HORIZON].close
            if (now.close <= 0.0) continue
            val forwardPct = (future / now.close - 1.0) * 100.0
            val label = when {
                forwardPct >= LABEL_EDGE_PCT -> 1
                forwardPct <= -LABEL_EDGE_PCT -> 0
                else -> continue // ignore ambiguous/no-edge samples
            }

            val closes = past.map { it.close }
            val ret1 = pctChange(closes, 1)
            val ret4 = pctChange(closes, 4)
            val ret16 = pctChange(closes, 16)
            val emaSpread = if (snap.ema50 > 0) (snap.ema20 / snap.ema50 - 1.0) * 100.0 else 0.0
            val rsiScaled = (snap.rsi - 50.0) / 50.0
            val macdScaled = if (snap.close > 0) snap.macd / snap.close * 100.0 else 0.0
            val atrPct = if (snap.close > 0) snap.atr / snap.close * 100.0 else 0.0
            val volRatio = snap.volumeRatio.coerceIn(0.0, 8.0)
            val candle = candles[i]
            val candleRange = if (candle.open > 0) (candle.high - candle.low) / candle.open * 100.0 else 0.0
            val bodyPct = if (candle.open > 0) (candle.close - candle.open) / candle.open * 100.0 else 0.0
            val btcReturn = btcByTime[candle.openTime]?.let { btc ->
                val btcIdx = btcCandles?.indexOfFirst { it.openTime == btc.openTime } ?: -1
                if (btcIdx >= 4 && btcCandles != null) {
                    val old = btcCandles[btcIdx - 4].close
                    if (old > 0) (btc.close / old - 1.0) * 100.0 else 0.0
                } else 0.0
            } ?: 0.0

            val features = doubleArrayOf(
                ret1.coerceIn(-5.0, 5.0) / 5.0,
                ret4.coerceIn(-10.0, 10.0) / 10.0,
                ret16.coerceIn(-20.0, 20.0) / 20.0,
                emaSpread.coerceIn(-5.0, 5.0) / 5.0,
                rsiScaled.coerceIn(-1.0, 1.0),
                macdScaled.coerceIn(-3.0, 3.0) / 3.0,
                atrPct.coerceIn(0.0, 8.0) / 8.0,
                volRatio / 8.0,
                candleRange.coerceIn(0.0, 8.0) / 8.0,
                bodyPct.coerceIn(-5.0, 5.0) / 5.0,
                btcReturn.coerceIn(-10.0, 10.0) / 10.0
            )
            samples += HistoricalSample(features, label, now.openTime)
        }
        return samples
    }

    private fun pctChange(values: List<Double>, bars: Int): Double {
        if (values.size <= bars) return 0.0
        val old = values[values.size - 1 - bars]
        return if (old > 0) (values.last() / old - 1.0) * 100.0 else 0.0
    }

    fun trainAndValidate(symbol: String, candles: List<Candle>, btc: List<Candle>?): Pair<MlModel?, HistoricalTrainingReport> {
        val samples = buildSamples(candles, btc)
        if (samples.size < 100) {
            return null to HistoricalTrainingReport(symbol, candles.size, 0, samples.size, 0.0, 0.0, 0.0, 0.0, false,
                "Không đủ dữ liệu mẫu có nhãn. Cần tối thiểu 100 mẫu sau khi lọc.")
        }
        val split = (samples.size * 0.70).toInt().coerceIn(1, samples.size - 1)
        val train = samples.take(split)
        val validation = samples.drop(split)

        val weights = DoubleArray(train.first().features.size)
        var bias = 0.0
        val learningRate = 0.08
        val epochs = 500
        repeat(epochs) {
            val grad = DoubleArray(weights.size)
            var biasGrad = 0.0
            train.forEach { sample ->
                val p = sigmoid(dot(weights, sample.features) + bias)
                val error = p - sample.label
                for (j in weights.indices) grad[j] += error * sample.features[j]
                biasGrad += error
            }
            for (j in weights.indices) {
                val regularization = 0.002 * weights[j]
                weights[j] -= learningRate * (grad[j] / train.size + regularization)
            }
            bias -= learningRate * biasGrad / train.size
        }

        val model = MlModel(weights.toList(), bias, System.currentTimeMillis(), symbol, "15m")
        val predictions = validation.map { sample -> model.probabilityUp(sample.features) >= 0.5 }
        val actual = validation.map { it.label == 1 }
        val accuracy = predictions.indices.count { predictions[it] == actual[it] }.toDouble() / predictions.size
        val majority = max(actual.count { it }, actual.count { !it }).toDouble() / actual.size
        val tp = predictions.indices.count { predictions[it] && actual[it] }
        val fp = predictions.indices.count { predictions[it] && !actual[it] }
        val fn = predictions.indices.count { !predictions[it] && actual[it] }
        val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)

        // Conservative promotion gate: outperform majority baseline and avoid tiny samples.
        val promoted = validation.size >= 30 && accuracy >= majority + 0.02 && precision >= 0.50
        val message = when {
            validation.size < 30 -> "Tập kiểm chứng quá nhỏ; không cập nhật mô hình."
            accuracy < majority + 0.02 -> "Không vượt baseline đơn giản; giữ mô hình cũ."
            precision < 0.50 -> "Precision chiều tăng thấp; không cập nhật mô hình."
            else -> "Mô hình vượt baseline kiểm chứng; đã đủ điều kiện đề xuất lưu."
        }
        return (if (promoted) model else null) to HistoricalTrainingReport(
            symbol, candles.size, train.size, validation.size, accuracy, majority,
            precision, recall, promoted, message
        )
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { a[it] * b[it] }
    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x.coerceIn(-30.0, 30.0)))
}
