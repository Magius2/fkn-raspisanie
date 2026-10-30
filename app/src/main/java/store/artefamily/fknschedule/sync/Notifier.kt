package store.artefamily.fknschedule.sync

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import store.artefamily.fknschedule.MainActivity
import store.artefamily.fknschedule.R
import store.artefamily.fknschedule.logic.ScheduleLogic
import store.artefamily.fknschedule.widget.TodayWidgetProvider
import store.artefamily.fknschedule.widget.TimerWidgetProvider
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Push-уведомления «до пары N минут» + обновление виджетов в фоне.
 * Планируется через AlarmManager (точное время начала пары − N минут).
 */
object Notifier {
    const val CHANNEL_ID = "lessons"
    const val ACTION_TICK = "store.artefamily.fknschedule.ACTION_TICK"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ближайшие пары", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Напоминания о начале пар" })
        }
    }

    private fun nowMin(): Int {
        val now = LocalDateTime.now(ScheduleLogic.MSK)
        return now.hour * 60 + now.minute
    }

    fun scheduleNext(ctx: Context) {
        if (!Store.getNotificationsEnabled(ctx)) return
        val data = Store.loadData(ctx) ?: return
        val cal = Store.loadCal(ctx)
        val sel = Store.getSel(ctx)
        val (group, sub) = parseSel(sel)
        val week = Store.getManualWeek(ctx) ?: ScheduleLogic.autoWeek(cal)

        val next = ScheduleLogic.nextLesson(data, group, sub, week, cal, nowMin()) ?: return
        val minutes = Store.getNotifMinutes(ctx)

        val whenMs = if (next.daysAhead == 0 && next.startsInMin != null) {
            val startIn = next.startsInMin
            System.currentTimeMillis() + (startIn - minutes) * 60_000L
        } else null

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, TickReceiver::class.java).setAction(ACTION_TICK)
        val pi = PendingIntent.getBroadcast(ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Тик каждые 30 минут: обновляет виджеты и следит за следующей парой
        val tick = System.currentTimeMillis() + 30 * 60_000L
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, tick, pi)

        if (whenMs != null) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        }
    }

    private fun parseTime(s: String): Int {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(s) ?: return 0
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_TICK) return
        val ctx = context.applicationContext
        TodayWidgetProvider.updateAll(ctx)
        TimerWidgetProvider.updateAll(ctx)

        // Уведомление, если до пары <= N минут
        if (Store.getNotificationsEnabled(ctx) &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33) {
            val data = Store.loadData(ctx) ?: return
            val cal = Store.loadCal(ctx)
            val sel = Store.getSel(ctx)
            val (group, sub) = parseSel(sel)
            val week = Store.getManualWeek(ctx) ?: ScheduleLogic.autoWeek(cal)
            val now = LocalDateTime.now(ScheduleLogic.MSK)
            val nowMin = now.hour * 60 + now.minute
            val minutes = Store.getNotifMinutes(ctx)

            val next = ScheduleLogic.nextLesson(data, group, sub, week, cal, nowMin) ?: return
            if (next.daysAhead == 0 && next.startsInMin != null && next.startsInMin <= minutes) {
                Notifier.ensureChannel(ctx)
                val open = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val title = if (next.startsInMin == 0L) "Пара начинается!"
                else "Через ${next.startsInMin} мин — ${next.lesson.subject}"
                val text = buildString {
                    append(next.time)
                    next.lesson.roomDisplay().takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    next.lesson.teacher?.let { append(" · $it") }
                }
                val n = NotificationCompat.Builder(ctx, Notifier.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(next.dow * 1000 + (next.startsInMin?.toInt() ?: 0), n)
            }
        }
        Notifier.scheduleNext(ctx)
    }
}

fun parseSel(sel: String): Pair<String, String?> {
    val parts = sel.split(".")
    return parts[0] to parts.getOrNull(1)
}
