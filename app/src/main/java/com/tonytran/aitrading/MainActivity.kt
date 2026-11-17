package com.tonytran.aitrading

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TonyApp() }
    }
}

private enum class Tab(val label: String) {
    DASHBOARD("AI TRADING"), SCANNER("AI Scanner"), TRADE("Giao dịch"),
    HISTORY("Lịch sử"), FUNDING("Funding Hunter"), NEWS("Tin tức"), FUSION("AI Fusion"), BACKTEST("Backtest"), EVENTS("Sự kiện"), DIAGNOSTICS("Kiểm thử hệ thống"), BINANCE("Binance Read-only"), INTELLIGENCE("AI Market Intelligence"), LEARNING("AI SELF-LEARNING"), SETTINGS("Cài đặt")
}

@Composable
private fun TonyApp() {
    val context = LocalContext.current
    val fundingSettings = remember { FundingSettingsStore(context) }
    val market = remember { BinancePublicMarket() }
    val paperLedgerStore = remember { PaperLedgerStore(context) }
    val paperSnapshotAtLaunch = remember { paperLedgerStore.load() }
    val paper = remember { PaperTradingEngine(paperSnapshotAtLaunch, paperLedgerStore::save) }
    val fundingClient = remember { BinanceFundingClient() }
    val fundingEngine = remember { FundingRateHunterEngine(context) }
    var fundingCandidates by remember { mutableStateOf<List<FundingMarket>>(emptyList()) }
    var fundingTelemetry by remember { mutableStateOf<FundingScanResult?>(null) }
    var fundingDashboardNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var fundingHistory by remember { mutableStateOf<List<FundingPaperPosition>>(fundingEngine.history()) }
    var fundingAuto by remember { mutableStateOf(false) }
    var fundingThreshold by remember { mutableStateOf(fundingSettings.loadThreshold()) }
    var fundingMargin by remember { mutableStateOf(fundingSettings.loadMargin()) }
    var fundingResumeNotice by remember { mutableStateOf(fundingSettings.wasAutoEnabled()) }
    var fundingStatus by remember { mutableStateOf("Chưa quét dữ liệu Futures.") }
    var fundingBusy by remember { mutableStateOf(false) }
    var coins by remember { mutableStateOf<List<MarketCoin>>(emptyList()) }
    var message by remember { mutableStateOf("Đang khởi tạo dữ liệu thị trường...") }
    var paperRestoreNotice by remember { mutableStateOf(paperSnapshotAtLaunch != null) }
    var loading by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    var selectedSymbol by remember { mutableStateOf("BTCUSDT") }
    var spotAmount by remember { mutableStateOf("5") }
    var futuresMargin by remember { mutableStateOf("2") }
    var side by remember { mutableStateOf(Side.LONG) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val tradeNotifications = remember { TradeNotificationManager(context) }
    val eventStore = remember { TradingEventStore(context) }
    var tradingEvents by remember { mutableStateOf(eventStore.all()) }
    fun recordEvent(type: TradingEventType, title: String, detail: String, symbol: String = "",
                    tradeId: Long? = null, source: String = "APP", status: String = "INFO",
                    latencyMs: Long? = null, dedupeKey: String? = null, cooldownMs: Long = 0L) {
        eventStore.add(type, title, detail, symbol, tradeId, source, status, latencyMs, dedupeKey, cooldownMs)
        tradingEvents = eventStore.all()
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* User can enable notifications later in Android app settings. */ }

    LaunchedEffect(tab) {
        if (tab == Tab.FUNDING) {
            while (true) {
                fundingDashboardNow = System.currentTimeMillis()
                delay(250L)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (paperRestoreNotice) {
            val openCount = paper.openTrades().size
            val closedCount = paper.history().count { it.status == TradeStatus.CLOSED }
            message = "Đã khôi phục sổ PAPER trên thiết bị: $openCount lệnh đang mở, $closedCount lệnh đã đóng. Đây là dữ liệu mô phỏng; hãy kiểm tra giá hiện tại trước khi tiếp tục."
            paperRestoreNotice = false
        }
        fundingHistory = fundingEngine.history()
        if (fundingResumeNotice) {
            fundingStatus = "Đã khôi phục dữ liệu phiên trước. AUTO đã được tắt an toàn; kiểm tra vị thế đang mở rồi bật lại thủ công nếu muốn."
            fundingResumeNotice = false
        } else if (fundingEngine.openPositions().isNotEmpty()) {
            fundingStatus = "Đã khôi phục ${fundingEngine.openPositions().size} vị thế PAPER đang mở. Cần quét lại dữ liệu để tiếp tục giám sát."
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun dispatchTradeNotifications() {
        paper.drainEvents().forEach { event ->
            tradeNotifications.show(event)
            val isOpen = event.eventType == "OPEN"
            recordEvent(
                if (isOpen) TradingEventType.PAPER_ORDER_OPEN else TradingEventType.PAPER_ORDER_CLOSE,
                if (isOpen) "Đã mở lệnh PAPER ${event.trade.symbol}" else "Đã đóng lệnh PAPER ${event.trade.symbol}",
                "Mã #${event.trade.id} • ${event.trade.market} • ${event.trade.side} • Giá ${event.trade.entry}" +
                    (if (!isOpen) " • PnL ${event.trade.pnl} USDT • ${event.trade.note}" else " • Khối lượng ${event.trade.quantity}"),
                event.trade.symbol, event.trade.id, "PAPER_ENGINE", if (isOpen) "OPENED" else "CLOSED"
            )
        }
        paper.drainRiskEvents().forEach { event ->
            tradeNotifications.showRisk(event)
            recordEvent(TradingEventType.RISK_WARNING, "Cảnh báo rủi ro ${event.trade.symbol}",
                "${event.riskType} • Giá ${event.currentPrice} • Giá trị ${event.metricValue} • Ngưỡng ${event.threshold}",
                event.trade.symbol, event.trade.id, "RISK_MONITOR", "WARNING",
                dedupeKey = "risk-${event.trade.id}-${event.riskType}", cooldownMs = 60_000L)
        }
    }

    fun syncBackgroundMonitor(startIfNeeded: Boolean = false) {
        val payload = org.json.JSONArray().apply {
            paper.openTrades().forEach { trade ->
                put(org.json.JSONObject().apply {
                    put("id", trade.id); put("symbol", trade.symbol)
                    put("market", trade.market.name); put("side", trade.side.name)
                    put("entry", trade.entry); put("quantity", trade.quantity)
                    put("margin", trade.margin)
                })
            }
        }.toString()
        val serviceIntent = Intent(context, BackgroundMonitorService::class.java).apply {
            action = if (startIfNeeded) BackgroundMonitorService.ACTION_START else BackgroundMonitorService.ACTION_UPDATE
            putExtra(BackgroundMonitorService.EXTRA_TRADES, payload)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
        else context.startService(serviceIntent)
    }

    val learningStore = remember { LocalLearningStore(context) }
    var strategyConfig by remember { mutableStateOf(learningStore.loadConfig()) }
    var backtestReport by remember { mutableStateOf<BacktestReport?>(null) }
    var optimizationReport by remember { mutableStateOf<StrategyOptimizationReport?>(null) }
    var backtestBusy by remember { mutableStateOf(false) }
    val newsIntel = remember { NewsIntelligence(context) }
    val newsCache = remember { NewsCache(context) }
    var newsDigest by remember { mutableStateOf(newsCache.load()) }
    var newsBusy by remember { mutableStateOf(false) }
    val mlStore = remember { LocalMlModelStore(context) }
    val predictionStore = remember { PredictionLearningStore(context) }
    var predictionRecords by remember { mutableStateOf(predictionStore.load()) }
    var mlReport by remember { mutableStateOf<HistoricalTrainingReport?>(null) }
    var walkReport by remember { mutableStateOf<WalkForwardReport?>(null) }
    var analogs by remember { mutableStateOf<List<AnalogResult>>(emptyList()) }
    var mlBusy by remember { mutableStateOf(false) }
    var mlModel by remember { mutableStateOf(mlStore.load()) }
    val scope = rememberCoroutineScope()

    fun refreshFunding() {
        scope.launch {
            fundingBusy = true
            try {
                val thresholdPct = fundingThreshold.toDoubleOrNull() ?: 1.5
                val scan = fundingClient.scan(thresholdPct)
                fundingTelemetry = scan
                fundingCandidates = scan.candidates
                val result = fundingEngine.process(scan, fundingAuto, marginUsdt = fundingMargin.toDoubleOrNull() ?: 2.0)
                fundingStatus = result.message
                recordEvent(TradingEventType.FUNDING_UPDATE, "Funding Hunter cập nhật", result.message,
                    source = "BINANCE_FUNDING_SCANNER", status = if (fundingAuto) "AUTO_PAPER" else "SCAN",
                    dedupeKey = "funding-status", cooldownMs = 60_000L)
                fundingHistory = fundingEngine.history()
            } catch (e: Exception) {
                fundingStatus = "Lỗi quét Funding: ${e.message ?: "kiểm tra kết nối"}"
            } finally {
                fundingBusy = false
            }
        }
    }

    LaunchedEffect(fundingAuto, fundingThreshold, fundingMargin) {
        fundingSettings.saveThreshold(fundingThreshold)
        fundingSettings.saveMargin(fundingMargin)
        fundingSettings.saveAutoEnabled(fundingAuto)
        if (fundingAuto) {
            while (true) {
                val cycleStarted = android.os.SystemClock.elapsedRealtime()
                try {
                    val thresholdPct = fundingThreshold.toDoubleOrNull() ?: 1.5
                    val scan = fundingClient.scan(thresholdPct)
                    fundingTelemetry = scan
                    fundingCandidates = scan.candidates
                    val result = fundingEngine.process(scan, true, marginUsdt = fundingMargin.toDoubleOrNull() ?: 2.0)
                    fundingStatus = result.message
                    fundingHistory = fundingEngine.history()
                } catch (e: Exception) {
                    fundingStatus = "Funding Hunter tạm lỗi: ${e.message ?: "kiểm tra mạng"}"
                }
                // Target roughly 1s cycle start-to-start. Slow networks still
                // determine the real cadence; this is not a latency guarantee.
                val elapsed = android.os.SystemClock.elapsedRealtime() - cycleStarted
                delay((1_000L - elapsed).coerceAtLeast(100L))
            }
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                val tickers = market.fetchTickers()
                // Deep analysis for the 12 most liquid pairs; remaining pairs stay in watchlist.
                val deep = tickers.take(12).map { ticker ->
                    async {
                        runCatching {
                            val m5 = Indicators.snapshot(market.fetchKlines(ticker.symbol, "5m"))
                            val m15 = Indicators.snapshot(market.fetchKlines(ticker.symbol, "15m"))
                            val h1 = Indicators.snapshot(market.fetchKlines(ticker.symbol, "1h"))
                            AdaptiveSignalEngine.analyze(ticker, m5, m15, h1, strategyConfig)
                        }.getOrElse { ticker.copy(score = 0, signal = "WAIT", analysis = "Lỗi phân tích nến") }
                    }
                }.awaitAll()
                val deepMap = deep.associateBy { it.symbol }
                val fresh = tickers.map { deepMap[it.symbol] ?: it.copy(score = 0, signal = "WAIT", analysis = "Chờ phân tích chuyên sâu") }
                coins = fresh
                fresh.filter { it.signal != "WAIT" }.forEach { coin ->
                    recordEvent(TradingEventType.MARKET_SIGNAL, "AI phát hiện ${coin.signal} ${coin.symbol}",
                        "Điểm ${coin.score}/100 • RSI ${"%.1f".format(java.util.Locale.US, coin.rsi)} • Xu hướng 1h ${coin.trend1h}",
                        coin.symbol, source = "TECHNICAL_AI", status = coin.signal,
                        dedupeKey = "signal-${coin.symbol}-${coin.signal}", cooldownMs = 5 * 60_000L)
                }
                fresh.filter { kotlin.math.abs(it.changePct) >= 3.0 }.forEach { coin ->
                    recordEvent(TradingEventType.MARKET_MOVE, "Biến động mạnh ${coin.symbol}",
                        "Biến động 24h ${"%.2f".format(java.util.Locale.US, coin.changePct)}% • Giá ${coin.price}",
                        coin.symbol, source = "BINANCE_PUBLIC_TICKER", status = "VOLATILE",
                        dedupeKey = "move-${coin.symbol}", cooldownMs = 15 * 60_000L)
                }
                paper.monitor(fresh.associate { it.symbol to it.price })
                dispatchTradeNotifications()
                if (context.getSharedPreferences("tony_background_monitor", android.content.Context.MODE_PRIVATE)
                        .getBoolean("enabled_by_user", false)) syncBackgroundMonitor()
                val learningReport = MarketIntelligenceEngine.analyze(fresh, fundingTelemetry)
                val learningCoins = fresh.map { coin ->
                    val aiDecision = learningReport.items.firstOrNull { it.symbol == coin.symbol }
                    coin.copy(
                        signal = aiDecision?.decision ?: "WAIT",
                        score = aiDecision?.score ?: 0
                    )
                }
                predictionStore.capture(learningCoins, learningReport.btcChangePct)
                predictionRecords = predictionStore.resolve(fresh.associate { it.symbol to it.price })
                message = "Cập nhật giá công khai thành công."
            } catch (e: Exception) {
                message = "Không lấy được giá: ${e.message ?: "kiểm tra mạng"}"
            }
            loading = false
            refreshKey++
        }
    }

    fun refreshNews() {
        scope.launch {
            newsBusy = true
            try {
                newsDigest = newsIntel.fetchDaily()
                message = "Đã cập nhật bản tin thị trường."
            } catch (e: Exception) {
                message = "Không tải được tin tức: ${e.message ?: "kiểm tra mạng"}"
            } finally { newsBusy = false }
        }
    }

    LaunchedEffect(Unit) {
        newsDigest = newsCache.load()
        refreshNews()
        while (true) {
            delay(30L * 60L * 1000L)
            refreshNews()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(15_000)
        }
    }

    val chosen = coins.firstOrNull { it.symbol == selectedSymbol }
    val prices = coins.associate { it.symbol to it.price }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBarCompat(
                    title = "TONY TRAN AI TRADING EA",
                    subtitle = "PAPER TRADING • LIVE MARKET DATA"
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab != Tab.LEARNING,
                        onClick = { if (tab == Tab.LEARNING) tab = Tab.DASHBOARD },
                        icon = { Text("✦") },
                        label = { Text("AI TRADING") }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.LEARNING,
                        onClick = { tab = Tab.LEARNING },
                        icon = { Text("AI") },
                        label = { Text("AI SELF-LEARNING") }
                    )
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AssistChip(
                        onClick = { refresh() },
                        enabled = !loading,
                        label = { Text(if (loading) "Đang cập nhật..." else "↻ Làm mới dữ liệu") }
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "PAPER • KHÔNG TIỀN THẬT",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (tab == Tab.LEARNING) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            "AI SELF-LEARNING  •  HỌC TỪ DỮ LIỆU LỊCH SỬ\nChỉ tạo đề xuất; không tự thay đổi luật giao dịch.",
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        listOf(Tab.DASHBOARD, Tab.SCANNER, Tab.TRADE, Tab.HISTORY, Tab.FUNDING, Tab.NEWS, Tab.FUSION, Tab.BACKTEST, Tab.EVENTS, Tab.DIAGNOSTICS, Tab.BINANCE, Tab.INTELLIGENCE, Tab.SETTINGS).forEach { section ->
                            FilterChip(
                                selected = tab == section,
                                onClick = { tab = section },
                                label = { Text(when(section) {
                                    Tab.DASHBOARD -> "Tổng quan"; Tab.SCANNER -> "Scanner"; Tab.TRADE -> "Lệnh"
                                    Tab.HISTORY -> "Lịch sử"; Tab.FUNDING -> "Funding"; Tab.NEWS -> "Tin tức"
                                    Tab.FUSION -> "Fusion"; Tab.BACKTEST -> "Kiểm thử"; Tab.EVENTS -> "Sự kiện"; Tab.DIAGNOSTICS -> "Kiểm thử"; Tab.BINANCE -> "Binance"; Tab.INTELLIGENCE -> "AI Market"; else -> "Cài đặt"
                                }) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (tab) {
                    Tab.DASHBOARD -> DashboardScreen(paper, coins, prices, refreshKey)
                    Tab.SCANNER -> ScannerScreen(coins, onPick = { selectedSymbol = it; tab = Tab.TRADE })
                    Tab.TRADE -> TradeScreen(
                        coins, selectedSymbol, { selectedSymbol = it }, spotAmount,
                        { spotAmount = it }, futuresMargin, { futuresMargin = it },
                        side, { side = it }, paper,
                        onTrade = { text ->
                            val startedAt = android.os.SystemClock.elapsedRealtime()
                            val c = coins.firstOrNull { it.symbol == selectedSymbol }
                            message = if (c == null) "Chưa có giá cho $selectedSymbol"
                            else if (text == "spot") paper.openSpot(c.symbol, c.price, spotAmount.toDoubleOrNull() ?: 0.0)
                            else paper.openFutures(c.symbol, c.price, futuresMargin.toDoubleOrNull() ?: 0.0, side)
                            val processingMs = android.os.SystemClock.elapsedRealtime() - startedAt
                            recordEvent(TradingEventType.SYSTEM, "Thời gian xử lý yêu cầu PAPER",
                                "Thời gian gọi bộ máy mô phỏng và kiểm tra kết quả: ${processingMs} ms. Không phải thời gian khớp lệnh trên Binance.",
                                selectedSymbol, source = "LOCAL_PAPER_ENGINE", status = "PROCESSED", latencyMs = processingMs)
                            dispatchTradeNotifications()
                            if (context.getSharedPreferences("tony_background_monitor", android.content.Context.MODE_PRIVATE)
                                    .getBoolean("enabled_by_user", false)) syncBackgroundMonitor()
                            refreshKey++
                        }, refreshKey
                    )
                    Tab.HISTORY -> HistoryScreen(paper.history().filter { it.status == TradeStatus.CLOSED }, prices) { id, price ->
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        message = paper.close(id, price)
                        val processingMs = android.os.SystemClock.elapsedRealtime() - startedAt
                        recordEvent(TradingEventType.SYSTEM, "Thời gian xử lý đóng PAPER",
                            "Thời gian xử lý nội bộ: ${processingMs} ms. Không có lệnh gửi hoặc xác nhận khớp từ Binance.",
                            tradeId = id, source = "LOCAL_PAPER_ENGINE", status = "PROCESSED", latencyMs = processingMs)
                        dispatchTradeNotifications()
                        if (context.getSharedPreferences("tony_background_monitor", android.content.Context.MODE_PRIVATE)
                                .getBoolean("enabled_by_user", false)) syncBackgroundMonitor()
                        refreshKey++
                    }
                    Tab.FUNDING -> FundingHunterScreen(
                        candidates = fundingCandidates,
                        history = fundingHistory,
                        learningStats = fundingEngine.learningStats(),
                        telemetry = fundingTelemetry,
                        dashboardNow = fundingDashboardNow,
                        autoEnabled = fundingAuto,
                        onAutoChange = { fundingAuto = it },
                        threshold = fundingThreshold,
                        onThresholdChange = { fundingThreshold = it },
                        margin = fundingMargin,
                        onMarginChange = { fundingMargin = it },
                        status = fundingStatus,
                        busy = fundingBusy,
                        onScan = { refreshFunding() }
                    )
                    Tab.NEWS -> NewsScreen(
                        digest = newsDigest,
                        busy = newsBusy,
                        onRefresh = { refreshNews() },
                        onOpen = { url ->
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                    )
                    Tab.FUSION -> DecisionFusionScreen(
                        coins = coins,
                        digest = newsDigest,
                        historicalModel = mlModel,
                        predictionRecords = predictionRecords,
                        onClearLearning = {
                            predictionStore.clear()
                            predictionRecords = emptyList()
                            message = "Đã xóa lịch sử đánh giá dự báo trên thiết bị."
                        },
                        onPick = { selectedSymbol = it; tab = Tab.TRADE }
                    )
                    Tab.BACKTEST -> BacktestScreen(
                        coins = coins,
                        report = backtestReport,
                        optimizationReport = optimizationReport,
                        busy = backtestBusy,
                        onRun = { symbol ->
                            scope.launch {
                                backtestBusy = true
                                try {
                                    val end = System.currentTimeMillis()
                                    val start = end - 60L * 24L * 60L * 60L * 1000L
                                    val candles = market.fetchHistoricalKlines(symbol, "15m", start, end, 6_000)
                                    val baseline = strategyConfig
                                    val result = withContext(Dispatchers.Default) {
                                        StrategyOptimizer.optimize(candles, baseline, predictionRecords)
                                    }
                                    optimizationReport = result
                                    if (result.promoted && result.winner != null) {
                                        val guard = SafetyCore.validatePromotion(
                                            BacktestReport("LOCAL_DATASET", "15m", result.baseline.metrics, result.winner.metrics, true,
                                                result.explanation),
                                            walkForwardPassed = true
                                        )
                                        if (guard.allowed) {
                                            val audit = SafetyAuditStore(context)
                                            val proposal = LearningProposal(
                                                id = "STRAT-${System.currentTimeMillis()}",
                                                source = "StrategyOptimizer",
                                                summary = "Đề xuất cấu hình ${result.winner.config.name}",
                                                stage = LearningStage.PENDING_APPROVAL,
                                                validationPassed = true,
                                                metrics = "return=${result.winner.metrics.netReturnPct};dd=${result.winner.metrics.maxDrawdownPct};trades=${result.winner.metrics.trades}"
                                            )
                                            audit.saveProposal(proposal)
                                            message = "AI đã tạo đề xuất thay đổi chiến lược. Chưa áp dụng; cần người dùng phê duyệt riêng."
                                        } else {
                                            SafetyAuditStore(context).record("PROMOTION_BLOCKED", guard.reason)
                                            message = guard.reason
                                        }
                                    } else {
                                        message = result.explanation
                                    }
                                } catch (e: Exception) {
                                    message = "Backtest lỗi: ${e.message ?: "không xác định"}"
                                } finally { backtestBusy = false }
                            }
                        }
                    )
                    Tab.EVENTS -> TradingEventCenterScreen(
                        events = tradingEvents,
                        onClear = {
                            eventStore.clear()
                            tradingEvents = emptyList()
                            recordEvent(TradingEventType.SYSTEM, "Đã xóa nhật ký sự kiện", "Nhật ký cũ đã được xóa theo yêu cầu.", source = "EVENT_CENTER")
                        }
                    )
                    Tab.LEARNING -> HistoricalLearningScreen(
                        walkReport = walkReport, analogs = analogs,
                        model = mlModel, busy = mlBusy, predictionRecords = predictionRecords,
                        onTrain = { symbol, months ->
                            scope.launch {
                                mlBusy = true
                                try {
                                    val end = System.currentTimeMillis()
                                    val start = end - months.toLong() * 30L * 24L * 60L * 60L * 1000L
                                    val start5m = maxOf(start, end - 90L * 24L * 60L * 60L * 1000L)
                                    val h15 = market.fetchHistoricalKlines(symbol, "15m", start, end, 40_000)
                                    val h5 = market.fetchHistoricalKlines(symbol, "5m", start5m, end, 30_000)
                                    val h1 = market.fetchHistoricalKlines(symbol, "1h", start, end, 10_000)
                                    val h4 = market.fetchHistoricalKlines(symbol, "4h", start, end, 3_000)
                                    val btc = if (symbol == "BTCUSDT") h15 else market.fetchHistoricalKlines("BTCUSDT", "15m", start, end, 40_000)
                                    val trained = withContext(Dispatchers.Default) {
                                        MultiTimeframeLearning.trainWalkForward(symbol, h15, h5, h1, h4, btc)
                                    }
                                    walkReport = trained.second
                                    val analogHistory = h15.dropLast(121)
                                    analogs = withContext(Dispatchers.Default) {
                                        MultiTimeframeLearning.findAnalogs(h15.takeLast(121), analogHistory, 8)
                                    }
                                    if (trained.first != null) {
                                        mlStore.save(trained.first!!)
                                        mlModel = trained.first
                                        message = "Mô hình đa khung đã vượt walk-forward và được lưu."
                                    } else {
                                        message = trained.second.explanation
                                    }
                                } catch (e: Exception) {
                                    message = "AI Lab lỗi: ${e.message ?: "không xác định"}"
                                } finally { mlBusy = false }
                            }
                        },
                        onClear = { mlStore.clear(); mlModel = null; message = "Đã xóa mô hình local." }
                    )
                    Tab.DIAGNOSTICS -> SystemDiagnosticsScreen()
                    Tab.BINANCE -> BinanceReadOnlyScreen(paper, coins)
                    Tab.INTELLIGENCE -> MarketIntelligenceScreen(coins, fundingTelemetry)
                    Tab.SETTINGS -> SettingsScreen(
                        paper = paper,
                        config = strategyConfig,
                        onReset = { message = "Tài khoản mô phỏng đã đặt lại."; refreshKey++ },
                        onStartBackground = {
                            context.getSharedPreferences("tony_background_monitor", android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean("enabled_by_user", true).apply()
                            syncBackgroundMonitor(startIfNeeded = true)
                            message = "Đã bật theo dõi nền."
                        },
                        onStopBackground = {
                            context.getSharedPreferences("tony_background_monitor", android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean("enabled_by_user", false).apply()
                            context.startService(Intent(context, BackgroundMonitorService::class.java).setAction(BackgroundMonitorService.ACTION_STOP))
                            message = "Đã tắt theo dõi nền."
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FundingHunterScreen(
    candidates: List<FundingMarket>,
    history: List<FundingPaperPosition>,
    learningStats: FundingLearningStats?,
    telemetry: FundingScanResult?,
    dashboardNow: Long,
    autoEnabled: Boolean,
    onAutoChange: (Boolean) -> Unit,
    threshold: String,
    onThresholdChange: (String) -> Unit,
    margin: String,
    onMarginChange: (String) -> Unit,
    status: String,
    busy: Boolean,
    onScan: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("FUNDING RATE HUNTER", style = MaterialTheme.typography.titleLarge)
        Text("Quét Binance USDⓈ-M Perpetual • Auto PAPER TRADING")
        Text(
            "AI Funding Intelligence dùng bộ chấm điểm định lượng theo funding, phí, spread, thanh khoản và proxy biến động 24h. Đây là mô hình luật minh bạch, chưa phải AI học sâu hay dự báo chắc chắn.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        val openPositions = history.filter { it.status == "OPEN" }
        val nearestFunding = candidates.minByOrNull { it.nextFundingTime }
        val nextSettlement = openPositions.minOfOrNull { it.settlementTime }
            ?: nearestFunding?.nextFundingTime
        val estimatedServerNow = telemetry?.let {
            it.scannedAt + (dashboardNow - it.localScannedAt).coerceAtLeast(0L)
        }
        val countdownMs = if (nextSettlement != null && estimatedServerNow != null)
            nextSettlement - estimatedServerNow else null

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BẢNG ĐIỀU KHIỂN THỜI GIAN THỰC", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Đếm ngược funding", style = MaterialTheme.typography.labelSmall)
                        Text(
                            countdownMs?.let { ms ->
                                val sign = if (ms < 0) "ĐÃ QUA " else ""
                                val seconds = kotlin.math.abs(ms) / 1000L
                                sign + "%02d:%02d".format(seconds / 60L, seconds % 60L)
                            } ?: "--:--",
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (countdownMs != null && countdownMs in 0L..10_000L)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Vị thế PAPER mở", style = MaterialTheme.typography.labelSmall)
                        Text("${openPositions.size}", style = MaterialTheme.typography.headlineSmall)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Cơ hội đạt lọc", style = MaterialTheme.typography.labelSmall)
                        Text("${candidates.size}", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                HorizontalDivider()
                Text(
                    "Giờ Binance ước tính: " + (estimatedServerNow?.let {
                        android.text.format.DateFormat.format("HH:mm:ss", java.util.Date(it)).toString()
                    } ?: "Chưa đồng bộ"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    telemetry?.let {
                        "Độ lệch giờ máy chủ: ${it.serverOffsetMs} ms • RTT: ${it.serverTimeRttMs} ms • Thời gian quét: ${it.scanDurationMs} ms"
                    } ?: "Chưa có dữ liệu đo độ trễ. Hãy quét lần đầu.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    telemetry?.let {
                        "Lần quét gần nhất: " + android.text.format.DateFormat.format("HH:mm:ss", java.util.Date(it.localScannedAt)).toString()
                    } ?: "Chưa quét dữ liệu",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    "Đây là ước tính từ REST API; đồng hồ và độ trễ có thể thay đổi theo mạng, không phải bảo đảm khớp lệnh T−5s.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

                ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("V2.1 • AI PATTERN & RISK RESEARCH", style = MaterialTheme.typography.titleMedium)
                val stats = learningStats
                if (stats == null) {
                    Text("Chưa khởi tạo bộ nhớ học cục bộ.")
                } else {
                    Text("Cơ hội đã ghi: ${stats.total} • Đã có kết quả: ${stats.resolved}")
                    Text("Tổng: thắng ${stats.wins} • thua ${stats.losses} • tỷ lệ dương ${"%.1f".format(stats.winRatePct)}%")
                    Text("Được AI chấp nhận: ${stats.acceptedCount} • tỷ lệ PnL dương ${"%.1f".format(stats.acceptedWinRatePct)}%")
                    Text("Bị AI từ chối: ${stats.rejectedCount} • tỷ lệ PnL vẫn dương ${"%.1f".format(stats.rejectedPositivePct)}%")
                    Text("Sai lệch tuyệt đối dự báo Net trung bình: ${"%.5f".format(stats.averageForecastError)} USDT")
                    Text("PnL mô phỏng tổng: ${"%.5f".format(stats.realizedNet)} USDT")
                    Text("Ngưỡng điểm gợi ý: ${stats.recommendedMinScore?.let { "$it/100" } ?: "Chưa đủ dữ liệu"}")
                    Text("Mẫu cơ hội tốt nhất: ${stats.patternInsight}")
                    Text("Rủi ro đuôi 10% theo PnL giá: ${stats.adverseRiskPct?.let { "${"%.3f".format(it)}%" } ?: "Cần ít nhất 20 mẫu có kết quả"}")
                    Text("Walk-forward 70/30: ${stats.walkForwardMessage}")
                    Text(stats.message, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "V2.1 bổ sung nhóm mẫu theo funding/spread/biến động, phân vị 10% của biến động PnL giá và kiểm định train/holdout theo thời gian. Mọi thống kê cần đủ mẫu; kết quả vẫn là mô phỏng từ giá mark lần quét đầu sau settlement, phí/spread/slippage ước tính, không phải giá khớp hoặc xác nhận Binance trả funding. Không tự thay đổi ngưỡng/AUTO.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

OutlinedTextField(
            value = threshold,
            onValueChange = onThresholdChange,
            label = { Text("Funding tối thiểu (% mỗi kỳ)") },
            supportingText = { Text("Mặc định 1.5% mỗi kỳ. API Binance trả rate dạng thập phân; ứng dụng quy đổi sang %.") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = margin,
            onValueChange = onMarginChange,
            label = { Text("Ký quỹ mỗi lệnh (USDT)") },
            supportingText = {
                val amount = margin.toDoubleOrNull()
                Text(if (amount != null && amount > 0.0)
                    "AUTO chọn đòn bẩy mô phỏng theo bộ giới hạn rủi ro hiện tại. Notional ước tính sẽ bằng \$${"%.2f".format(amount)} × đòn bẩy."
                else "Nhập số USDT lớn hơn 0.")
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Đòn bẩy: AUTO MAX có kiểm soát rủi ro • Giới hạn PAPER hiện tại tối đa 10x. Mức thực tế trên Binance phụ thuộc symbol, notional tier và tài khoản; chưa xác minh qua API tài khoản.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (busy) "ĐANG QUÉT..." else "QUÉT CƠ HỘI")
            }
            FilterChip(
                selected = autoEnabled,
                onClick = { onAutoChange(!autoEnabled) },
                label = { Text(if (autoEnabled) "AUTO: BẬT" else "AUTO: TẮT") }
            )
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Trạng thái", style = MaterialTheme.typography.titleMedium)
                Text(status)
                Text(
                    "Bộ lọc: funding ≥ ngưỡng • volume 24h ≥ 20 triệu USDT • spread ≤ 0,10% • biến động 24h ≤ 12% • vào khoảng T−5s.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Text("NHẬT KÝ SỰ KIỆN", style = MaterialTheme.typography.titleMedium)
        val recentEvents = history.sortedByDescending { maxOf(it.openedAt, it.exitPrice?.let { _ -> it.settlementTime } ?: 0L) }.take(6)
        if (recentEvents.isEmpty()) {
            Text("Chưa có sự kiện giao dịch. Các lần quét gần nhất được hiển thị ở bảng thời gian thực.")
        } else {
            recentEvents.forEach { p ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        val timeLabel = android.text.format.DateFormat.format("HH:mm:ss", java.util.Date(p.openedAt)).toString()
                        Text("$timeLabel • ${p.symbol} • ${p.side}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (p.status == "CLOSED")
                                "ĐÃ ĐÓNG PAPER • Net ${"%.5f".format(p.netPnl)} USDT"
                            else "ĐANG MỞ PAPER • chờ mốc funding",
                            color = if (p.status == "CLOSED") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                        )
                        Text(p.note.ifBlank { "Entry ${p.entryPrice} • Funding ${p.fundingRatePct}%" },
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text("Cơ hội hiện tại (${candidates.size})", style = MaterialTheme.typography.titleMedium)
        if (candidates.isEmpty()) Text("Chưa có cặp đạt đầy đủ bộ lọc. Không ép vào lệnh.")
        candidates.take(30).forEach { c ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(c.symbol, style = MaterialTheme.typography.titleMedium)
                        Text("${if (c.fundingRatePct >= 0) "+" else ""}%.4f%%".format(c.fundingRatePct))
                    }
                    Text("Hướng nhận funding: ${if (c.side == Side.SHORT) "SHORT" else "LONG"}")
                    Text("Mark: ${c.markPrice} • Volume 24h: ${"%.1f".format(c.quoteVolume / 1_000_000)}M USDT")
                    Text("Spread: ${"%.4f".format(c.spreadPct)}% • Biến động 24h: ${"%.2f".format(c.changePct24h)}%")
                    val marginValue = margin.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 2.0
                    val assessment = FundingOpportunityAnalyzer.assess(c, marginValue, 10)
                    Text(
                        "AI rule score: ${assessment.score}/100 • ${assessment.decision} (tham chiếu 10x)",
                        color = if (assessment.decision == "ĐỦ ĐIỀU KIỆN PAPER") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Ước tính tham chiếu 10x: funding ${"%.5f".format(assessment.expectedFundingUsdt)} USDT • Chi phí ${"%.5f".format(assessment.estimatedCostsUsdt)} • Dự phòng rủi ro giá ${"%.5f".format(assessment.adversePriceRiskUsdt)} • Net ${"%.5f".format(assessment.expectedNetUsdt)} USDT",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Rủi ro giá bất lợi ước tính: ${"%.3f".format(assessment.adverseMovePct)}% (proxy từ biến động 24h, không phải dự báo)")
                    Text(assessment.reasons.joinToString(" "))
                    Text( "Còn ${(c.nextFundingTime - System.currentTimeMillis()).coerceAtLeast(0L) / 1000}s đến funding")
                }
            }
        }
        Text("Lịch sử giao dịch Funding (${history.size})", style = MaterialTheme.typography.titleMedium)
        if (history.isEmpty()) Text("Chưa có lệnh Funding mô phỏng.")
        history.takeLast(20).reversed().forEach { p ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("#${p.id} • ${p.symbol} • ${p.side} • ${p.leverage}x", style = MaterialTheme.typography.titleSmall)
                    Text("Entry ${p.entryPrice} • Margin ${p.margin} USDT • Funding ${p.fundingRatePct}%")
                    if (p.status == "CLOSED") {
                        Text("Exit ${p.exitPrice} • Funding giả lập +${"%.5f".format(p.fundingReceived)} USDT")
                        Text("PnL giá ${"%.5f".format(p.pricePnl)} • Chi phí ${"%.5f".format(p.feesAndCosts)} • Net ${"%.5f".format(p.netPnl)} USDT")
                    } else {
                        Text("ĐANG MỞ • chờ thanh toán funding")
                    }
                }
            }
        }
        HorizontalDivider()
        Text(
            "Chỉ là mô phỏng. Mục tiêu vào lệnh T−5s (cửa sổ 3–7s), quét khoảng mỗi giây; sau timestamp funding, đóng ở lần quét thành công đầu tiên. " +
                "Không có xác nhận funding payment thực tế và không gửi lệnh Binance. Trạng thái PAPER được lưu trên máy; sau khi mở lại app, AUTO luôn tắt và cần bật thủ công. Khi app đóng, Funding Hunter không quét nền.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarCompat(title: String, subtitle: String) {
    CenterAlignedTopAppBar(title = {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary)
        }
    })
}

@Composable
private fun DashboardScreen(
    paper: PaperTradingEngine,
    coins: List<MarketCoin>,
    prices: Map<String, Double>,
    key: Int
) {
    val wallet = paper.wallet
    val open = paper.openTrades()
    val report = PaperAnalytics.calculate(wallet, paper.history(), prices)
    val unpricedOpen = open.count { !prices.containsKey(it.symbol) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Tài sản mô phỏng", style = MaterialTheme.typography.titleMedium)
        Text("%.4f USDT".format(report.estimatedEquity), style = MaterialTheme.typography.headlineLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Spot", wallet.spot, Modifier.weight(1f)); MetricCard("Futures", wallet.futures, Modifier.weight(1f)); MetricCard("Dự phòng", wallet.reserve, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Lệnh mở", open.size.toDouble(), Modifier.weight(1f))
            MetricCard("PnL đã chốt", report.realizedPnl, Modifier.weight(1f))
        }
        HorizontalDivider()
        Text("HIỆU QUẢ PAPER TRADING", style = MaterialTheme.typography.titleMedium)
        Text("Equity ước tính: %.4f USDT".format(report.estimatedEquity),
            style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("PnL chưa chốt", report.unrealizedPnl, Modifier.weight(1f))
            MetricCard("Win rate %", report.winRatePct, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Max drawdown %", report.maxDrawdownPct, Modifier.weight(1f))
            MetricCard("Lệnh đã đóng", report.closedTrades.toDouble(), Modifier.weight(1f))
        }
        Text("Profit Factor: ${report.profitFactor?.let { "%.2f".format(it) } ?: "Chưa đủ dữ liệu lỗ để tính"}")
        Text("Lệnh thắng: ${report.wins} • Lệnh thua: ${report.losses} • Tổng lãi: %.4f • Tổng lỗ: %.4f USDT".format(
            report.grossProfit, report.grossLoss
        ), style = MaterialTheme.typography.bodySmall)
        Text("Phân tích theo thị trường", style = MaterialTheme.typography.titleSmall)
        report.marketBreakdown.forEach { item ->
            Text("${item.market}: ${item.trades} lệnh • thắng ${item.wins} • thua ${item.losses} • PnL %.4f USDT • Win rate %.1f%%".format(
                item.realizedPnl, item.winRatePct
            ), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (unpricedOpen > 0) "Chú ý: $unpricedOpen vị thế chưa có giá mới; Equity/PnL chưa chốt đang bị thiếu phần định giá của các vị thế này."
            else "PnL chưa chốt và Equity dùng giá ticker công khai mới nhất cùng phí ước tính.",
            style = MaterialTheme.typography.bodySmall,
            color = if (unpricedOpen > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
        )
        Text("Drawdown tính từ vốn mô phỏng 10 USDT theo các giao dịch đã đóng và equity hiện tại; không phải thống kê drawdown theo từng tick.")
        Text("Lệnh đang mở", style = MaterialTheme.typography.titleMedium)
        if (open.isEmpty()) Text("Chưa có giao dịch mô phỏng.")
        open.forEach { Text("${it.symbol} • ${it.market} • ${it.side} • Entry ${it.entry}") }
        Text("Top tín hiệu phân tích đa khung", style = MaterialTheme.typography.titleMedium)
        coins.sortedByDescending { it.score }.take(5).forEach {
            Text("${it.symbol}: ${it.signal} (${it.score}/100) • RSI ${"%.1f".format(it.rsi)}")
            Text(it.analysis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Text("Giá được lấy từ Binance Public API. Số dư và lệnh là dữ liệu giả lập.")
    }
}

@Composable
private fun MetricCard(label: String, value: Double, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(if (label == "Lệnh mở") value.toInt().toString() else "%.2f".format(value),
                style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ScannerScreen(coins: List<MarketCoin>, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(query, { query = it }, label = { Text("Tìm coin, ví dụ BTCUSDT") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        LazyColumn {
            items(coins.filter { it.symbol.contains(query.trim().uppercase()) }
                .sortedByDescending { it.score }.take(100), key = { it.symbol }) { c ->
                ListItem(
                    headlineContent = { Text("${c.symbol}  •  ${c.signal} ${c.score}/100") },
                    supportingContent = { Text("Giá ${c.price} • 24h ${"%.2f".format(c.changePct)}% • RSI ${"%.1f".format(c.rsi)} • Vol ${"%.1f".format(c.volumeRatio)}x") },
                    trailingContent = { TextButton(onClick = { onPick(c.symbol) }) { Text("Chọn") } }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TradeScreen(
    coins: List<MarketCoin>, selected: String, onSelected: (String) -> Unit,
    spotAmount: String, onSpotAmount: (String) -> Unit,
    margin: String, onMargin: (String) -> Unit,
    side: Side, onSide: (Side) -> Unit,
    paper: PaperTradingEngine, onTrade: (String) -> Unit, key: Int
) {
    val coin = coins.firstOrNull { it.symbol == selected }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Đặt lệnh giả lập", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(selected, { onSelected(it.uppercase()) }, label = { Text("Cặp USDT") }, modifier = Modifier.fillMaxWidth())
        Text("Giá hiện tại: ${coin?.price ?: "đang chờ dữ liệu"}")
        Text("SPOT", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(spotAmount, onSpotAmount, label = { Text("Số tiền Spot (USDT), mặc định 5") })
        Button(onClick = { onTrade("spot") }, enabled = coin != null, modifier = Modifier.fillMaxWidth()) {
            Text("MUA SPOT GIẢ LẬP")
        }
        HorizontalDivider()
        Text("USDⓈ-M FUTURES • Isolated • 10x", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(margin, onMargin, label = { Text("Margin Futures (USDT), mặc định 2") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(side == Side.LONG, { onSide(Side.LONG) }, label = { Text("LONG") })
FilterChip(side == Side.SHORT, { onSide(Side.SHORT) }, label = { Text("SHORT") })
        }
        Button(onClick = { onTrade("futures") }, enabled = coin != null, modifier = Modifier.fillMaxWidth()) {
            Text("MỞ FUTURES GIẢ LẬP")
        }
        Text("Không có lệnh thật. Không kết nối tài khoản hoặc API key.")
        Text("Số dư ví: Spot %.2f • Futures %.2f • Dự phòng %.2f".format(
            paper.wallet.spot, paper.wallet.futures, paper.wallet.reserve))
    }
}

@Composable
private fun HistoryScreen(
    trades: List<PaperTrade>,
    prices: Map<String, Double>,
    onClose: (Long, Double) -> Unit
) {
    LazyColumn {
        if (trades.none { it.status == TradeStatus.CLOSED }) {
            item {
                Text(
                    "Chưa có giao dịch nào được đóng.",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        items(trades.filter { it.status == TradeStatus.CLOSED }
            .sortedByDescending { it.closedAt ?: it.openedAt },
            key = { it.id }
        ) { t ->

            val openedTime = java.text.SimpleDateFormat(
                "dd/MM/yyyy HH:mm:ss",
                java.util.Locale.getDefault()
            ).format(java.util.Date(t.openedAt))

            val closedTime = t.closedAt?.let {
                java.text.SimpleDateFormat(
                    "dd/MM/yyyy HH:mm:ss",
                    java.util.Locale.getDefault()
                ).format(java.util.Date(it))
            } ?: "Không có dữ liệu"

            val pnlText = (if (t.pnl > 0) "+" else "") +
                "%.4f".format(java.util.Locale.US, t.pnl)

            ListItem(
                headlineContent = {
                    Text("${t.symbol} • ${t.market} • ${t.side}")
                },
                supportingContent = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Giá vào: ${t.entry}")
                        Text("Giá đóng: ${t.exit ?: "Không có dữ liệu"}")
                        Text("PnL: $pnlText USDT")
                        Text("Phí: %.6f USDT".format(
                            java.util.Locale.US, t.fees
                        ))
                        Text("Thời gian mở: $openedTime")
                        Text("Thời gian đóng: $closedTime")
                        if (t.note.isNotBlank()) {
                            Text("Ghi chú: ${t.note}")
                        }
                        Text("TRẠNG THÁI: ĐÃ ĐÓNG")
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

        }
    }
}

@Composable
private fun SettingsScreen(
    paper: PaperTradingEngine,
    config: StrategyConfig,
    onReset: () -> Unit,
    onStartBackground: () -> Unit,
    onStopBackground: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cài đặt mô phỏng", style = MaterialTheme.typography.titleLarge)
        Text("Vốn ban đầu: 10 USDT")
        Text("Phân bổ khởi tạo: Spot 5 • Futures 2 • Dự phòng 3 USDT")
        Text("Spot: mục tiêu xấp xỉ +5% net; thoát khẩn cấp khi giảm 10% so với entry.")
        Text("Futures: Isolated 10x; mục tiêu +50% ROI trên margin. Chưa có mô hình thanh lý chính xác.")
        Text("Phí hiện đang dùng giả định Spot 0,10% và Futures 0,05% mỗi chiều. Cần thay bằng phí thực tế của tài khoản.")
        Text("AI chạy local trên điện thoại. Phân tích EMA20/50, RSI, MACD cơ bản, ATR, Volume và regime 5m/15m/1h.")
        HorizontalDivider()
        Text("THEO DÕI GIÁ NỀN", style = MaterialTheme.typography.titleMedium)
        Text("Dịch vụ foreground lấy giá Binance Public API mỗi 30 giây và có thể gửi cảnh báo khi màn hình tắt. Cần bật thủ công.")
        Button(onClick = onStartBackground, modifier = Modifier.fillMaxWidth()) { Text("BẬT THEO DÕI NỀN") }
        OutlinedButton(onClick = onStopBackground, modifier = Modifier.fillMaxWidth()) { Text("TẮT THEO DÕI NỀN") }
        Text("Chỉ cảnh báo theo snapshot vị thế. Nếu app bị hệ điều hành dừng, dịch vụ không tự đóng lệnh mô phỏng hoặc cập nhật sổ paper.")
        Text("Cấu hình đang dùng: ${config.name} • Volume ≥ ${config.volumeRatioMin}x • RSI LONG ${config.rsiLongMin}-${config.rsiLongMax}")
        Text("Học có kiểm soát: chỉ lưu cấu hình ứng viên khi vượt kiểm chứng ngoài mẫu; nếu không, giữ baseline.")
        Button(onClick = { paper.reset(); onReset() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Text("Đặt lại tài khoản mô phỏng")
        }
    }
}


@Composable
private fun HistoricalLearningScreen(
    walkReport: WalkForwardReport?,
    analogs: List<AnalogResult>,
    model: MlModel?,
    busy: Boolean,
    predictionRecords: List<PredictionRecord>,
    onTrain: (String, Int) -> Unit,
    onClear: () -> Unit
) {
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var months by remember { mutableIntStateOf(6) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI HISTORICAL LEARNING LAB", style = MaterialTheme.typography.titleLarge)
        Text("Huấn luyện đa khung thời gian trực tiếp trên điện thoại.")
        val signalRows = predictionRecords.groupBy { it.horizonMinutes }.toSortedMap()
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("LIVE SIGNAL LEARNING • 5M / 15M / 1H / 4H", style = MaterialTheme.typography.titleMedium)
                Text("Đã ghi ${predictionRecords.map { "${it.symbol}:${it.signal}:${it.createdAt}" }.distinct().size} tín hiệu. LONG/SHORT được chấm đúng/sai khi biến động thuận/ngược ít nhất 0,15%; vùng nhỏ hơn là đi ngang. WAIT được thống kê riêng: AVOIDED MOVE khi giá dịch chuyển từ 0,50% trở lên.")
                listOf(5, 15, 60, 240).forEach { horizon ->
                    val rows = signalRows[horizon].orEmpty()
                    val directional = rows.filter { it.signal == "LONG" || it.signal == "SHORT" }
                    val done = directional.filter { it.status != PredictionStatus.PENDING }
                    val correct = done.count { it.status == PredictionStatus.CORRECT }
                    val wrong = done.count { it.status == PredictionStatus.INCORRECT }
                    val waitAvoided = rows.count { it.signal == "WAIT" && it.status == PredictionStatus.AVOIDED_MOVE }
                    Text("${if (horizon < 60) "${horizon} phút" else "${horizon / 60} giờ"}: ${directional.size} LONG/SHORT • Đúng $correct • Sai $wrong • Chờ ${directional.count { it.status == PredictionStatus.PENDING }} • WAIT né biến động $waitAvoided",
                        style = MaterialTheme.typography.bodySmall)
                    if (correct + wrong > 0) Text("Độ chính xác directional: ${"%.1f".format(java.util.Locale.US, correct.toDouble() / (correct + wrong) * 100)}%",
                        style = MaterialTheme.typography.labelSmall)
                }
                val completedDirectional = predictionRecords.filter {
                    (it.signal == "LONG" || it.signal == "SHORT") && it.status != PredictionStatus.PENDING
                }
                val directionalCorrect = completedDirectional.count { it.status == PredictionStatus.CORRECT }
                val directionalWrong = completedDirectional.count { it.status == PredictionStatus.INCORRECT }
                val scored = directionalCorrect + directionalWrong
                Text("ĐỀ XUẤT HỌC TẬP", style = MaterialTheme.typography.titleSmall)
                Text(when {
                    scored < 20 -> "Chưa đủ mẫu (cần ít nhất 20 kết quả directional) để đề xuất thay đổi. Giữ nguyên tham số."
                    directionalCorrect.toDouble() / scored < 0.45 -> "Tỷ lệ đúng thấp trong mẫu quan sát. Đề xuất tạo candidate siết bộ lọc tín hiệu và chạy backtest + walk-forward; chưa thay đổi cấu hình."
                    directionalCorrect.toDouble() / scored >= 0.60 -> "Kết quả quan sát đang ổn định tương đối. Đề xuất giữ cấu hình hiện tại và tiếp tục thu thập mẫu."
                    else -> "Kết quả chưa đủ tốt để thay đổi có căn cứ. Tiếp tục thu thập dữ liệu và kiểm chứng."
                }, style = MaterialTheme.typography.bodySmall)
                val byRegime = predictionRecords.filter { it.signal != "WAIT" && it.status != PredictionStatus.PENDING }
                    .groupBy { it.regime }
                Text("Theo điều kiện thị trường", style = MaterialTheme.typography.titleSmall)
                if (byRegime.isEmpty()) Text("Chưa đủ mẫu đã hoàn tất.")
                byRegime.forEach { (regime, rows) ->
                    val c = rows.count { it.status == PredictionStatus.CORRECT }
                    val w = rows.count { it.status == PredictionStatus.INCORRECT }
                    Text("$regime • ${rows.size} mẫu • Đúng $c / Sai $w", style = MaterialTheme.typography.bodySmall)
                }
                Text("Chỉ ghi nhận khi ứng dụng nhận được snapshot giá sau thời điểm mục tiêu. Nếu app tắt hoặc mất mạng, mốc được đánh giá ở lần cập nhật đầu tiên sau hạn; không tái tạo tick lịch sử. Kết quả không phải PnL hay xác suất lợi nhuận.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        OutlinedTextField(symbol, { symbol = it.uppercase() }, label = { Text("Coin cần học, ví dụ ETHUSDT") }, modifier = Modifier.fillMaxWidth())
        Text("Dữ liệu quá khứ")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 6, 12).forEach { m ->
                FilterChip(selected = months == m, onClick = { months = m }, label = { Text("$m tháng") })
            }
        }
        Button(onClick = { onTrain(symbol, months) }, enabled = !busy && symbol.endsWith("USDT"), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "ĐANG TẢI & HUẤN LUYỆN..." else "HUẤN LUYỆN WALK-FORWARD")
        }
        Text("Khung: 5m (tối đa 90 ngày), 15m/1h/4h (theo khoảng thời gian chọn). BTC 15m làm biến tham chiếu.")
        HorizontalDivider()
        Text("Mô hình local", style = MaterialTheme.typography.titleMedium)
        if (model == null) Text("Chưa có mô hình được chấp nhận.")
        else {
            Text("${model.version} • ${model.symbol} • ${model.interval}")
            Text("Huấn luyện: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(model.trainedAt))}")
            Text("Số trọng số: ${model.weights.size}")
            OutlinedButton(onClick = onClear) { Text("Xóa mô hình local") }
        }
        if (walkReport != null) {
            HorizontalDivider()
            Text(if (walkReport.accepted) "MÔ HÌNH ĐẠT KIỂM CHỨNG" else "GIỮ MÔ HÌNH CŨ",
                color = if (walkReport.accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium)
            Text(walkReport.explanation)
            Text("Nến: 15m ${walkReport.candles15m} • 5m ${walkReport.candles5m} • 1h ${walkReport.candles1h} • 4h ${walkReport.candles4h}")
            Text("Mẫu ${walkReport.samples} • Số fold ${walkReport.folds}")
            Text("Accuracy TB ${(walkReport.averageAccuracy*100).format1()}% • Baseline ${(walkReport.baselineAccuracy*100).format1()}%")
            Text("Precision tăng TB ${(walkReport.averagePrecisionUp*100).format1()}%")
        }
        if (analogs.isNotEmpty()) {
            HorizontalDivider()
            Text("Tình huống lịch sử tương tự", style = MaterialTheme.typography.titleMedium)
            analogs.forEachIndexed { index, a ->
                Text("${index+1}. ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(a.timestamp))} • ${a.outcome} • Sau 4 nến ${"%.2f".format(a.forwardReturnPct)}% • Similarity ${(a.similarity*100).format1()}%")
            }
            Text("Độ tương đồng chỉ là thông tin tham khảo, không phải xác suất lặp lại.")
        }
        Text("Mô hình nghiên cứu chưa được kết nối với bộ thực thi lệnh.")
    }
}

private fun Double.format1(): String = "%.1f".format(this)

@Composable
private fun BacktestScreen(
    coins: List<MarketCoin>,
    report: BacktestReport?,
    optimizationReport: StrategyOptimizationReport?,
    busy: Boolean,
    onRun: (String) -> Unit
) {
    var symbol by remember { mutableStateOf("BTCUSDT") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("BACKTEST & KIỂM CHỨNG", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(symbol, { symbol = it.uppercase() }, label = { Text("Cặp coin") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onRun(symbol) }, enabled = !busy && symbol.endsWith("USDT"), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "ĐANG TỐI ƯU..." else "TỐI ƯU AI • 60 NGÀY • 15M")
        }
        if (optimizationReport != null) {
            val result = optimizationReport
            Text(result.explanation, style = MaterialTheme.typography.bodyMedium)
            Text("Đã thử ${result.testedCandidates} bộ tham số • ${result.validationCandles} nến kiểm chứng • 3 giai đoạn",
                style = MaterialTheme.typography.labelMedium)
            Text("Dữ liệu AI Self-Learning: ${result.learningSamples} mẫu directional" +
                (result.learningAccuracyPct?.let { " • Độ chính xác ${"%.1f".format(it)}%" } ?: " • Chưa đủ dữ liệu"),
                style = MaterialTheme.typography.bodySmall)
            if (result.rankedCandidates.isNotEmpty()) {
                Text("TOP 3 CẤU HÌNH ỨNG VIÊN", style = MaterialTheme.typography.titleSmall)
                result.rankedCandidates.forEachIndexed { index, candidate ->
                    Text("${index + 1}. ${candidate.config.name} • Net ${"%.2f".format(candidate.averageReturnPct)}% • DD ${"%.2f".format(candidate.worstDrawdownPct)}% • ${candidate.totalTrades} lệnh • ${candidate.profitableFolds}/3 giai đoạn dương",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("BASELINE", style = MaterialTheme.typography.titleSmall)
            OptimizerMetricsBlock(result.baseline)
            if (result.winner != null) {
                Text("CANDIDATE ĐẠT KIỂM CHỨNG • CHỜ PHÊ DUYỆT", style = MaterialTheme.typography.titleSmall)
                OptimizerMetricsBlock(result.winner)
                Text("Volume tối thiểu ${"%.2f".format(result.winner.config.volumeRatioMin)}x • RSI LONG ${"%.1f".format(result.winner.config.rsiLongMin)}–${"%.1f".format(result.winner.config.rsiLongMax)}",
                    style = MaterialTheme.typography.bodySmall)
            }
        } else if (report != null) {
            Text(report.reason)
            MetricsBlock(report.baseline)
            MetricsBlock(report.candidate)
        }
        Text("Lưu ý: bộ tối ưu hiện dùng chiến lược backtest LONG hiện có; chưa tối ưu SHORT, chưa mô phỏng đầy đủ spread, trượt giá, funding, thanh khoản hoặc khớp lệnh từng phần. Kết quả chỉ áp dụng PAPER TRADING.")
    }
}

@Composable
private fun MetricsBlock(m: BacktestMetrics) {
    Text("Lệnh ${m.trades} • Win rate ${"%.1f".format(m.winRatePct)}% • Net ${"%.2f".format(m.netReturnPct)}%")
    Text("Drawdown ${"%.2f".format(m.maxDrawdownPct)}% • Profit factor ${"%.2f".format(m.profitFactor)}")
}


@Composable
private fun NewsScreen(
    digest: NewsDigest?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit
) {
    var highOnly by remember { mutableStateOf(false) }
    val articles = digest?.articles.orEmpty()
    val shown = if (highOnly) articles.filter {
        it.impact == NewsImpact.CRITICAL || it.impact == NewsImpact.HIGH
    } else articles

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("NEWS INTELLIGENCE", style = MaterialTheme.typography.titleLarge)
                Text("Tin kinh tế, chính sách và crypto • phân tích tại thiết bị",
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onRefresh, enabled = !busy) {
                Text(if (busy) "Đang tải" else "Cập nhật")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !highOnly, onClick = { highOnly = false }, label = { Text("Tất cả") })
            FilterChip(selected = highOnly, onClick = { highOnly = true }, label = { Text("Tin quan trọng") })
        }
        if (digest == null) {
            Text("Chưa có dữ liệu tin tức. Nhấn Cập nhật để tải nguồn RSS.")
        } else {
            Text("Nguồn hoạt động: ${digest.sourceCount}/5 • ${articles.size} tin trong 7 ngày",
                style = MaterialTheme.typography.labelMedium)
            if (digest.fetchedAt > 0) {
                Text("Cập nhật lúc: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(digest.fetchedAt))}",
                    style = MaterialTheme.typography.labelSmall)
            }
            digest.errors.forEach { err ->
                Text("Nguồn tạm lỗi: $err", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (shown.isEmpty()) Text("Chưa có tin thuộc bộ lọc này.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { article ->
                    Card(onClick = { if (article.url.isNotBlank()) onOpen(article.url) }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(article.source, style = MaterialTheme.typography.labelMedium)
                                val label = when (article.impact) {
                                    NewsImpact.CRITICAL -> "RẤT CAO"
                                    NewsImpact.HIGH -> "CAO"
                                    NewsImpact.MEDIUM -> "TRUNG BÌNH"
                                    NewsImpact.LOW -> "THẤP"
                                }
                                val color = when (article.impact) {
                                    NewsImpact.CRITICAL, NewsImpact.HIGH -> MaterialTheme.colorScheme.error
                                    NewsImpact.MEDIUM -> MaterialTheme.colorScheme.tertiary
                                    NewsImpact.LOW -> MaterialTheme.colorScheme.secondary
                                }
                                Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(article.title, style = MaterialTheme.typography.titleSmall)
                            if (article.summary.isNotBlank()) Text(article.summary, style = MaterialTheme.typography.bodySmall)
                            if (article.topics.isNotEmpty()) Text(article.topics.joinToString(" • "), style = MaterialTheme.typography.labelSmall)
                            Text(article.relevance, style = MaterialTheme.typography.bodySmall)
                            Text(article.caution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            if (article.publishedAt > 0) Text(
                                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(article.publishedAt)),
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (article.url.isNotBlank()) Text("Mở nguồn gốc ↗", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        


@Composable
private fun DecisionFusionScreen(
    coins: List<MarketCoin>,
    digest: NewsDigest?,
    historicalModel: MlModel?,
    predictionRecords: List<PredictionRecord>,
    onClearLearning: () -> Unit,
    onPick: (String) -> Unit
) {
    val decisions = remember(coins, digest, historicalModel) {
        coins.take(30).map { DecisionFusionEngine.evaluate(it, digest, historicalModel) }
            .sortedWith(compareBy<FusedDecision> { if (it.decision == "WAIT") 1 else 0 }.thenByDescending { it.confidence })
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI DECISION FUSION", style = MaterialTheme.typography.titleLarge)
        Text("Hợp nhất kỹ thuật + bộ lọc tin tức + trạng thái mô hình lịch sử",
            style = MaterialTheme.typography.bodySmall)
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Nguyên tắc an toàn", style = MaterialTheme.typography.titleSmall)
                Text("Tin tức chỉ làm giảm độ tin cậy hoặc chuyển sang WAIT; không tự tạo LONG/SHORT. Mô hình lịch sử chưa được dùng bỏ phiếu hướng.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Chế độ hiện tại: PAPER / KHÔNG GỬI LỆNH THẬT",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        if (digest == null || digest.sourceCount == 0) {
            Text("Cảnh báo: chưa xác minh được nguồn tin. AI Fusion sẽ không coi việc thiếu tin là an toàn.")
        } else {
            Text("Tin đã tải từ ${digest.sourceCount}/5 nguồn • ${digest.criticalOrHigh.size} tin mức cao/rất cao",
                style = MaterialTheme.typography.labelMedium)
        }
        val stats = remember(predictionRecords) { PredictionLearningStoreStats.calculate(predictionRecords) }
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("TỰ ĐÁNH GIÁ DỰ BÁO – V0.8", style = MaterialTheme.typography.titleSmall)
                Text("Đã ghi ${stats.total} dự báo • Chờ kết quả ${stats.pending}",
                    style = MaterialTheme.typography.bodySmall)
                Text("Đã đủ thời gian đánh giá: ${stats.resolved} • Đúng ${stats.correct} • Sai ${stats.incorrect} • Đi ngang ${stats.flat}",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (stats.resolved == 0) "Chưa đủ mẫu để tính độ chính xác"
                    else "Tỷ lệ đúng ${"%.1f".format(stats.accuracyPct)}% • Biến động thuận hướng trung bình ${"%.3f".format(stats.averageDirectionalReturnPct)}%",
                    style = MaterialTheme.typography.bodySmall)
                Text("Mỗi dự báo được đối chiếu với giá thị trường sau 60 phút. Đây là đánh giá hướng giá thô, chưa trừ phí, spread hoặc trượt giá.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClearLearning) { Text("Xóa dữ liệu học") }
                }
            }
        }
        if (decisions.isEmpty()) {
            Text("Đang chờ dữ liệu thị trường...")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(decisions, key = { it.symbol }) { item ->
                    Card(onClick = { onPick(item.symbol) }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(item.symbol, style = MaterialTheme.typography.titleMedium)
                                val color = when (item.decision) {
                                    "LONG" -> MaterialTheme.colorScheme.primary
                                    "SHORT" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                                Text(item.decision, color = color, style = MaterialTheme.typography.titleSmall)
                            }
                            Text("Độ tin cậy ${item.confidence}/100 • Kỹ thuật ${item.technicalSignal} (${item.technicalScore})",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Rủi ro tin tức: ${item.newsRisk} • Rất cao ${item.criticalNews} • Cao ${item.highNews}",
                                style = MaterialTheme.typography.bodySmall)
                            item.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            Text(item.caution, color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.labelSmall)
                            Text("Chạm để mở màn hình giao dịch mô phỏng",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}


private object PredictionLearningStoreStats {
    fun calculate(records: List<PredictionRecord>): PredictionStats {
        val directional = records.filter { it.signal == "LONG" || it.signal == "SHORT" }
        val resolved = directional.filter { it.status != PredictionStatus.PENDING }
        val correct = resolved.count { it.status == PredictionStatus.CORRECT }
        val incorrect = resolved.count { it.status == PredictionStatus.INCORRECT }
        val flat = resolved.count { it.status == PredictionStatus.FLAT }
        val returns = resolved.mapNotNull { it.directionalReturnPct }
        return PredictionStats(
            total = directional.size,
            resolved = resolved.size,
            correct = correct,
            incorrect = incorrect,
            flat = flat,
            pending = directional.count { it.status == PredictionStatus.PENDING },
            accuracyPct = if (correct + incorrect == 0) 0.0 else correct.toDouble() / (correct + incorrect) * 100.0,
            averageDirectionalReturnPct = if (returns.isEmpty()) 0.0 else returns.average()
        )
    }
}


@Composable
private fun OptimizerMetricsBlock(result: OptimizerCandidateResult) {
    Text("Lợi nhuận TB ${"%.2f".format(result.averageReturnPct)}% • Drawdown xấu nhất ${"%.2f".format(result.worstDrawdownPct)}%",
        style = MaterialTheme.typography.bodySmall)
    Text("Tổng lệnh ${result.totalTrades} • Giai đoạn có lãi ${result.profitableFolds}/3",
        style = MaterialTheme.typography.bodySmall)
    result.folds.forEachIndexed { index, fold ->
        Text("Giai đoạn ${index + 1}: ${fold.trades} lệnh • Net ${"%.2f".format(fold.netReturnPct)}% • DD ${"%.2f".format(fold.maxDrawdownPct)}%",
            style = MaterialTheme.typography.labelSmall)
    }
}


@Composable
private fun BinanceReadOnlyScreen(paper: PaperTradingEngine, marketCoins: List<MarketCoin>) {
    val context = LocalContext.current
    val store = remember { BinanceCredentialStore(context) }
    val client = remember { BinanceReadOnlyClient() }
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(store.hasCredentials()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Chưa kết nối. Hãy lưu API Key chỉ đọc trên thiết bị.") }
    var snapshot by remember { mutableStateOf<BinanceReadOnlySnapshot?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("BINANCE ACCOUNT • READ-ONLY", style = MaterialTheme.typography.titleLarge)
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Bảo mật & quyền API", style = MaterialTheme.typography.titleMedium)
                Text("Chỉ dùng API Key có quyền đọc. Tắt Spot Trading, Futures Trading và Withdrawals trên Binance. Không gửi API Secret qua chat.")
                Text("Thông tin được mã hóa AES-GCM bằng Android Keystore và lưu trong vùng riêng của ứng dụng. Không có chức năng đặt lệnh hoặc rút tiền trong mô-đun này.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(apiKey, { apiKey = it.trim() }, label = { Text("Binance API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiSecret, { apiSecret = it }, label = { Text("Binance API Secret") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    runCatching { store.save(apiKey.trim(), apiSecret) }
                        .onSuccess { saved = true; apiKey = ""; apiSecret = ""; status = "Đã lưu thông tin mã hóa trên thiết bị. Chưa xác minh kết nối." }
                        .onFailure { status = it.message ?: "Không thể lưu thông tin." }
                }, enabled = apiKey.isNotBlank() && apiSecret.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("LƯU API KEY AN TOÀN") }
                Text(if (saved) "API Key đã lưu trên thiết bị" else "Chưa có API Key được lưu", style = MaterialTheme.typography.labelMedium)
                Button(onClick = {
                    scope.launch {
                        busy = true
                        status = "Đang xác minh quyền đọc Spot và USDⓈ-M Futures..."
                        try {
                            val (key, secret) = store.load()
                            snapshot = client.fetchAccount(key, secret)
                            status = "Kết nối thành công • Đọc được tài khoản Spot và Futures."
                        } catch (e: Exception) {
                            snapshot = null
                            status = e.message?.take(240) ?: "Kết nối thất bại. Kiểm tra mạng và quyền API."
                        } finally { busy = false }
                    }
                }, enabled = saved && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "ĐANG KẾT NỐI..." else "KIỂM TRA KẾT NỐI & ĐỌC SỐ DƯ") }
                OutlinedButton(onClick = {
                    store.clear(); saved = false; snapshot = null; apiKey = ""; apiSecret = ""
                    status = "Đã xóa API Key/Secret đã lưu khỏi vùng lưu trữ ứng dụng."
                }, enabled = saved && !busy, modifier = Modifier.fillMaxWidth()) { Text("XÓA API KEY ĐÃ LƯU") }
            }
        }
        Text(status, color = if (snapshot != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
        snapshot?.let { data ->
            val assessment = remember(data, marketCoins) { BinanceAccountAdvisor.assess(data, marketCoins) }
            Card(colors = CardDefaults.cardColors(containerColor = when {
                assessment.riskScore >= 60 -> MaterialTheme.colorScheme.errorContainer
                assessment.riskScore >= 25 -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            })) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("TONY TRAN • AI ACCOUNT ADVISOR", style = MaterialTheme.typography.titleMedium)
                    Text(assessment.riskLabel, style = MaterialTheme.typography.titleLarge)
                    Text("Điểm rủi ro quy tắc: ${assessment.riskScore}/100 • Vị thế mở: ${assessment.openPositions}")
                    Text("Futures wallet: ${"%.4f".format(java.util.Locale.US, assessment.futuresWalletUsdt)} USDT • PnL thả nổi: ${"%.4f".format(java.util.Locale.US, assessment.futuresUnrealizedUsdt)} USDT")
                    assessment.advice.forEach { item ->
                        HorizontalDivider()
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall)
                        Text("Đề xuất: ${item.action}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Đây là bộ quy tắc phân tích cục bộ, không phải mô hình dự đoán lợi nhuận. Điểm rủi ro là chỉ báo nội bộ, không phải margin ratio hay giá thanh lý Binance.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("Cập nhật: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(data.fetchedAt))}", style = MaterialTheme.typography.labelSmall)
            HorizontalDivider()
            Text("SPOT • TÀI SẢN KHÁC 0", style = MaterialTheme.typography.titleMedium)
            if (data.spot.isEmpty()) Text("Không có số dư khác 0.")
            data.spot.forEach { b -> Text("${b.asset}: khả dụng ${"%.8f".format(java.util.Locale.US, b.free)} • khóa ${"%.8f".format(java.util.Locale.US, b.locked)}") }
            HorizontalDivider()
            Text("USDⓈ-M FUTURES", style = MaterialTheme.typography.titleMedium)
            if (data.futures.isEmpty()) Text("Không có số dư Futures khác 0.")
            data.futures.forEach { b -> Text("${b.asset}: ví ${"%.8f".format(java.util.Locale.US, b.wallet)} • PnL chưa thực hiện ${"%.8f".format(java.util.Locale.US, b.unrealized)}") }
            HorizontalDivider()
            Text("VỊ THẾ FUTURES ĐANG MỞ", style = MaterialTheme.typography.titleMedium)
            if (data.positions.isEmpty()) Text("Không có vị thế Futures đang mở.")
            data.positions.forEach { p ->
                Text("${p.symbol} • ${if (p.amount > 0) "LONG" else "SHORT"} ${"%.6f".format(java.util.Locale.US, kotlin.math.abs(p.amount))} • Entry ${p.entryPrice} • PnL ${p.unrealized} USDT • ${p.leverage}x • ${if (p.isolated) "Isolated" else "Cross"}")
            }
            HorizontalDivider()
            Text("ĐỐI CHIẾU VỚI VÍ PAPER", style = MaterialTheme.typography.titleMedium)
            Text("PAPER Spot: ${"%.2f".format(paper.wallet.spot)} USDT • Futures: ${"%.2f".format(paper.wallet.futures)} USDT • Dự phòng: ${"%.2f".format(paper.wallet.reserve)} USDT")
            Text("Đây là số liệu riêng biệt. Không tự đồng bộ số dư thật vào ví PAPER và không quy đổi tổng tài sản nhiều đồng sang USDT.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Lưu ý: kết nối đọc tài khoản cần API Key có quyền đọc Spot và USDⓈ-M Futures. Nếu chỉ cấp quyền Spot, phần Futures có thể báo lỗi quyền truy cập. Không bật quyền giao dịch để khắc phục.", style = MaterialTheme.typography.bodySmall)
    }
}


@Composable
private fun MarketIntelligenceScreen(
    coins: List<MarketCoin>,
    funding: FundingScanResult?
) {
    val report = remember(coins, funding) { MarketIntelligenceEngine.analyze(coins, funding) }
    val actionable = report.items.count { it.decision != "WAIT" }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("AI MARKET INTELLIGENCE", style = MaterialTheme.typography.titleLarge)
        Text("Tổng hợp BTC • độ rộng thị trường • xu hướng 1h • thanh khoản • ATR% • Funding",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("BTC 24h %", report.btcChangePct ?: 0.0, Modifier.weight(1f))
            MetricCard("Coin tăng %", report.breadthPct, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Biến động cao", report.highVolatilityCount.toDouble(), Modifier.weight(1f))
            MetricCard("Có Funding", report.fundingCoverage.toDouble(), Modifier.weight(1f))
        }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TỔNG QUAN CHIẾN LƯỢC", style = MaterialTheme.typography.titleMedium)
                Text("Cặp đạt bộ lọc tham khảo: $actionable/${report.items.size}")
                Text("Funding được cập nhật: ${funding?.let { java.text.DateFormat.getTimeInstance().format(java.util.Date(it.localScannedAt)) } ?: "Chưa có dữ liệu"}")
                Text("Thời điểm phân tích: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(report.generatedAt))}",
                    style = MaterialTheme.typography.labelSmall)
                Text("Điểm là điểm quy tắc nội bộ, không phải xác suất thắng. Khi thiếu dữ liệu hoặc tín hiệu xung đột, hệ thống ưu tiên WAIT.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        report.items.take(30).forEach { item ->
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.symbol, style = MaterialTheme.typography.titleMedium)
                        Text("${item.decision} • ${item.score}/100",
                            color = if (item.decision == "WAIT") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                    }
                    Text("BTC: ${item.btcContext} • Regime: ${item.regime}")
                    Text("Funding: ${item.fundingPct?.let { "%.4f%%".format(java.util.Locale.US, it) } ?: "Chưa có"}",
                        style = MaterialTheme.typography.bodySmall)
                    item.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Text(item.caution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        Text("Dữ liệu thị trường công khai và Funding có thể không đồng bộ thời điểm. Không sử dụng điểm này như tín hiệu đặt lệnh tự động.",
            style = MaterialTheme.typography.bodySmall)
    }
}
