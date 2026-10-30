package store.artefamily.fknschedule.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import store.artefamily.fknschedule.widget.TodayWidgetProvider
import store.artefamily.fknschedule.widget.TimerWidgetProvider
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Фоновая синхронизация: раз в 6 часов тянет data.json/cal.json,
 * обновляет виджеты и планирует уведомления о следующей паре.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val okData = fetchAndSave("${Store.BASE_URL}/data.json") { Store.saveData(ctx, it) }
        val okCal = fetchAndSave("${Store.BASE_URL}/cal.json") { Store.saveCal(ctx, it) }

        TodayWidgetProvider.updateAll(ctx)
        TimerWidgetProvider.updateAll(ctx)
        Notifier.scheduleNext(ctx)

        return if (okData) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "sync",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build()
            )
        }
    }
}

fun fetchAndSave(url: String, save: (String) -> Unit): Boolean {
    return try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) return false
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            save(text)
            true
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        false
    }
}
