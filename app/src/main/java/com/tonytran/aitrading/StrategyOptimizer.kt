package com.tonytran.aitrading

import kotlin.math.max

data class OptimizerCandidateResult(
    val config: StrategyConfig,
    val folds: List<BacktestMetrics>,
    val averageReturnPct: Double,
    val worstDrawdownPct: Double,
    val profitableFolds: Int,
    val totalTrades: Int
) {
    val metrics: BacktestMetrics
        get() = BacktestMetrics(
            trades = totalTrades,
            winRatePct = if (folds.isEmpty()) 0.0 else folds.map { it.winRatePct }.average(),
            netReturnPct = averageReturnPct,
            maxDrawdownPct = worstDrawdownPct,
            profitFactor = if (folds.isEmpty()) 0.0 else folds.map { it.profitFactor }.average(),
            averageTradePct = if (folds.isEmpty()) 0.0 else folds.map { it.averageTradePct }.average()
        )
}

data class StrategyOptimizationReport(
    val symbol: String,
    val baseline: OptimizerCandidateResult,
    val winner: OptimizerCandidateResult?,
    val promoted: Boolean,
    val testedCandidates: Int,
    val validationCandles: Int,
    val rankedCandidates: List<OptimizerCandidateResult>,
    val learningSamples: Int,
    val learningAccuracyPct: Double?,
    val explanation: String,
    val testedAt: Long = System.currentTimeMillis()
)

object StrategyOptimizer {
    /**
     * Chronological holdout: first 50% is not used for scoring; last 50% is split
     * into three consecutive validation windows. Candidate parameters are fixed
     * before those windows are scored. No random shuffle / future data leakage.
     */
    fun optimize(
        candles: List<Candle>,
        baseline: StrategyConfig,
        learningRecords: List<PredictionRecord> = emptyList()
    ): StrategyOptimizationReport {
        require(candles.size >= 700) { "Cần ít nhất 700 nến 15m để kiểm chứng 3 giai đoạn." }
        val ordered = candles.distinctBy { it.openTime }.sortedBy { it.openTime }
        val holdoutStart = ordered.size / 2
        val holdout = ordered.drop(holdoutStart)
        val foldSize = holdout.size / 3
        val folds = (0..2).map { index ->
            val start = index * foldSize
            val end = if (index == 2) holdout.size else (index + 1) * foldSize
            holdout.subList(start, end)
        }
        require(folds.all { it.size >= 100 }) {
            "Mỗi giai đoạn kiểm chứng cần ít nhất 100 nến. Tải thêm dữ liệu."
        }

        val directional = learningRecords.filter {
            (it.signal == "LONG" || it.signal == "SHORT") &&
                (it.status == PredictionStatus.CORRECT || it.status == PredictionStatus.INCORRECT)
        }
        val learningAccuracy = if (directional.isEmpty()) null else
            directional.count { it.status == PredictionStatus.CORRECT }.toDouble() / directional.size * 100.0
        val candidates = generateCandidates(baseline, directional.size, learningAccuracy)
        val baseResult = evaluate(baseline, folds)
        val evaluated = candidates.map { evaluate(it, folds) }
        val ranked = evaluated.sortedByDescending {
            it.averageReturnPct - it.worstDrawdownPct * 0.35 + it.profitableFolds * 0.15
        }
        val winner = ranked.filter { qualifies(it, baseResult, baseline) }.firstOrNull()

        val promoted = winner != null
        val explanation = when {
            winner == null -> "Không có bộ tham số nào vượt baseline ổn định qua 3 giai đoạn. Giữ nguyên chiến lược hiện tại."
            else -> "Có candidate vượt tiêu chí kiểm chứng 3 giai đoạn. Đã tạo đề xuất chờ phê duyệt; chưa thay đổi cấu hình đang chạy."
        }
        return StrategyOptimizationReport(
            symbol = "LOCAL_DATASET",
            baseline = baseResult,
            winner = winner,
            promoted = promoted,
            testedCandidates = evaluated.size,
            validationCandles = folds.sumOf { it.size },
            rankedCandidates = ranked.take(3),
            learningSamples = directional.size,
            learningAccuracyPct = learningAccuracy,
            explanation = explanation
        )
    }

    private fun generateCandidates(
        base: StrategyConfig,
        learningSamples: Int,
        learningAccuracyPct: Double?
    ): List<StrategyConfig> {
        val variants = listOf(
            -0.30 to -2.0, -0.15 to -1.0, 0.0 to 0.0,
            0.15 to 1.0, 0.30 to 2.0, 0.45 to 3.0,
            -0.15 to 2.0, 0.15 to -2.0, 0.30 to -1.0
        )
        val learningBias = when {
            learningSamples < 20 || learningAccuracyPct == null -> emptyList()
            learningAccuracyPct < 45.0 -> listOf(0.20 to 1.5, 0.35 to 2.5)
            learningAccuracyPct >= 60.0 -> listOf(-0.10 to -0.5)
            else -> emptyList()
        }
        return (variants + learningBias).mapIndexed { index, (volumeDelta, rsiDelta) ->
            val min = (base.rsiLongMin + rsiDelta).coerceIn(40.0, 62.0)
            val max = (base.rsiLongMax + rsiDelta).coerceIn(min + 2.0, 75.0)
            base.copy(
                name = "AutoCandidate-${index + 1}",
                volumeRatioMin = (base.volumeRatioMin + volumeDelta).coerceIn(0.8, 2.8),
                rsiLongMin = min,
                rsiLongMax = max
            )
        }.distinctBy { "${it.volumeRatioMin}:${it.rsiLongMin}:${it.rsiLongMax}" }
    }

    private fun evaluate(config: StrategyConfig, folds: List<List<Candle>>): OptimizerCandidateResult {
        val metrics = folds.map { BacktestEngine.run(it, config) }
        val returns = metrics.map { it.netReturnPct }
        return OptimizerCandidateResult(
            config = config,
            folds = metrics,
            averageReturnPct = returns.average(),
            worstDrawdownPct = metrics.maxOfOrNull { it.maxDrawdownPct } ?: 0.0,
            profitableFolds = metrics.count { it.netReturnPct > 0.0 },
            totalTrades = metrics.sumOf { it.trades }
        )
    }

    private fun qualifies(
        candidate: OptimizerCandidateResult,
        baseline: OptimizerCandidateResult,
        policy: StrategyConfig
    ): Boolean {
        val improvement = candidate.averageReturnPct - baseline.averageReturnPct
        return candidate.totalTrades >= 15 &&
            candidate.profitableFolds >= 2 &&
            candidate.worstDrawdownPct <= policy.maxValidationDrawdownPct &&
            candidate.averageReturnPct > baseline.averageReturnPct &&
            improvement >= 0.25 &&
            candidate.folds.all { it.trades >= 3 } &&
            candidate.folds.indices.count { i ->
                candidate.folds[i].netReturnPct > baseline.folds[i].netReturnPct
            } >= 2
    }
}
