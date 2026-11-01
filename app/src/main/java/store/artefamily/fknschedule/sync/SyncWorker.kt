package store.artefamily.fknschedule.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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

        // Если хоть один файл не скачался — просим повтор (с backoff):
        // иначе следующий шанс был бы только через 6 часов.
        return if (okData && okCal) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "sync",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                    // Без сети не будим устройство зря.
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()
            )
        }
    }
}

private const val TAG = "Sync"
private const val MAX_JSON_BYTES = 5L * 1024 * 1024

/**
 * Скачивает JSON и сохраняет его ТОЛЬКО если ответ корректен.
 *
 * Раньше сюда попадал любой 2xx: страница captive-портала или `{}`
 * затирали рабочий офлайн-кэш. Теперь валидация происходит до записи
 * (см. [Store.saveData]/[Store.saveCal]) — битый ответ оставляет
 * последний хороший файл нетронутым.
 */
fun fetchAndSave(url: String, save: (String) -> Unit): Boolean {
    return try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode}: $url")
                return false
            }
            // Защита от гигантского ответа (скомпрометированный сервер):
            // не читаем его целиком в память.
            if (conn.contentLengthLong > MAX_JSON_BYTES) {
                Log.w(TAG, "ответ слишком большой, пропускаем: $url")
                return false
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (text.length > MAX_JSON_BYTES) return false
            save(text) // невалидный JSON бросит исключение — кэш не тронут
            true
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "не удалось скачать $url", e)
        false
    }
}
