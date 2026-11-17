package com.tonytran.aitrading

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import java.util.Locale

private data class AssistantLine(val text: String, val fromUser: Boolean)

private val AssistantAccent = Color(0xFFBE9BFF)
private val AssistantBackground = Color(0xFF15111B)
private val AssistantPanel = Color(0xFF251B31)
private val AssistantPanelSoft = Color(0xFF342740)

@Composable
fun MarketAssistantScreen(
    coins: List<MarketCoin>,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    onAnalyze: suspend (String) -> AssistantMarketReport?
) {
    var question by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("BTCUSDT") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Phân tích dựa trên dữ liệu công khai từ Binance. Không tự động đặt lệnh.") }
    var lines by remember {
        mutableStateOf(listOf(AssistantLine(
            "Xin chào! Tôi là TONY AI Assistant. Hãy hỏi về xu hướng, tín hiệu BUY/SELL/WAIT hoặc phân tích một cặp USDT. Tôi sử dụng bộ phân tích định lượng nội bộ, chưa kết nối mô hình AI hội thoại bên ngoài.", false
        )))
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    BackHandler(onBack = onBack)

    fun submitQuestion(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty() || busy) return
        val normalized = clean.uppercase(Locale.US)
        val requested = Regex("[A-Z0-9]{2,15}USDT").find(normalized)?.value
        if (requested != null) selected = requested
        lines = lines + AssistantLine(clean, true)
        question = ""
        keyboard?.hide()
        val symbol = requested ?: selected
        scope.launch {
            busy = true
            status = "Đang tải dữ liệu Binance và phân tích 5m / 15m / 1h / 4h..."
            val result = runCatching { onAnalyze(symbol) }.getOrNull()
            val answer = if (result == null) {
                "Chưa thể lấy đủ dữ liệu nến cho $symbol. Hãy kiểm tra kết nối Internet, mã giao dịch và thử lại. Tôi không đưa ra tín hiệu khi thiếu dữ liệu."
            } else buildString {
                append("PHÂN TÍCH ${result.symbol}\n")
                append("Quyết định: ${result.decision} • Điểm đồng thuận: ${result.score}/100\n")
                append("Xu hướng: ${result.trendSummary}\n")
                append("Giá tham chiếu: ${MarketAssistantAnalysis.fmtPrice(result.currentPrice)} USDT\n\n")
                append("ĐA KHUNG THỜI GIAN\n")
                result.timeframeViews.forEach {
                    append("${it.timeframe}: ${it.trend} • RSI ${MarketAssistantAnalysis.fmt(it.rsi)} • Volume ${MarketAssistantAnalysis.fmt(it.volumeRatio)}x\n")
                }
                append("\nHỗ trợ: ${result.support?.let { MarketAssistantAnalysis.fmtPrice(it) } ?: "Chưa đủ dữ liệu"}\n")
                append("Kháng cự: ${result.resistance?.let { MarketAssistantAnalysis.fmtPrice(it) } ?: "Chưa đủ dữ liệu"}\n")
                if (result.entryReference != null) {
                    append("Entry tham chiếu: ${MarketAssistantAnalysis.fmtPrice(result.entryReference)}\n")
                    append("Stop Loss: ${result.stopLossReference?.let { MarketAssistantAnalysis.fmtPrice(it) } ?: "N/A"}\n")
                    append("Take Profit: ${result.takeProfitReference?.let { MarketAssistantAnalysis.fmtPrice(it) } ?: "N/A"}\n")
                    append("Risk/Reward: ${result.riskReward?.let { MarketAssistantAnalysis.fmt(it) } ?: "N/A"}\n")
                } else append("Chưa đề xuất Entry/SL/TP vì tín hiệu chưa đủ điều kiện.\n")
                append("\nCƠ SỞ PHÂN TÍCH\n• ${result.reasons.joinToString("\n• ")}\n")
                if (result.cautions.isNotEmpty()) append("\nCẢNH BÁO\n• ${result.cautions.joinToString("\n• ")}\n")
                append("\nĐây là phân tích kỹ thuật tham khảo từ dữ liệu công khai, không phải dự báo chắc chắn hay lời khuyên đầu tư. Không có lệnh thật nào được gửi.")
            }
            lines = lines + AssistantLine(answer, false)
            status = if (result == null) "Phân tích chưa hoàn tất • ${symbol}" else
                "Cập nhật ${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(result.generatedAt))} • Binance Public"
            busy = false
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AssistantBackground).imePadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Chat application header
        Row(
            modifier = Modifier.fillMaxWidth().background(AssistantPanel).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                Text("‹", color = AssistantAccent, style = MaterialTheme.typography.headlineMedium)
            }
            Surface(color = Color(0xFF7652B5), shape = RoundedCornerShape(13.dp), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("✦", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("TONY AI ASSISTANT", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(if (busy) "ĐANG PHÂN TÍCH..." else "ADVISORY MODE • PAPER ONLY", color = AssistantAccent, style = MaterialTheme.typography.labelSmall)
            }
            AssistChip(onClick = {}, label = { Text("ADVISORY") }, colors = AssistChipDefaults.assistChipColors(
                labelColor = AssistantAccent, containerColor = Color(0xFF38284B)
            ))
        }

        // Small market context strip
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1D1725)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${selected}  •  Binance Public Klines", color = Color(0xFFD9CBEA), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { onPick(selected) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("PAPER", color = AssistantAccent)
            }
        }

        if (lines.size == 1 && !busy) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bạn có thể hỏi TONY AI", color = Color.White, fontWeight = FontWeight.SemiBold)
                val suggestions = listOf("BTCUSDT có nên BUY không?", "Phân tích ETHUSDT", "Tìm tín hiệu breakout")
                suggestions.forEach { suggestion ->
                    OutlinedButton(
                        onClick = { submitQuestion(suggestion) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AssistantAccent.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE8DDF7))
                    ) { Text(suggestion, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                }
            }
        }

        // Independent, expanding conversation history
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(lines) { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Top
                ) {
                    if (!line.fromUser) {
                        Surface(color = Color(0xFF7652B5), shape = RoundedCornerShape(10.dp), modifier = Modifier.size(30.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("✦", color = Color.White) }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(if (line.fromUser) 0.84f else 0.91f),
                        color = if (line.fromUser) Color(0xFF65439A) else AssistantPanelSoft,
                        shape = RoundedCornerShape(if (line.fromUser) 18.dp else 18.dp),
                        border = if (line.fromUser) null else BorderStroke(1.dp, Color(0xFF554263))
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!line.fromUser) Text("TONY AI", color = AssistantAccent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text(line.text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = AssistantAccent, strokeWidth = 2.dp)
                        Text("TONY AI đang phân tích dữ liệu thị trường...", color = AssistantAccent, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text(status, modifier = Modifier.fillMaxWidth().background(Color(0xFF1D1725)).padding(horizontal = 14.dp, vertical = 6.dp),
            color = Color(0xFFB8A9C8), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)

        // Fixed composer: stays above the Android keyboard
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AssistantPanel,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, AssistantAccent.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                        focusedContainerColor = Color(0xFF30243F),
                        unfocusedContainerColor = Color(0xFF30243F),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedPlaceholderColor = Color(0xFFD2C2EA),
                        unfocusedPlaceholderColor = Color(0xFFD2C2EA),
                        cursorColor = AssistantAccent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (question.isNotBlank() && !busy) submitQuestion(question) })
                )
                Button(
                    onClick = { submitQuestion(question) },
                    enabled = question.isNotBlank() && !busy,
                    modifier = Modifier.heightIn(min = 54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7652B5), contentColor = Color.White),
                    shape = RoundedCornerShape(17.dp),
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 12.dp)
                ) { Text("Gửi", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
