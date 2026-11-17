package com.tonytran.aitrading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TradingEventCenterScreen(
    events: List<TradingEvent>,
    onClear: () -> Unit
) {
    var filter by remember { mutableStateOf("TẤT CẢ") }
    val filters = listOf("TẤT CẢ", "LỆNH", "RỦI RO", "THỊ TRƯỜNG", "AI")
    val shown = events.filter { e ->
        when (filter) {
            "LỆNH" -> e.type == TradingEventType.PAPER_ORDER_OPEN || e.type == TradingEventType.PAPER_ORDER_CLOSE
            "RỦI RO" -> e.type == TradingEventType.RISK_WARNING
            "THỊ TRƯỜNG" -> e.type == TradingEventType.MARKET_SIGNAL || e.type == TradingEventType.MARKET_MOVE
            "AI" -> e.type == TradingEventType.LEARNING
            else -> true
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("TRADING EVENT CENTER", style = MaterialTheme.typography.titleLarge)
                Text("${events.size} sự kiện được lưu trên thiết bị", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onClear, enabled = events.isNotEmpty()) { Text("Xóa nhật ký") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            filters.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
            }
        }
        if (shown.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Chưa có sự kiện", style = MaterialTheme.typography.titleMedium)
                    Text("Các thay đổi tín hiệu, lệnh PAPER và cảnh báo rủi ro sẽ xuất hiện tại đây.")
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(e.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(e.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(e.detail, style = MaterialTheme.typography.bodyMedium)
                            val meta = buildString {
                                append(e.displayTime())
                                if (e.symbol.isNotBlank()) append(" • ${e.symbol}")
                                if (e.tradeId != null) append(" • Lệnh #${e.tradeId}")
                            }
                            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(buildString {
                                append("Nguồn: ${e.source}")
                                if (e.latencyMs != null) append(" • Xử lý: ${e.latencyMs} ms")
                            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Text("Thời gian xử lý nội bộ không đồng nghĩa với độ trễ khớp lệnh Binance. Bản hiện tại chỉ mô phỏng lệnh, chưa có xác nhận khớp lệnh thật.", style = MaterialTheme.typography.labelSmall)
    }
}
