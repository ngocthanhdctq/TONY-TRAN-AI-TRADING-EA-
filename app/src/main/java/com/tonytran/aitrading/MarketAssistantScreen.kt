package com.tonytran.aitrading

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private data class AssistantLine(val text: String, val fromUser: Boolean)

private val AssistantAccent = Color(0xFFB995FF)
private val AssistantPanel = Color(0xFF241B31)
private val AssistantPanelSoft = Color(0xFF30243F)

@Composable
fun MarketAssistantScreen(
    coins: List<MarketCoin>,
    onPick: (String) -> Unit,
    onAnalyze: suspend (String) -> AssistantMarketReport?
) {
    var question by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("BTCUSDT") }
    var report by remember { mutableStateOf<AssistantMarketReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var chatExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Chọn mã và nhấn Phân tích đa khung thời gian để tải nến mới từ Binance.") }
    var lines by remember {
        mutableStateOf(listOf(AssistantLine(
            "Xin chào! Tôi đang ở chế độ Advisory. Hãy hỏi về một cặp USDT hoặc chọn mã để phân tích nến 5m, 15m, 1h và 4h. Đây là bộ phân tích định lượng nội bộ, chưa kết nối mô hình AI hội thoại bên ngoài.", false
        )))
    }
    val scope = rememberCoroutineScope()
    val coin = coins.firstOrNull { it.symbol.equals(selected, true) }

    fun submitQuestion(prompt: String) {
        val normalized = prompt.uppercase(Locale.US)
        val requested = Regex("[A-Z0-9]{2,15}USDT").find(normalized)?.value
        if (requested != null) selected = requested
        lines = lines + AssistantLine(prompt, true)
        question = ""
        chatExpanded = true
        val symbol = requested ?: selected
        scope.launch {
            busy = true
            status = "Đang tải nến Binance và phân tích 4 khung thời gian..."
            val result = runCatching { onAnalyze(symbol) }.getOrNull()
            report = result
            val answer = if (result == null) {
                "Chưa thể lấy đủ dữ liệu nến cho $symbol. Kiểm tra kết nối Internet, mã giao dịch và thử lại. Không đưa ra tín hiệu khi thiếu dữ liệu."
            } else buildString {
                append("PHÂN TÍCH ${result.symbol}\n")
                append("Quyết định: ${result.decision} • Điểm đồng thuận: ${result.score}/100\n")
                append("Xu hướng: ${result.trendSummary}\n")
                append("Giá tham chiếu: ${MarketAssistantAnalysis.fmtPrice(result.currentPrice)} USDT\n\n")
                append("Đa khung thời gian:\n")
                result.timeframeViews.forEach { append("${it.timeframe}: ${it.trend} • RSI ${MarketAssistantAnalysis.fmt(it.rsi)} • Vol ${MarketAssistantAnalysis.fmt(it.volumeRatio)}x\n") }
                append("\nHỗ trợ tham khảo: ${result.support?.let { MarketAssistantAnalysis.fmtPrice(it) } ?: "Chưa đủ dữ liệu"}\n")
                append("Kháng cự tham khảo: ${result.resistance?.let { MarketAssistantAnalysis.fmtPrice(it) } ?: "Chưa đủ dữ liệu"}\n")
                if (result.entryReference != null) {
                    append("Entry tham chiếu: ${MarketAssistantAnalysis.fmtPrice(result.entryReference)}\n")
                    append("SL tham chiếu: ${result.stopLossReference?.let { MarketAssistantAnalysis.fmtPrice(it) } ?: "N/A"}\n")
                    append("TP tham chiếu: ${result.takeProfitReference?.let { MarketAssistantAnalysis.fmtPrice(it) } ?: "N/A"}\n")
                    append("Risk/Reward mục tiêu: ${result.riskReward?.let { MarketAssistantAnalysis.fmt(it) } ?: "N/A"}\n")
                } else append("Chưa đề xuất Entry/SL/TP vì tín hiệu chưa đủ điều kiện.\n")
                append("\nCơ sở:\n• ${result.reasons.joinToString("\n• ")}\n")
                if (result.cautions.isNotEmpty()) append("\nCảnh báo:\n• ${result.cautions.joinToString("\n• ")}\n")
                append("\nĐây là phân tích kỹ thuật tham khảo từ dữ liệu công khai, không phải dự báo chắc chắn hay lời khuyên đầu tư. Không có lệnh thật nào được gửi.")
            }
            lines = lines + AssistantLine(answer, false)
            status = if (result == null) "Không hoàn tất phân tích." else "Cập nhật lúc ${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(result.generatedAt))} • Binance Public"
            busy = false
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TONY TRAN AI ASSISTANT", style = MaterialTheme.typography.titleMedium)
                    AssistChip(onClick = {}, label = { Text("ADVISORY") })
                }
                Text("Phân tích đa khung thời gian • Không tự động đặt lệnh • PAPER only",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Nguồn: Binance Public Klines • 5m / 15m / 1h / 4h", style = MaterialTheme.typography.labelSmall)
                Text("Chưa kết nối nhà cung cấp AI hội thoại bên ngoài.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = selected, onValueChange = { selected = it.uppercase(Locale.US) },
                modifier = Modifier.weight(1f), singleLine = true, label = { Text("Mã giao dịch") }, placeholder = { Text("BTCUSDT") })
            TextButton(onClick = { onPick(selected) }) { Text("PAPER") }
        }
        Button(onClick = { submitQuestion("Phân tích $selected theo 4 khung thời gian và đưa ra nhận định.") },
            modifier = Modifier.fillMaxWidth(), enabled = !busy && selected.matches(Regex("[A-Z0-9]{2,15}USDT"))) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Phân tích đa khung thời gian")
        }
        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        report?.let { r ->
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("${r.symbol} • ${r.decision}", style = MaterialTheme.typography.titleMedium)
                        Text("${r.score}/100", style = MaterialTheme.typography.titleMedium)
                    }
                    r.timeframeViews.forEach { Text("${it.timeframe}: ${it.trend} | RSI ${MarketAssistantAnalysis.fmt(it.rsi)} | EMA20 ${MarketAssistantAnalysis.fmtPrice(it.ema20)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        // Collapsible chat panel: compact by default to keep the AI Center uncluttered.
        Surface(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            color = AssistantPanel,
            contentColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AssistantAccent.copy(alpha = 0.72f))
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = Color(0xFF7652B5), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("✦", color = Color.White, style = MaterialTheme.typography.titleLarge) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("TONY AI ASSISTANT", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(if (busy) "ĐANG PHÂN TÍCH..." else "ADVISORY MODE • PAPER ONLY",
                            style = MaterialTheme.typography.labelSmall, color = AssistantAccent)
                    }
                    TextButton(onClick = { chatExpanded = !chatExpanded }) {
                        Text(if (chatExpanded) "Thu gọn  ⌄" else "Mở chat  ⤢", color = AssistantAccent)
                    }
                }
                if (chatExpanded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("BTCUSDT có nên BUY không?", "Phân tích ETHUSDT", "Tìm tín hiệu breakout").forEach { suggestion ->
                            SuggestionChip(onClick = { submitQuestion(suggestion) }, label = {
                                Text(suggestion, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                            })
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(lines) { line ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(if (line.fromUser) 0.88f else 1f),
                                    color = if (line.fromUser) Color(0xFF5E4290) else AssistantPanelSoft,
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text(line.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = question,
                            onValueChange = { question = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("✦  Nhập câu hỏi về thị trường...") },
                            minLines = 1,
                            maxLines = 3,
                            shape = RoundedCornerShape(16.dp),
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
                            keyboardActions = KeyboardActions(onSend = { if (question.isNotBlank() && !busy) submitQuestion(question.trim()) })
                        )
                        Button(
                            onClick = { if (question.isNotBlank() && !busy) submitQuestion(question.trim()) },
                            enabled = question.isNotBlank() && !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7652B5), contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp)
                        ) { Text("Gửi ↑", fontWeight = FontWeight.Bold) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(color = AssistantPanelSoft, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                            Text("${lines.size} tin nhắn • Chạm ‘Mở chat’ để hỏi TONY AI",
                                Modifier.padding(horizontal = 12.dp, vertical = 13.dp), color = Color(0xFFE5D9F5),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { chatExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7652B5), contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Mở chat") }
                    }
                }
            }
        }
    }
}
