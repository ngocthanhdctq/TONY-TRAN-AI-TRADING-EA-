package com.tonytran.aitrading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

private data class AssistantLine(
    val text: String,
    val fromUser: Boolean,
    val report: AssistantMarketReport? = null
)

private val AssistantAccent = Color(0xFFB995FF)
private val AssistantPanel = Color(0xFF241B31)
private val AssistantPanelSoft = Color(0xFF30243F)
private val AssistantPositive = Color(0xFF65C99A)
private val AssistantNegative = Color(0xFFFF858D)
private val AssistantNeutral = Color(0xFFFFC66D)

@Composable
fun MarketAssistantScreen(
    coins: List<MarketCoin>,
    onPick: (String) -> Unit,
    onAnalyze: suspend (String) -> AssistantMarketReport?
) {
    var question by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("BTCUSDT") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Chọn mã và nhấn Phân tích đa khung thời gian để tải nến mới từ Binance.") }
    var lines by remember {
        mutableStateOf(listOf(AssistantLine(
            "Xin chào! Tôi là TONY AI Assistant. Hãy hỏi về xu hướng, tín hiệu BUY/SELL/WAIT hoặc phân tích một cặp USDT. Tôi sử dụng bộ phân tích định lượng nội bộ, chưa kết nối mô hình AI hội thoại bên ngoài.",
            false
        )))
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun submitQuestion(prompt: String) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty() || busy) return
        val normalized = cleanPrompt.uppercase(Locale.US)
        val requested = Regex("[A-Z0-9]{2,15}USDT").find(normalized)?.value
        if (requested != null) selected = requested
        lines = lines + AssistantLine(cleanPrompt, true)
        question = ""
        val symbol = requested ?: selected
        scope.launch {
            busy = true
            status = "Đang tải nến Binance và phân tích 4 khung thời gian..."
            val result = runCatching { onAnalyze(symbol) }.getOrNull()
            if (result == null) {
                lines = lines + AssistantLine(
                    "Chưa thể lấy đủ dữ liệu nến cho $symbol. Kiểm tra kết nối Internet, mã giao dịch và thử lại. Không đưa ra tín hiệu khi thiếu dữ liệu.",
                    false
                )
                status = "Không hoàn tất phân tích."
            } else {
                lines = lines + AssistantLine("", false, result)
                status = "Cập nhật lúc ${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(result.generatedAt))} • Binance Public"
            }
            busy = false
        }
    }

    LaunchedEffect(lines.size, busy) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF130F18)),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = AssistantPanel),
            border = BorderStroke(1.dp, AssistantAccent.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TONY TRAN AI ASSISTANT", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    AssistChip(onClick = {}, label = { Text("ADVISORY") })
                }
                Text(
                    "Phân tích đa khung thời gian • Không tự động đặt lệnh • PAPER only",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD9CDE5)
                )
                Text(
                    "Nguồn: Binance Public Klines • 5m / 15m / 1h / 4h",
                    style = MaterialTheme.typography.labelSmall,
                    color = AssistantAccent
                )
                Text(
                    "Chưa kết nối nhà cung cấp AI hội thoại bên ngoài.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AssistantNegative
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = { selected = it.uppercase(Locale.US) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Mã giao dịch") },
                placeholder = { Text("BTCUSDT") }
            )
            TextButton(onClick = { onPick(selected) }) { Text("PAPER") }
        }

        Button(
            onClick = { submitQuestion("Phân tích $selected theo 4 khung thời gian và đưa ra nhận định.") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            enabled = !busy && selected.matches(Regex("[A-Z0-9]{2,15}USDT"))
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Phân tích đa khung thời gian")
        }

        Text(
            status,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB9AFC4)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("HỘI THOẠI", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text("Phân tích định lượng • Advisory", color = AssistantAccent, style = MaterialTheme.typography.labelSmall)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(lines) { line ->
                if (line.fromUser) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.88f),
                            color = Color(0xFF62428E),
                            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp)
                        ) {
                            Text(line.text, Modifier.padding(14.dp), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else if (line.report != null) {
                    AssistantReportCard(line.report)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
                        Surface(color = Color(0xFF7652B5), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(34.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("✦", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.92f),
                            color = AssistantPanelSoft,
                            shape = RoundedCornerShape(topStart = 5.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                            border = BorderStroke(1.dp, AssistantAccent.copy(alpha = 0.25f))
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("TONY AI", color = AssistantAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                Text(line.text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 42.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AssistantAccent)
                        Spacer(Modifier.width(10.dp))
                        Text("TONY AI đang phân tích dữ liệu...", color = AssistantAccent, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().background(Color(0xFF21172D)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("BTCUSDT có nên BUY không?", "Phân tích ETHUSDT", "Tìm tín hiệu breakout").forEach { suggestion ->
                    SuggestionChip(
                        onClick = { submitQuestion(suggestion) },
                        label = { Text(suggestion, maxLines = 1) },
                        enabled = !busy
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Nhập câu hỏi về thị trường...") },
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AssistantAccent,
                        unfocusedBorderColor = AssistantAccent.copy(alpha = 0.55f),
                        focusedContainerColor = Color(0xFF352744),
                        unfocusedContainerColor = Color(0xFF30243F),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedPlaceholderColor = Color(0xFFD2C2EA),
                        unfocusedPlaceholderColor = Color(0xFFD2C2EA)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitQuestion(question) })
                )
                Button(
                    onClick = { submitQuestion(question) },
                    enabled = question.isNotBlank() && !busy,
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7652B5), contentColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("Gửi", fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "Phân tích dựa trên dữ liệu công khai từ Binance. Không tự động đặt lệnh.",
                color = Color(0xFFB9AFC4),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun AssistantReportCard(report: AssistantMarketReport) {
    val decisionColor = when (report.decision.uppercase(Locale.US)) {
        "BUY" -> AssistantPositive
        "SELL" -> AssistantNegative
        else -> AssistantNeutral
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
        Surface(color = Color(0xFF7652B5), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("✦", color = Color.White, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.width(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AssistantPanelSoft),
            border = BorderStroke(1.dp, AssistantAccent.copy(alpha = 0.35f)),
            shape = RoundedCornerShape(topStart = 5.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("TONY AI • PHÂN TÍCH", color = AssistantAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Text(report.symbol, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                    Surface(color = decisionColor.copy(alpha = 0.16f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, decisionColor)) {
                        Text(report.decision, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = decisionColor, fontWeight = FontWeight.ExtraBold)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("ĐỒNG THUẬN", "${report.score}/100", Modifier.weight(1f), AssistantAccent)
                    MetricTile("GIÁ THAM CHIẾU", compactPrice(report.currentPrice), Modifier.weight(1f), Color.White)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("NHẬN ĐỊNH XU HƯỚNG", color = AssistantAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text(report.trendSummary, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ĐA KHUNG THỜI GIAN", color = AssistantAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    report.timeframeViews.forEach { view ->
                        val trendColor = when (view.trend) {
                            "TĂNG" -> AssistantPositive
                            "GIẢM" -> AssistantNegative
                            else -> AssistantNeutral
                        }
                        Surface(color = Color(0xFF241B31), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(view.timeframe, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(view.trend, color = trendColor, fontWeight = FontWeight.Bold)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    SmallMetric("RSI", MarketAssistantAnalysis.fmt(view.rsi))
                                    SmallMetric("Volume", "${MarketAssistantAnalysis.fmt(view.volumeRatio)}x")
                                    SmallMetric("EMA20", compactPrice(view.ema20))
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VÙNG GIÁ QUAN TRỌNG", color = AssistantAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile("HỖ TRỢ", report.support?.let(::compactPrice) ?: "Chưa đủ dữ liệu", Modifier.weight(1f), AssistantPositive)
                        MetricTile("KHÁNG CỰ", report.resistance?.let(::compactPrice) ?: "Chưa đủ dữ liệu", Modifier.weight(1f), AssistantNegative)
                    }
                }

                if (report.entryReference != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("KẾ HOẠCH THAM KHẢO", color = AssistantAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricTile("ENTRY", compactPrice(report.entryReference), Modifier.weight(1f), Color.White)
                            MetricTile("STOP LOSS", report.stopLossReference?.let(::compactPrice) ?: "—", Modifier.weight(1f), AssistantNegative)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricTile("TAKE PROFIT", report.takeProfitReference?.let(::compactPrice) ?: "—", Modifier.weight(1f), AssistantPositive)
                            MetricTile("R:R", report.riskReward?.let { MarketAssistantAnalysis.fmt(it) } ?: "—", Modifier.weight(1f), AssistantAccent)
                        }
                    }
                } else {
                    Text(
                        "Chưa đề xuất Entry / SL / TP vì tín hiệu chưa đủ điều kiện.",
                        color = AssistantNeutral,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (report.reasons.isNotEmpty()) {
                    ExpandableAnalysisSection("CƠ SỞ PHÂN TÍCH", report.reasons)
                }
                if (report.cautions.isNotEmpty()) {
                    ExpandableAnalysisSection("CẢNH BÁO RỦI RO", report.cautions, AssistantNeutral)
                }
                Text(
                    "Dữ liệu công khai Binance • ${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(report.generatedAt))}\nChỉ mang tính tham khảo. Không có lệnh thật nào được gửi.",
                    color = Color(0xFFB9AFC4),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color) {
    Surface(modifier, color = Color(0xFF241B31), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Color(0xFFB9AFC4), style = MaterialTheme.typography.labelSmall)
            Text(value, color = valueColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SmallMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color(0xFFB9AFC4), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExpandableAnalysisSection(title: String, lines: List<String>, accent: Color = AssistantAccent) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = Color(0xFF241B31), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text(if (expanded) "Thu gọn −" else "Xem ${lines.size} mục +", color = accent, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (expanded) {
                lines.forEach { line ->
                    Text("• $line", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }
    }
}

private fun compactPrice(value: Double): String {
    if (!value.isFinite()) return "—"
    return when {
        value >= 1000 -> String.format(Locale.US, "%,.2f", value)
        value >= 1 -> String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
        value >= 0.01 -> String.format(Locale.US, "%.5f", value).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
    }
}
