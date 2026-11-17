package com.tonytran.aitrading

/**
 * V0.7 transparent decision fusion.
 * News is a risk veto / confidence modifier only; headlines never create LONG or SHORT.
 * Historical model is reported as context but is not used as a directional vote until
 * its exact feature pipeline and out-of-sample calibration are connected.
 */
data class FusedDecision(
    val symbol: String,
    val price: Double,
    val decision: String,
    val confidence: Int,
    val technicalSignal: String,
    val technicalScore: Int,
    val newsRisk: String,
    val criticalNews: Int,
    val highNews: Int,
    val reasons: List<String>,
    val caution: String
)

object DecisionFusionEngine {
    fun evaluate(
        coin: MarketCoin,
        digest: NewsDigest?,
        historicalModel: MlModel? = null
    ): FusedDecision {
        val articles = digest?.articles.orEmpty()
        val critical = articles.count { it.impact == NewsImpact.CRITICAL && isRelevant(it) }
        val high = articles.count { it.impact == NewsImpact.HIGH && isRelevant(it) }
        val medium = articles.count { it.impact == NewsImpact.MEDIUM && isRelevant(it) }

        val reasons = mutableListOf<String>()
        val tech = coin.signal
        var confidence = coin.score.coerceIn(0, 100)
        var decision = if (tech == "LONG" || tech == "SHORT") tech else "WAIT"

        if (tech == "LONG" || tech == "SHORT") {
            reasons += "Kỹ thuật: $tech, điểm ${coin.score}/100"
        } else {
            reasons += "Kỹ thuật chưa đủ đồng thuận để vào lệnh"
        }

        val risk = when {
            critical > 0 -> "RẤT CAO"
            high >= 2 || (high >= 1 && medium >= 2) -> "CAO"
            high == 1 || medium >= 2 -> "TRUNG BÌNH"
            else -> "THẤP"
        }

        when {
            critical > 0 -> {
                decision = "WAIT"
                confidence = minOf(confidence, 35)
                reasons += "Có $critical tin RẤT CAO liên quan thị trường: khóa tín hiệu mới"
            }
            high >= 2 || (high >= 1 && medium >= 2) -> {
                decision = "WAIT"
                confidence = minOf(confidence, 45)
                reasons += "Cụm tin rủi ro cao: chờ thị trường phản ứng và ổn định"
            }
            high == 1 -> {
                confidence = (confidence - 15).coerceAtLeast(0)
                reasons += "Có tin tác động CAO: giảm độ tin cậy, cần xác nhận giá"
            }
            medium >= 2 -> {
                confidence = (confidence - 8).coerceAtLeast(0)
                reasons += "Nhiều tin trung bình: giảm nhẹ độ tin cậy"
            }
            else -> reasons += "Không phát hiện cụm tin rủi ro nổi bật trong dữ liệu hiện có"
        }

        val newsUnavailable = digest == null || digest.sourceCount == 0
        val newsStale = digest != null && digest.fetchedAt > 0L &&
            System.currentTimeMillis() - digest.fetchedAt > 24L * 60L * 60L * 1000L
        if (newsUnavailable || newsStale) {
            decision = "WAIT"
            confidence = minOf(confidence, 40)
            reasons += if (newsUnavailable) {
                "Không xác minh được nguồn tin: chuyển WAIT"
            } else {
                "Dữ liệu tin tức quá 24 giờ: chuyển WAIT"
            }
        }

        if (decision != "WAIT" && confidence < 60) {
            decision = "WAIT"
            reasons += "Độ tin cậy sau hợp nhất dưới ngưỡng 60"
        }

        val modelContext = when {
            historicalModel == null -> "Chưa có mô hình lịch sử được lưu"
            historicalModel.symbol != coin.symbol -> "Mô hình lịch sử thuộc ${historicalModel.symbol}, không dùng cho ${coin.symbol}"
            else -> "Có mô hình lịch sử ${historicalModel.version}; chưa bỏ phiếu hướng vì chưa đồng bộ pipeline đặc trưng"
        }
        reasons += modelContext

        val caution = when {
            newsUnavailable -> "Không xác minh được tin tức: giữ WAIT, không giao dịch."
            newsStale -> "Tin tức đã cũ hơn 24 giờ: giữ WAIT cho đến khi cập nhật."
            else -> "Tin tức chỉ là bộ lọc rủi ro, không dự báo hướng giá. Chờ nến đóng và xác nhận spread/thanh khoản."
        }

        return FusedDecision(
            symbol = coin.symbol,
            price = coin.price,
            decision = decision,
            confidence = confidence,
            technicalSignal = tech,
            technicalScore = coin.score,
            newsRisk = risk,
            criticalNews = critical,
            highNews = high,
            reasons = reasons,
            caution = caution
        )
    }

    private fun isRelevant(article: NewsArticle): Boolean {
        val text = (article.title + " " + article.summary + " " + article.topics.joinToString(" "))
            .lowercase()
        val terms = listOf(
            "crypto", "bitcoin", "ethereum", "digital asset", "stablecoin", "exchange",
            "sec", "cftc", "federal reserve", "fed", "fomc", "interest rate",
            "inflation", "consumer price", "employment", "nonfarm", "treasury",
            "tariff", "sanction", "war", "geopolitical", "etf", "liquidation",
            "hack", "exploit", "breach", "bankruptcy", "depeg"
        )
        return terms.any { text.contains(it) }
    }
}
