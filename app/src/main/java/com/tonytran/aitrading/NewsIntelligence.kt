package com.tonytran.aitrading

import android.content.Context
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

enum class NewsImpact { CRITICAL, HIGH, MEDIUM, LOW }

data class NewsArticle(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val source: String,
    val publishedAt: Long,
    val impact: NewsImpact,
    val topics: List<String>,
    val relevance: String,
    val caution: String
)

data class NewsDigest(
    val articles: List<NewsArticle>,
    val fetchedAt: Long,
    val sourceCount: Int,
    val errors: List<String>
) {
    val criticalOrHigh: List<NewsArticle>
        get() = articles.filter { it.impact == NewsImpact.CRITICAL || it.impact == NewsImpact.HIGH }
}

private data class NewsFeed(val name: String, val url: String)

class NewsIntelligence(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val feeds = listOf(
        NewsFeed("Federal Reserve", "https://www.federalreserve.gov/feeds/press_all.xml"),
        NewsFeed("BLS – Kinh tế Mỹ", "https://www.bls.gov/feed/bls_latest.rss"),
        NewsFeed("BEA – Kinh tế Mỹ", "https://apps.bea.gov/rss/rss.xml"),
        NewsFeed("SEC – Pháp lý", "https://www.sec.gov/rss/news/press.xml"),
        NewsFeed("ECB – Ngân hàng Trung ương", "https://www.ecb.europa.eu/rss/press.html")
    )

    suspend fun fetchDaily(): NewsDigest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val all = mutableListOf<NewsArticle>()
        val errors = mutableListOf<String>()
        feeds.forEach { feed ->
            try {
                val request = Request.Builder()
                    .url(feed.url)
                    .header("User-Agent", "TonyTranAITradingEA/0.6 (news reader)")
                    .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                    .get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val stream = response.body?.byteStream() ?: error("Nguồn không có nội dung")
                    all += parseFeed(stream, feed.name)
                }
            } catch (e: Exception) {
                errors += "${feed.name}: ${e.message ?: "không tải được"}"
            }
            kotlinx.coroutines.delay(150)
        }
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val ranked = all.distinctBy { it.id }
            .filter { it.publishedAt >= cutoff || it.publishedAt == 0L }
            .map { NewsAnalyzer.evaluate(it) }
            .sortedWith(compareByDescending<NewsArticle> { impactRank(it.impact) }.thenByDescending { it.publishedAt })
            .take(100)
        val digest = NewsDigest(ranked, System.currentTimeMillis(), feeds.size - errors.size, errors)
        NewsCache(context).save(digest)
        digest
    }

    private fun parseFeed(input: java.io.InputStream, source: String): List<NewsArticle> {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        val items = mutableListOf<NewsArticle>()
        var insideItem = false
        var title = ""
        var summary = ""
        var link = ""
        var guid = ""
        var date = ""
        var currentTag = ""
        var depth = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.ROOT)
                    if (tag == "item" || tag == "entry") {
                        insideItem = true
                        title = ""; summary = ""; link = ""; guid = ""; date = ""
                        depth = 1
                    } else if (insideItem) {
                        depth++
                        currentTag = tag
                        if (tag == "link" && link.isBlank()) {
                            link = parser.getAttributeValue(null, "href") ?: ""
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (insideItem) {
                        val value = parser.text ?: ""
                        when (currentTag) {
                            "title" -> title += value
                            "description", "summary", "content", "encoded" -> summary += value
                            "link" -> if (link.isBlank()) link = value
                            "guid", "id" -> guid += value
                            "pubdate", "published", "updated", "date" -> date += value
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase(Locale.ROOT)
                    if (insideItem) {
                        if (tag == "item" || tag == "entry") {
                            if (title.isNotBlank()) {
                                val cleanTitle = cleanText(title)
                                val cleanSummary = cleanText(summary).take(700)
                                val published = parseDate(date)
                                val canonical = link.ifBlank { guid }.ifBlank { "$source|$cleanTitle|$published" }
                                items += NewsArticle(
                                    id = canonical.hashCode().toString(),
                                    title = cleanTitle,
                                    summary = cleanSummary,
                                    url = canonical.takeIf { it.startsWith("http") } ?: "",
                                    source = source,
                                    publishedAt = published,
                                    impact = NewsImpact.LOW,
                                    topics = emptyList(),
                                    relevance = "",
                                    caution = ""
                                )
                            }
                            insideItem = false
                            currentTag = ""
                            depth = 0
                        } else {
                            currentTag = ""
                            depth = (depth - 1).coerceAtLeast(0)
                        }
                    }
                }
            }
            parser.next()
        }
        return items
    }

    private fun cleanText(value: String): String =
        value.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ").trim()

    private fun parseDate(value: String): Long {
        if (value.isBlank()) return 0L
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss z", "EEE, dd MMM yyyy HH:mm z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd"
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(value.trim())?.time ?: continue
            } catch (_: Exception) { }
        }
        return 0L
    }

    private fun impactRank(impact: NewsImpact): Int = when (impact) {
        NewsImpact.CRITICAL -> 4
        NewsImpact.HIGH -> 3
        NewsImpact.MEDIUM -> 2
        NewsImpact.LOW -> 1
    }
}

object NewsAnalyzer {
    private val critical = listOf(
        "hack", "exploit", "security breach", "exchange outage", "insolvency",
        "bankruptcy", "depeg", "stablecoin loses peg", "emergency rate",
        "fomc statement", "rate decision", "war declared", "invasion",
        "sanctions", "spot bitcoin etf", "bitcoin etf approval"
    )
    private val high = listOf(
        "federal reserve", "fed", "fomc", "interest rate", "rate cut", "rate hike",
        "cpi", "pce", "nonfarm payroll", "employment situation", "unemployment",
        "inflation", "sec", "cftc", "lawsuit", "charged", "approval", "rejected",
        "etf", "regulation", "tariff", "treasury", "liquidation", "stablecoin",
        "bitcoin", "ethereum", "crypto"
    )
    private val medium = listOf(
        "token unlock", "hard fork", "upgrade", "mainnet", "listing", "delisting",
        "outflow", "inflow", "institutional", "treasury yield", "bond auction",
        "geopolitical", "oil price", "central bank"
    )

    fun evaluate(article: NewsArticle): NewsArticle {
        val text = (article.title + " " + article.summary).lowercase(Locale.ROOT)
        val criticalHits = critical.filter { text.contains(it) }
        val highHits = high.filter { text.contains(it) }
        val mediumHits = medium.filter { text.contains(it) }
        val impact = when {
            criticalHits.isNotEmpty() -> NewsImpact.CRITICAL
            highHits.size >= 2 -> NewsImpact.HIGH
            highHits.isNotEmpty() || mediumHits.isNotEmpty() -> NewsImpact.MEDIUM
            else -> NewsImpact.LOW
        }
        val topics = buildList {
            if (listOf("fed", "fomc", "interest rate", "cpi", "pce", "inflation", "payroll", "unemployment").any(text::contains)) add("MACRO")
            if (listOf("bitcoin", "btc", "ethereum", "eth", "crypto", "token", "stablecoin", "etf").any(text::contains)) add("CRYPTO")
            if (listOf("sec", "cftc", "regulation", "lawsuit", "charged", "approval").any(text::contains)) add("LEGAL")
            if (listOf("hack", "exploit", "breach", "outage", "depeg", "insolvency").any(text::contains)) add("RISK")
            if (listOf("war", "invasion", "sanctions", "tariff", "geopolitical").any(text::contains)) add("GEOPOLITICS")
        }
        val relevance = when {
            "RISK" in topics -> "Rủi ro trực tiếp với thanh khoản/an toàn thị trường crypto"
            "MACRO" in topics -> "Có thể tác động USD, lợi suất và khẩu vị rủi ro"
            "LEGAL" in topics -> "Có thể ảnh hưởng khung pháp lý và khả năng tiếp cận thị trường"
            "GEOPOLITICS" in topics -> "Có thể làm thay đổi nhu cầu trú ẩn và khẩu vị rủi ro"
            "CRYPTO" in topics -> "Liên quan trực tiếp hệ sinh thái tài sản số"
            else -> "Tác động chưa rõ; cần đọc nội dung gốc"
        }
        val caution = when {
            impact == NewsImpact.CRITICAL -> "Tin cần xác minh nguồn gốc và phản ứng giá; không tự động suy ra LONG/SHORT."
            impact == NewsImpact.HIGH -> "Tác động tiềm năng lớn; hướng giá phụ thuộc kỳ vọng và dữ liệu thực tế."
            else -> "Không dùng riêng tiêu đề để quyết định giao dịch."
        }
        return article.copy(impact = impact, topics = topics, relevance = relevance, caution = caution)
    }
}

class NewsCache(context: Context) {
    private val prefs = context.getSharedPreferences("tony_news_cache", Context.MODE_PRIVATE)
    fun save(digest: NewsDigest) {
        val arr = org.json.JSONArray()
        digest.articles.forEach { a ->
            arr.put(org.json.JSONObject().apply {
                put("id", a.id); put("title", a.title); put("summary", a.summary)
                put("url", a.url); put("source", a.source); put("publishedAt", a.publishedAt)
                put("impact", a.impact.name); put("topics", org.json.JSONArray(a.topics))
                put("relevance", a.relevance); put("caution", a.caution)
            })
        }
        prefs.edit().putString("articles", arr.toString())
            .putLong("fetchedAt", digest.fetchedAt).putInt("sourceCount", digest.sourceCount).apply()
    }
    fun load(): NewsDigest? = try {
        val raw = prefs.getString("articles", null) ?: return null
        val arr = org.json.JSONArray(raw)
        val articles = (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            val tags = j.optJSONArray("topics")
            NewsArticle(j.getString("id"), j.getString("title"), j.optString("summary"),
                j.optString("url"), j.optString("source"), j.optLong("publishedAt"),
                runCatching { NewsImpact.valueOf(j.optString("impact", "LOW")) }.getOrDefault(NewsImpact.LOW),
                (0 until (tags?.length() ?: 0)).map { tags!!.getString(it) },
                j.optString("relevance"), j.optString("caution"))
        }
        NewsDigest(articles, prefs.getLong("fetchedAt", 0L), prefs.getInt("sourceCount", 0), emptyList())
    } catch (_: Exception) { null }
}
