
package com.tonytran.aitrading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class DiagnosticResult(
    val name: String,
    val passed: Boolean,
    val detail: String
)

object SystemDiagnostics {
    fun run(): List<DiagnosticResult> {
        val results = mutableListOf<DiagnosticResult>()

        fun check(name: String, detail: String, condition: Boolean) {
            results += DiagnosticResult(name, condition, detail)
        }

        val spotBelowMinimum = SafetyCore.checkSpot(4.99, 5.0, 0)
        check("Chặn Spot dưới mức tối thiểu", spotBelowMinimum.reason, !spotBelowMinimum.allowed)

        val spotOverCap = SafetyCore.checkSpot(5.01, 10.0, 0)
        check("Chặn Spot vượt 5 USDT", spotOverCap.reason, !spotOverCap.allowed)

        val spotAtLimit = SafetyCore.checkSpot(5.0, 5.0, 0)
        check("Cho phép Spot đúng giới hạn", spotAtLimit.reason, spotAtLimit.allowed)

        val futuresOverMargin = SafetyCore.checkFutures(2.01, 10, 5.0, 0)
        check("Chặn Futures margin trên 2 USDT", futuresOverMargin.reason, !futuresOverMargin.allowed)

        val futuresOverLeverage = SafetyCore.checkFutures(1.0, 11, 5.0, 0)
        check("Chặn đòn bẩy trên 10x", futuresOverLeverage.reason, !futuresOverLeverage.allowed)

        val positionLimit = SafetyCore.checkFutures(1.0, 10, 5.0, 3)
        check("Chặn vị thế thứ tư", positionLimit.reason, !positionLimit.allowed)

        val invalidNumber = SafetyCore.checkSpot(Double.NaN, 5.0, 0)
        check("Chặn dữ liệu số không hợp lệ", invalidNumber.reason, !invalidNumber.allowed)

        val paper = PaperTradingEngine()
        val before = paper.wallet
        val invalidPriceMessage = paper.openSpot("BTCUSDT", 0.0, 5.0)
        check(
            "Giá không hợp lệ không làm đổi số dư",
            invalidPriceMessage,
            paper.wallet == before && paper.history().isEmpty()
        )

        val openMessage = paper.openSpot("BTCUSDT", 60000.0, 5.0)
        val opened = paper.openTrades().singleOrNull()
        check(
            "PAPER mở lệnh trong giới hạn",
            openMessage,
            opened != null && paper.wallet.spot == 0.0
        )

        val closeMessage = opened?.let { paper.close(it.id, 60000.0, "System diagnostic") } ?: "Không có lệnh để đóng."
        check(
            "PAPER đóng lệnh và ghi lịch sử",
            closeMessage,
            paper.openTrades().isEmpty() && paper.history().singleOrNull()?.status == TradeStatus.CLOSED
        )

        val fresh = PaperTradingEngine()
        val futuresMessage = fresh.openFutures("ETHUSDT", 3000.0, 2.0, Side.LONG)
        val futuresOpened = fresh.openTrades().singleOrNull()
        check(
            "PAPER Futures Isolated 10x",
            futuresMessage,
            futuresOpened?.market == MarketType.FUTURES && futuresOpened.leverage == 10
        )

        val nanPriceEngine = PaperTradingEngine()
        val nanPriceResult = nanPriceEngine.openSpot("BTCUSDT", Double.NaN, 5.0)
        check(
            "Chặn giá NaN khi mở Spot",
            nanPriceResult,
            nanPriceEngine.history().isEmpty() && nanPriceEngine.wallet.spot == 5.0
        )

        val infinitePriceEngine = PaperTradingEngine()
        val infinitePriceResult = infinitePriceEngine.openFutures("ETHUSDT", Double.POSITIVE_INFINITY, 1.0, Side.LONG)
        check(
            "Chặn giá vô cực khi mở Futures",
            infinitePriceResult,
            infinitePriceEngine.history().isEmpty() && infinitePriceEngine.wallet.futures == 2.0
        )

        val invalidSymbolEngine = PaperTradingEngine()
        val invalidSymbolResult = invalidSymbolEngine.openSpot("BTC/USDT", 60_000.0, 5.0)
        check(
            "Chặn mã giao dịch sai định dạng",
            invalidSymbolResult,
            invalidSymbolEngine.history().isEmpty()
        )

        val closeGuardEngine = PaperTradingEngine()
        closeGuardEngine.openSpot("BTCUSDT", 60_000.0, 5.0)
        val closeGuardTrade = closeGuardEngine.openTrades().single()
        val badCloseResult = closeGuardEngine.close(closeGuardTrade.id, Double.NaN)
        check(
            "Chặn giá NaN khi đóng lệnh",
            badCloseResult,
            closeGuardEngine.openTrades().size == 1 && closeGuardEngine.wallet.spot == 0.0
        )
        val validCloseResult = closeGuardEngine.close(closeGuardTrade.id, 61_000.0)
        val duplicateCloseResult = closeGuardEngine.close(closeGuardTrade.id, 62_000.0)
        check(
            "Không cho đóng trùng một vị thế",
            "$validCloseResult | $duplicateCloseResult",
            closeGuardEngine.history().count { it.status == TradeStatus.CLOSED } == 1 &&
                closeGuardEngine.openTrades().isEmpty()
        )

        val snapshotSource = PaperTradingEngine()
        snapshotSource.openFutures("ETHUSDT", 3_000.0, 1.0, Side.SHORT)
        val restoredFromSnapshot = PaperTradingEngine(initialSnapshot = snapshotSource.snapshot())
        check(
            "Khôi phục snapshot giữ nguyên vị thế PAPER",
            "Vị thế ${restoredFromSnapshot.openTrades().size}",
            restoredFromSnapshot.openTrades().size == 1 &&
                restoredFromSnapshot.openTrades().single().side == Side.SHORT &&
                restoredFromSnapshot.wallet == snapshotSource.wallet
        )

        val analytics = PaperAnalytics.calculate(Wallet(), emptyList(), emptyMap())
        check(
            "Analytics vốn ban đầu 10 USDT",
            "Equity ${"%.2f".format(analytics.estimatedEquity)} USDT",
            analytics.estimatedEquity == 10.0 && analytics.closedTrades == 0
        )

        val sampleClosed = PaperTrade(
            id = 1L, symbol = "BTCUSDT", market = MarketType.SPOT, side = Side.BUY,
            entry = 100.0, quantity = 0.049, margin = 5.0, leverage = 1,
            openedAt = 1L, status = TradeStatus.CLOSED, exit = 110.0,
            pnl = 0.40, fees = 0.10, closedAt = 2L
        )
        val sampleReport = PaperAnalytics.calculate(
            Wallet(spot = 5.40, futures = 2.0, reserve = 3.0),
            listOf(sampleClosed), emptyMap()
        )
        check(
            "Analytics PnL và Win rate",
            "PnL ${"%.2f".format(sampleReport.realizedPnl)} USDT • Win rate ${"%.1f".format(sampleReport.winRatePct)}%",
            sampleReport.realizedPnl == 0.40 && sampleReport.winRatePct == 100.0
        )

        val riskyAccount = BinanceReadOnlySnapshot(
            spot = emptyList(),
            futures = listOf(BinanceAssetBalance("USDT", 0.0, 0.0, wallet = 100.0, unrealized = -8.0)),
            positions = listOf(BinanceOpenPosition("BTCUSDT", 0.01, 60000.0, -8.0, 25, false)),
            fetchedAt = 1L
        )
        val riskyAssessment = BinanceAccountAdvisor.assess(riskyAccount, emptyList())
        check(
            "AI Advisor phát hiện đòn bẩy cao và Cross",
            "${riskyAssessment.riskLabel} • ${riskyAssessment.riskScore}/100",
            riskyAssessment.riskScore >= 25 &&
                riskyAssessment.advice.any { it.title.contains("đòn bẩy cao") } &&
                riskyAssessment.advice.any { it.title.contains("Cross Margin") }
        )

        val emptyAccount = BinanceReadOnlySnapshot(emptyList(), emptyList(), emptyList(), 1L)
        val emptyAssessment = BinanceAccountAdvisor.assess(emptyAccount, emptyList())
        check(
            "AI Advisor WAIT khi không có vị thế",
            emptyAssessment.advice.joinToString { it.title },
            emptyAssessment.advice.any { it.title.contains("Không có vị thế") } &&
                emptyAssessment.advice.any { it.action.startsWith("WAIT") }
        )

        
        val marketIntelligenceCoins = listOf(
            MarketCoin(
                symbol = "BTCUSDT",
                price = 50000.0,
                changePct = 2.0,
                quoteVolume = 1_000_000_000.0,
                score = 85,
                signal = "LONG",
                atrPct = 1.2,
                volumeRatio = 1.8,
                trend1h = "BULLISH"
            ),
            MarketCoin(
                symbol = "ETHUSDT",
                price = 3000.0,
                changePct = 1.0,
                quoteVolume = 500_000_000.0,
                score = 82,
                signal = "LONG",
                atrPct = 1.0,
                volumeRatio = 1.5,
                trend1h = "BULLISH"
            ),
            MarketCoin(
                symbol = "SOLUSDT",
                price = 100.0,
                changePct = 5.0,
                quoteVolume = 100_000_000.0,
                score = 95,
                signal = "LONG",
                atrPct = 4.0,
                volumeRatio = 2.0,
                trend1h = "BULLISH"
            )
        )

        val intelligence = MarketIntelligenceEngine.analyze(
            marketIntelligenceCoins,
            null
        )

        val sol = intelligence.items.firstOrNull {
            it.symbol == "SOLUSDT"
        }

        check(
            "AI Market Intelligence chặn ATR% cao",
            sol?.reasons?.joinToString() ?: "Không có kết quả SOLUSDT",
            sol?.let {
                it.decision == "WAIT" &&
                    it.reasons.any { reason -> reason.contains("ATR% cao") }
            } == true
        )

        check(
            "Signal Learning LONG đúng khi giá tăng đủ ngưỡng",
            "LONG +0.20% phải được đánh dấu CORRECT",
            PredictionOutcomeEvaluator.evaluate("LONG", 0.20) == PredictionStatus.CORRECT
        )

        check(
            "Signal Learning SHORT đúng khi giá giảm đủ ngưỡng",
            "SHORT -0.20% phải được đánh dấu CORRECT",
            PredictionOutcomeEvaluator.evaluate("SHORT", -0.20) == PredictionStatus.CORRECT
        )

        check(
            "Signal Learning không chấm vùng nhiễu là thắng/thua",
            "Biến động +0.05% phải là FLAT",
            PredictionOutcomeEvaluator.evaluate("LONG", 0.05) == PredictionStatus.FLAT
        )

        check(
            "WAIT được đánh giá riêng, không tính là lệnh thắng",
            "WAIT +0.60% => AVOIDED_MOVE; +0.10% => FLAT",
            PredictionOutcomeEvaluator.evaluate("WAIT", 0.60) == PredictionStatus.AVOIDED_MOVE &&
                PredictionOutcomeEvaluator.evaluate("WAIT", 0.10) == PredictionStatus.FLAT
        )
                return results

    }
}

@Composable
fun SystemDiagnosticsScreen() {
    val results = remember { mutableStateListOf<DiagnosticResult>() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("SYSTEM DIAGNOSTICS", style = MaterialTheme.typography.titleLarge)
        Text("Bộ kiểm tra cục bộ cho Safety Core và sổ PAPER. Không gửi lệnh lên Binance.")
        Button(
            onClick = {
                results.clear()
                results.addAll(SystemDiagnostics.run())
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("CHẠY KIỂM TRA HỆ THỐNG") }

        if (results.isNotEmpty()) {
            val passed = results.count { it.passed }
            val failed = results.size - passed
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (failed == 0) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("KẾT QUẢ: $passed/${results.size} đạt", style = MaterialTheme.typography.titleMedium)
                    Text("Không đạt: $failed")
                }
            }
            results.forEach { item ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (item.passed) "ĐẠT" else "LỖI",
                                color = if (item.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Text(item.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            HorizontalDivider()
            Text("Chưa chạy kiểm tra.", style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider()
        Text("Phạm vi kiểm tra", style = MaterialTheme.typography.titleMedium)
        Text("• Giới hạn Spot 5 USDT và mức tối thiểu mô phỏng.")
        Text("• Futures margin tối đa 2 USDT, đòn bẩy tối đa 10x.")
        Text("• Tối đa 3 vị thế mở.")
        Text("• Chặn giá NaN/vô cực, mã giao dịch sai định dạng và giá đóng không hợp lệ.")
        Text("• Chặn đóng trùng vị thế và kiểm tra khôi phục snapshot trong bộ nhớ.")
        Text("• Mở/đóng lệnh PAPER và cập nhật lịch sử.")
        Text("• Không kiểm tra kết nối mạng, độ trễ Binance, hiệu năng thiết bị hoặc khả năng build APK.")
    }
}
