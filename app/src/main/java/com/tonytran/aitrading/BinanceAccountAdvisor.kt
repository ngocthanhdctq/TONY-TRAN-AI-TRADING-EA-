package com.tonytran.aitrading

import kotlin.math.abs

enum class AccountAdviceLevel { INFO, CAUTION, HIGH }
data class AccountAdvice(
    val level: AccountAdviceLevel,
    val title: String,
    val detail: String,
    val action: String,
    val symbol: String? = null
)
data class BinanceAccountAssessment(
    val riskScore: Int,
    val riskLabel: String,
    val futuresWalletUsdt: Double,
    val futuresUnrealizedUsdt: Double,
    val openPositions: Int,
    val advice: List<AccountAdvice>,
    val assessedAt: Long
)

/**
 * Transparent, deterministic on-device account assessment.
 * This is a rules engine, not a trained model or a prediction of profit.
 */
object BinanceAccountAdvisor {
    fun assess(snapshot: BinanceReadOnlySnapshot, market: List<MarketCoin>): BinanceAccountAssessment {
        val usdtFutures = snapshot.futures.firstOrNull { it.asset == "USDT" }
        val wallet = usdtFutures?.wallet ?: 0.0
        val unrealized = usdtFutures?.unrealized ?: snapshot.futures.sumOf { it.unrealized }
        var score = 0
        val advice = mutableListOf<AccountAdvice>()

        if (snapshot.positions.isEmpty()) {
            advice += AccountAdvice(AccountAdviceLevel.INFO, "Không có vị thế Futures mở",
                "Hiện không phát hiện vị thế Futures đang mở trong dữ liệu vừa tải.",
                "WAIT; chỉ cân nhắc giao dịch sau khi kiểm tra tín hiệu và giới hạn rủi ro.")
        }

        snapshot.positions.forEach { position ->
            val coin = market.firstOrNull { it.symbol == position.symbol }
            val side = if (position.amount > 0) "LONG" else "SHORT"
            if (position.leverage >= 20) {
                score += 30
                advice += AccountAdvice(AccountAdviceLevel.HIGH, "${position.symbol}: đòn bẩy cao",
                    "Vị thế $side đang dùng ${position.leverage}x; đòn bẩy lớn làm tăng tốc độ biến động PnL.",
                    "Cân nhắc giảm đòn bẩy hoặc quy mô vị thế thủ công trên Binance; AI không can thiệp tài khoản.",
                    position.symbol)
            } else if (position.leverage > 10) {
                score += 18
                advice += AccountAdvice(AccountAdviceLevel.CAUTION, "${position.symbol}: đòn bẩy trên 10x",
                    "Vị thế $side dùng ${position.leverage}x, vượt ngưỡng tham chiếu an toàn nội bộ 10x.",
                    "Rà soát margin và mức chịu lỗ; không tăng thêm vị thế.", position.symbol)
            }
            if (!position.isolated) {
                score += 20
                advice += AccountAdvice(AccountAdviceLevel.CAUTION, "${position.symbol}: Cross Margin",
                    "Vị thế đang dùng Cross; số dư ký quỹ khả dụng trong tài khoản có thể chịu ảnh hưởng.",
                    "Kiểm tra mức ký quỹ và cân nhắc Isolated nếu phù hợp với kế hoạch của bạn.",
                    position.symbol)
            }
            if (position.unrealized < 0.0) {
                score += if (wallet > 0 && abs(position.unrealized) / wallet >= 0.10) 25 else 12
                advice += AccountAdvice(
                    if (wallet > 0 && abs(position.unrealized) / wallet >= 0.10) AccountAdviceLevel.HIGH else AccountAdviceLevel.CAUTION,
                    "${position.symbol}: PnL đang âm",
                    "PnL chưa thực hiện ${"%.4f".format(java.util.Locale.US, position.unrealized)} USDT.",
                    "Đánh giá lại luận điểm vào lệnh và mức dừng lỗ đã đặt trên sàn; không bình quân giá tự động.",
                    position.symbol)
            }
            if (coin != null && coin.signal != "WAIT") {
                val aligned = (side == "LONG" && coin.signal == "BUY") || (side == "SHORT" && coin.signal == "SELL")
                if (!aligned) {
                    score += 15
                    advice += AccountAdvice(AccountAdviceLevel.CAUTION, "${position.symbol}: tín hiệu ngược vị thế",
                        "AI kỹ thuật hiện báo ${coin.signal} (${coin.score}/100), không cùng hướng với vị thế $side.",
                        "Kiểm tra đa khung thời gian; không đóng/mở tự động.", position.symbol)
                } else {
                    advice += AccountAdvice(AccountAdviceLevel.INFO, "${position.symbol}: tín hiệu cùng hướng",
                        "Tín hiệu ${coin.signal} (${coin.score}/100) đang cùng hướng vị thế $side; đây không phải bảo đảm xu hướng tiếp tục.",
                        "Tiếp tục theo dõi biến động và mức rủi ro.")
                }
            }
        }

        if (unrealized < 0.0 && wallet > 0 && abs(unrealized) / wallet >= 0.05) {
            score += 15
            advice += AccountAdvice(AccountAdviceLevel.HIGH, "Lỗ thả nổi đáng chú ý",
                "PnL chưa thực hiện âm tương đương ít nhất 5% số dư Futures USDT.",
                "Kiểm tra margin ratio và giá thanh lý trực tiếp trên Binance.")
        }

        val bounded = score.coerceIn(0, 100)
        val label = when {
            bounded >= 60 -> "RỦI RO CAO"
            bounded >= 25 -> "CẦN CHÚ Ý"
            else -> "RỦI RO THẤP THEO DỮ LIỆU HIỆN CÓ"
        }
        if (advice.isEmpty()) advice += AccountAdvice(AccountAdviceLevel.INFO,
            "Chưa thấy cảnh báo theo bộ quy tắc", "Dữ liệu hiện tại không kích hoạt ngưỡng cảnh báo đã cấu hình.",
            "WAIT khi thiếu tín hiệu; tiếp tục theo dõi.")
        return BinanceAccountAssessment(bounded, label, wallet, unrealized,
            snapshot.positions.size, advice, System.currentTimeMillis())
    }
}
