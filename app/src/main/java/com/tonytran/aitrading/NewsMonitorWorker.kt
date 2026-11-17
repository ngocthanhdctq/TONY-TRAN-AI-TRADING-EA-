package com.tonytran.aitrading

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Periodically checks public RSS feeds and posts local HIGH/CRITICAL news alerts. */
class NewsMonitorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val digest = NewsIntelligence(applicationContext).fetchDaily()
            if (digest.articles.isEmpty() && digest.errors.size == digest.sourceCount + digest.errors.size) {
                return Result.retry()
            }
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val known = prefs.getStringSet(KEY_SENT_IDS, emptySet()).orEmpty().toMutableSet()
            val fresh = digest.criticalOrHigh
                .filter { it.id !in known }
                .sortedByDescending { it.publishedAt }

            if (canPostNotifications()) {
                ensureChannel()
                fresh.take(8).forEach { article -> postAlert(article) }
            }

            // Record IDs even when notification permission is denied, avoiding a burst later.
            fresh.forEach { known.add(it.id) }
            val retained = known.toList().takeLast(MAX_REMEMBERED_IDS).toSet()
            prefs.edit().putStringSet(KEY_SENT_IDS, retained).putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI News Alert", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tin tức thị trường mức HIGH và CRITICAL"
                    enableVibration(true)
                }
            )
        }
    }

    private fun postAlert(article: NewsArticle) {
        val context = applicationContext
        val text = "${article.impact.name} • ${article.source}\n${article.relevance}\n${article.caution}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("AI NEWS • ${article.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val id = article.id.hashCode() and 0x7fffffff
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "tony_tran_ai_news_monitor"
        private const val PREFS = "tony_news_monitor_state"
        private const val KEY_SENT_IDS = "sent_article_ids"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val MAX_REMEMBERED_IDS = 300
        private const val CHANNEL_ID = "tony_ai_news_alerts"
    }
}
