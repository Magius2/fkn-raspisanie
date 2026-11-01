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
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.notif_channel_desc) }
        )
    }

    private fun nowMin(): Int {
        val now = LocalDateTime.now(ScheduleLogic.MSK)
        return now.hour * 60 + now.minute
    }

    /** Можно ли постить уведомления (разрешение на API 33+). Используется и настройками. */
    fun canPost(ctx: Context): Boolean =
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

        val leadPi = alarm(ctx, ACTION_LEAD, REQ_LEAD)

        // Точное напоминание — только если уведомления включены, разрешены
        // и группа ВЫБРАНА: молчаливый дефолт "1" не должен будить телефон
        // в свежей установке, где пользователь ещё ничего не выбирал.
        if (!Store.getNotificationsEnabled(ctx) || !canPost(ctx) || !Store.getSelChosen(ctx)) {
            am.cancel(leadPi)
            return
        }

        val data = Store.loadData(ctx) ?: return
        val cal = Store.loadCal(ctx)
        val (group, sub) = parseSel(Store.getSel(ctx))
        val week = Store.getManualWeek(ctx) ?: ScheduleLogic.autoWeek(cal)
        val lead = Store.getNotifMinutes(ctx).toLong()

        val next = ScheduleLogic.nextLesson(data, group, sub, week, cal, nowMin()) ?: return
        val startsIn = next.startsInMin ?: return          // напоминаем только про сегодняшние пары

        // Пара уже внутри окна напоминания: будильник «на сейчас» создал бы
        // цикл «сработал → перепланировался на сейчас» — RTC_WAKEUP крутился бы
        // весь lead-период. Просто напоминаем сейчас, тик перепроверит.
        val delayMin = startsIn - lead
        if (delayMin <= 0) {
            notifyIfSoon(ctx)
            return
        }

        val fireAt = System.currentTimeMillis() + delayMin * 60_000L
        // Exact всегда, когда система позволяет: до API 31 разрешение не нужно,
        // там точные будильники «просто работают» (в Doze неточные опаздывают).
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, leadPi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, leadPi)
        }
    }

    /** Показать напоминание о паре, до которой осталось <= lead минут. */
    fun notifyIfSoon(ctx: Context) {
        if (!Store.getNotificationsEnabled(ctx) || !canPost(ctx) || !Store.getSelChosen(ctx)) return

        val data = Store.loadData(ctx) ?: return
        val cal = Store.loadCal(ctx)
        val (group, sub) = parseSel(Store.getSel(ctx))
        val week = Store.getManualWeek(ctx) ?: ScheduleLogic.autoWeek(cal)
        val lead = Store.getNotifMinutes(ctx).toLong()

        val next = ScheduleLogic.nextLesson(data, group, sub, week, cal, nowMin())
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val inWindow = next != null && next.daysAhead == 0 &&
                next.startsInMin != null && next.startsInMin <= lead

        if (!inWindow) {
            // Окно напоминания прошло (пара началась) — снимаем прошлое
            // уведомление: иначе «Через 5 мин» висит в шторке вчерашним,
            // setAutoCancel срабатывает только по тапу.
            val old = Store.takeLastNoteId(ctx)
            if (old != 0) nm.cancel(old)
            return
        }
        val nextLesson = next ?: return
        val startsIn = nextLesson.startsInMin ?: return

        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Тема пары всегда в заголовке: «Пара начинается» без названия
        // не давало понять, какая именно пара началась.
        val title = if (startsIn <= 0) ctx.getString(R.string.notif_starting, nextLesson.lesson.subject)
        else ctx.getString(R.string.notif_in_minutes, startsIn, nextLesson.lesson.subject)
        val text = listOfNotNull(
            nextLesson.time,
            nextLesson.lesson.roomDisplay().takeIf { it.isNotBlank() },
            nextLesson.lesson.teacher
        ).joinToString(" · ")
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        // Стабильный id на конкретную пару: каждая пара сигналит сама по себе —
        // напоминание о следующей паре не «затирает» молча предыдущее.
        // Повторные тики по той же паре обновляют её же уведомление.
        val noteId = (ScheduleLogic.today().toString() + nextLesson.time + nextLesson.lesson.subject).hashCode()
        // Прошлое напоминание (другая пара) снимаем: та пара уже началась.
        val old = Store.takeLastNoteId(ctx)
        if (old != 0 && old != noteId) nm.cancel(old)
        Store.setLastNoteId(ctx, noteId)
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
        // goAsync + фоновый поток: чтение/парсинг data.json не на главном,
        // будильники переставляются даже при исключении в обработке тика
        // (иначе «пульс» умирал бы до следующего ребута).
        val pending = goAsync()
        Thread {
            try {
                runCatching {
                    when (intent.action) {
                        Notifier.ACTION_LEAD -> {
                            Notifier.notifyIfSoon(ctx)
                            // Виджет «через 15 мин» должен обновиться сразу,
                            // а не ждать следующего тика.
                            TodayWidgetProvider.updateAll(ctx)
                            TimerWidgetProvider.updateAll(ctx)
                        }
                        Notifier.ACTION_TICK -> {
                            TodayWidgetProvider.updateAll(ctx)
                            TimerWidgetProvider.updateAll(ctx)
                            Notifier.notifyIfSoon(ctx)
                        }
                    }
                }
                // Будильники переставляем даже при исключении в обработке тика
                // (иначе «пульс» умирал бы до следующего ребута).
                // runCatching и здесь: необработанное исключение в этом
                // потоке роняет процесс (например, SecurityException от
                // setExactAndAllowWhileIdle при отозванном разрешении).
                runCatching { Notifier.scheduleNext(ctx) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

fun parseSel(sel: String): Pair<String, String?> {
    val parts = sel.split(".")
    return parts[0] to parts.getOrNull(1)
}
