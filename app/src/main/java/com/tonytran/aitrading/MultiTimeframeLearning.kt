package com.tonytran.aitrading

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

data class WalkForwardReport(
    val symbol: String,
    val candles15m: Int,
    val candles5m: Int,
    val candles1h: Int,
    val candles4h: Int,
    val samples: Int,
    val folds: Int,
    val averageAccuracy: Double,
    val baselineAccuracy: Double,
    val averagePrecisionUp: Double,
    val accepted: Boolean,
    val explanation: String
)

data class AnalogResult(
    val timestamp: Long,
    val similarity: Double,
    val outcome: String,
    val forwardReturnPct: Double
)

object MultiTimeframeLearning {
    private data class Sample(val x: DoubleArray, val y: Int, val time: Long, val forwardPct: Double)

    fun trainWalkForward(
        symbol: String,
        main15m: List<Candle>,
        candles5m: List<Candle>,
        candles1h: List<Candle>,
        candles4h: List<Candle>,
        btc15m: List<Candle>
    ): Pair<MlModel?, WalkForwardReport> {
        if (main15m.size < 600) {
            return null to WalkForwardReport(symbol, main15m.size, candles5m.size, candles1h.size,
                candles4h.size, 0, 0, 0.0, 0.0, 0.0, false,
                "Cần tối thiểu 600 nến 15m để kiểm chứng nhiều giai đoạn.")
        }
        val samples = buildSamples(main15m, candles5m, candles1h, candles4h, btc15m)
        if (samples.size < 150) {
            return null to WalkForwardReport(symbol, main15m.size, candles5m.size, candles1h.size,
                candles4h.size, samples.size, 0, 0.0, 0.0, 0.0, false,
                "Không đủ mẫu có nhãn sau khi lọc nhiễu; cần thêm dữ liệu lịch sử.")
        }

        // Three expanding-window folds; leave a 4-sample purge gap before each validation window.
        val foldAcc = mutableListOf<Double>()
        val foldBase = mutableListOf<Double>()
        val foldPrecision = mutableListOf<Double>()
        val n = samples.size
        val firstTrainEnd = (n * 0.45).toInt()
        val validationSize = (n * 0.15).toInt()
        for (fold in 0 until 3) {
            val trainEnd = firstTrainEnd + fold * validationSize
            val validationStart = (trainEnd + 4).coerceAtMost(n - 1)
            val validationEnd = (validationStart + validationSize).coerceAtMost(n)
            if (trainEnd < 80 || validationEnd - validationStart < 20) continue
            val train = samples.subList(0, trainEnd)
            val validation = samples.subList(validationStart, validationEnd)
            val fitted = fit(train)
            val predictions = validation.map { fitted.first.probabilityUp(it.x) >= 0.5 }
            val actual = validation.map { it.y == 1 }
            val accuracy = predictions.indices.count { predictions[it] == actual[it] }.toDouble() / actual.size
            val baseline = max(actual.count { it }, actual.count { !it }).toDouble() / actual.size
            val tp = predictions.indices.count { predictions[it] && actual[it] }
            val fp = predictions.indices.count { predictions[it] && !actual[it] }
            val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
            foldAcc += accuracy
            foldBase += baseline
            foldPrecision += precision
        }
        if (foldAcc.size < 2) {
            return null to WalkForwardReport(symbol, main15m.size, candles5m.size, candles1h.size,
                candles4h.size, samples.size, foldAcc.size, 0.0, 0.0, 0.0, false,
                "Không đủ các fold kiểm chứng hợp lệ.")
        }
        val avgAcc = foldAcc.average()
        val avgBase = foldBase.average()
        val avgPrecision = foldPrecision.average()
        // Final candidate is fitted on all available samples only after historical fold evaluation.
        val finalFit = fit(samples)
        val accepted = samples.size >= 150 && avgAcc >= avgBase + 0.02 && avgPrecision >= 0.52
        val explanation = when {
            !accepted -> "Không vượt đủ tiêu chí walk-forward; giữ mô hình cũ."
            else -> "Vượt baseline ở các giai đoạn kiểm chứng; mô hình được phép lưu làm ứng viên nghiên cứu."
        }
        val model = if (accepted) MlModel(finalFit.first.weights.toList(), finalFit.second,
            System.currentTimeMillis(), symbol, "15m+5m+1h+4h", "LocalMTFLogisticV2") else null
        return model to WalkForwardReport(symbol, main15m.size, candles5m.size, candles1h.size,
            candles4h.size, samples.size, foldAcc.size, avgAcc, avgBase, avgPrecision, accepted, explanation)
    }

    fun findAnalogs(
        current15m: List<Candle>,
        history15m: List<Candle>,
        limit: Int = 8
    ): List<AnalogResult> {
        if (current15m.size < 100 || history15m.size < 120) return emptyList()
        val current = featureAt(current15m, current15m.lastIndex, null, null, null, null) ?: return emptyList()
        val candidates = mutableListOf<AnalogResult>()
        for (i in 100 until history15m.size - 4) {
            val f = featureAt(history15m, i, null, null, null, null) ?: continue
            val distance = euclidean(current, f)
            val forward = (history15m[i + 4].close / history15m[i].close - 1.0) * 100.0
            candidates += AnalogResult(history15m[i].openTime, 1.0 / (1.0 + distance),
                if (forward > 0.18) "UP" else if (forward < -0.18) "DOWN" else "FLAT", forward)
        }
        return candidates.sortedByDescending { it.similarity }.take(limit)
    }

    private fun buildSamples(
        main: List<Candle>, c5: List<Candle>, c1h: List<Candle>, c4h: List<Candle>, btc: List<Candle>
    ): List<Sample> {
        val samples = mutableListOf<Sample>()
        val btcMap = btc.mapIndexedNotNull { idx, candle ->
            if (idx >= 4 && btc[idx - 4].close > 0.0)
                candle.openTime to ((candle.close / btc[idx - 4].close - 1.0) * 100.0)
            else null
        }.toMap()
        for (i in 120 until main.size - 4) {
            val f = featureAt(main, i, c5, c1h, c4h, btcMap) ?: continue
            val ret = (main[i + 4].close / main[i].close - 1.0) * 100.0
            val label = when {
                ret >= 0.18 -> 1
                ret <= -0.18 -> 0
                else -> continue
            }
            samples += Sample(f, label, main[i].openTime, ret)
        }
        return samples
    }

    private fun featureAt(
        main: List<Candle>, index: Int,
        c5: List<Candle>?, c1h: List<Candle>?, c4h: List<Candle>?,
        btcMap: Map<Long, Double>?
    ): DoubleArray? {
        if (index < 100 || index >= main.size) return null
        val mainWindow = main.subList((index - 120).coerceAtLeast(0), index + 1)
        val base = Indicators.snapshot(mainWindow) ?: return null
        val time = main[index].openTime + 15L * 60L * 1000L
        fun tfFeatures(list: List<Candle>?, barsBack: Int, intervalMs: Long): List<Double> {
            if (list == null) return listOf(0.0, 0.0, 0.0)
            var idx = list.binarySearchBy(time) { it.openTime }.let { if (it >= 0) it else -it - 2 }
            // Only use fully closed higher/lower timeframe bars to avoid look-ahead leakage.
            while (idx >= 0 && list[idx].openTime + intervalMs > time) idx--
            if (idx < 60) return listOf(0.0, 0.0, 0.0)
            val snap = Indicators.snapshot(list.subList((idx - 100).coerceAtLeast(0), idx + 1))
                ?: return listOf(0.0, 0.0, 0.0)
            val spread = if (snap.ema50 > 0) (snap.ema20 / snap.ema50 - 1.0) * 100 else 0.0
            val ret = if (idx >= barsBack && list[idx-barsBack].close > 0)
                (list[idx].close / list[idx-barsBack].close - 1.0) * 100 else 0.0
            return listOf(spread.coerceIn(-5.0,5.0)/5.0, (snap.rsi-50.0)/50.0,
                ret.coerceIn(-10.0,10.0)/10.0)
        }
        val mainClose = main[index].close
        val mainRet1 = if (main[index-1].close > 0) (mainClose/main[index-1].close-1)*100 else 0.0
        val mainRet4 = if (main[index-4].close > 0) (mainClose/main[index-4].close-1)*100 else 0.0
        val atrPct = if (mainClose > 0) base.atr/mainClose*100 else 0.0
        val features = mutableListOf(
            mainRet1.coerceIn(-5.0,5.0)/5.0,
            mainRet4.coerceIn(-10.0,10.0)/10.0,
            ((base.ema20/base.ema50)-1.0).coerceIn(-0.05,0.05)/0.05,
            (base.rsi-50.0)/50.0,
            (base.macd/mainClose*100.0).coerceIn(-3.0,3.0)/3.0,
            (atrPct/8.0).coerceIn(0.0,1.0),
            (base.volumeRatio/8.0).coerceIn(0.0,1.0)
        )
        features += tfFeatures(c5, 3, 5L * 60L * 1000L)
        features += tfFeatures(c1h, 2, 60L * 60L * 1000L)
        features += tfFeatures(c4h, 1, 4L * 60L * 60L * 1000L)
        val btcRet = btcMap?.get(main[index].openTime) ?: 0.0
        features += (btcRet.coerceIn(-10.0, 10.0) / 10.0)
        return features.toDoubleArray()
    }

    private fun fit(samples: List<Sample>): Pair<MlModel, Double> {
        val w = DoubleArray(samples.first().x.size)
        var b = 0.0
        val lr = 0.06
        repeat(350) {
            val grad = DoubleArray(w.size)
            var gb = 0.0
            samples.forEach { s ->
                var z = b
                for (i in w.indices) z += w[i]*s.x[i]
                val p = 1.0/(1.0+exp(-z.coerceIn(-30.0,30.0)))
                val err = p-s.y
                for (i in w.indices) grad[i] += err*s.x[i]
                gb += err
            }
            for (i in w.indices) w[i] -= lr*(grad[i]/samples.size + 0.002*w[i])
            b -= lr*gb/samples.size
        }
        return MlModel(w.toList(), b, System.currentTimeMillis(), "training", "15m", "LocalMTFLogisticV2") to b
    }

    private fun euclidean(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        return sqrt((0 until n).sumOf { (a[it]-b[it])*(a[it]-b[it]) })
    }
}
