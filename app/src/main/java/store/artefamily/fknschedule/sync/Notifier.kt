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
import java.time.LocalDateTime

/**
 * Уведомления «до пары N минут» и регулярное обновление виджетов.
 *
 * Два независимых будильника (разные requestCode — иначе второй затирал бы
 * первый, у PendingIntent идентичность определяется requestCode+action):
 *   REQ_TICK      — «пульс» каждые 15 минут: виджеты, «сейчас», перепланирование;
 *   REQ_LEAD      — точное срабатывание за N минут до начала пары.
 */
object Notifier {
    const val CHANNEL_ID = "lessons"
    const val ACTION_TICK = "store.artefamily.fknschedule.ACTION_TICK"
    const val ACTION_LEAD = "store.artefamily.fknschedule.ACTION_LEAD"

    private const val REQ_TICK = 1001
    private const val REQ_LEAD = 1002
    private const val TICK_INTERVAL_MS = 15 * 60_000L

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ближайшие пары", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Напоминания о начале пар" }
        )
    }

    private fun nowMin(): Int {
        val now = LocalDateTime.now(ScheduleLogic.MSK)
        return now.hour * 60 + now.minute
    }

    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun alarm(ctx: Context, action: String, req: Int): PendingIntent {
        val intent = Intent(ctx, TickReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            ctx, req, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Пересчитать и переставить будильники. Вызывается при старте, смене группы и после тика. */
    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // «Пульс»: обновляет виджеты, даже если пользователь ничего не открывал.
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + TICK_INTERVAL_MS,
            alarm(ctx, ACTION_TICK, REQ_TICK)
        )

        // Точное напоминание — только если уведомления включены и разрешены.
        if (!Store.getNotificationsEnabled(ctx) || !canPost(ctx)) {
            am.cancel(alarm(ctx, ACTION_LEAD, REQ_LEAD))
            return
        }

        val data = Store.loadData(ctx) ?: return
        val cal = Store.loadCal(ctx)
        val (group, sub) = parseSel(Store.getSel(ctx))
        val week = Store.getManualWeek(ctx) ?: ScheduleLogic.autoWeek(cal)
        val lead = Store.getNotifMinutes(ctx).toLong()

        val next = ScheduleLogic.nextLesson(data, group, sub, week, cal, nowMin()) ?: return
        val startsIn = next.startsInMin ?: return          // напоминаем только про сегодняшние пары

        val pi = alarm(ctx, ACTION_LEAD, REQ_LEAD)
        val fireAt = System.currentTimeMillis() + (startsIn - lead).coerceAtLeast(0) * 60_000L
        // Если до пары осталось меньше lead — сработает сразу: напомнить
        // с опозданием лучше, чем промолчать (id уведомления стабилен,
        // дублей по той же паре не будет).
        //
        // Exact, когда система позволяет: в Doze неточный будильник может
        // опоздать на несколько минут — для «за 5 минут до пары» это критично.
        if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        }
    }

    /** Показать напоминание о паре, до которой осталось <= lead минут. */
    fun notifyIfSoon(ctx: Context) {
        if (!Store.getNotificationsEnabled(ctx) || !canPost(ctx)) return

        val data = Store.loadData(ctx) ?: return
        val cal = Store.loadCal(ctx)
        val (group, sub) = parseSel(Store.getSel(ctx))
        val week = Store.getManualWeek(ctx) ?: ScheduleLogic.autoWeek(cal)
        val lead = Store.getNotifMinutes(ctx).toLong()

        val next = ScheduleLogic.nextLesson(data, group, sub, week, cal, nowMin()) ?: return
        if (next.daysAhead != 0) return
        val startsIn = next.startsInMin ?: return
        if (startsIn > lead) return

        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (startsIn <= 0) "Пара начинается" else "Через $startsIn мин — ${next.lesson.subject}"
        val text = buildString {
            append(next.time)
            next.lesson.roomDisplay().takeIf { it.isNotBlank() }?.let { append(" · $it") }
            next.lesson.teacher?.let { append(" · $it") }
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Стабильный id на конкретную пару: каждая пара сигналит сама по себе —
        // напоминание о следующей паре не «затирает» молча предыдущее.
        // Повторные тики по той же паре обновляют её же уведомление.
        val noteId = (ScheduleLogic.today().toString() + next.time + next.lesson.subject).hashCode()
        nm.notify(noteId, notification)
    }
}

/**
 * Должен быть объявлен в AndroidManifest — иначе система не найдёт компонент
 * и будильник сгорит впустую (именно так «пропадали» напоминания).
 */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Notifier.ACTION_LEAD -> Notifier.notifyIfSoon(ctx)
            Notifier.ACTION_TICK -> {
                TodayWidgetProvider.updateAll(ctx)
                TimerWidgetProvider.updateAll(ctx)
                Notifier.notifyIfSoon(ctx)
            }
        }
        Notifier.scheduleNext(ctx)
    }
}

fun parseSel(sel: String): Pair<String, String?> {
    val parts = sel.split(".")
    return parts[0] to parts.getOrNull(1)
}