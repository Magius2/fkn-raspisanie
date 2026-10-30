package store.artefamily.fknschedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import store.artefamily.fknschedule.MainActivity
import store.artefamily.fknschedule.R
import store.artefamily.fknschedule.logic.ScheduleLogic
import store.artefamily.fknschedule.sync.Store
import store.artefamily.fknschedule.sync.parseSel
import java.time.LocalDateTime

/**
 * Виджет «Сегодня»: 2 ближайшие пары (или первая пара завтрашнего дня).
 * Тап открывает приложение.
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(ctx)
    }

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TodayWidgetProvider::class.java))
            for (id in ids) mgr.updateAppWidget(id, render(ctx, id))
        }

        private fun render(ctx: Context, id: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.today_widget)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.w_root, open)

            val data = Store.loadData(ctx)
            if (data == null) {
                views.setTextViewText(R.id.w_title, "Расписание")
                views.setTextViewText(R.id.w_line1, "Загрузите данные в приложении")
                views.setTextViewText(R.id.w_line2, "")
                views.setTextViewText(R.id.w_time1, "")
                views.setTextViewText(R.id.w_time2, "")
                return views
            }

            val cal = Store.loadCal(ctx)
            val sel = Store.getSel(ctx)
            val (group, sub) = parseSel(sel)
            val week = Store.getManualWeek(ctx) ?: ScheduleLogic.autoWeek(cal)
            val now = LocalDateTime.now(ScheduleLogic.MSK)
            val nowMin = now.hour * 60 + now.minute
            val todayDow = ScheduleLogic.today().dayOfWeek.value

            val holiday = ScheduleLogic.holidayToday(cal)
            views.setTextViewText(
                R.id.w_title,
                if (holiday != null) "$holiday" else "Группа $group · ${ScheduleLogic.weekName(week).lowercase()}"
            )

            if (holiday != null) {
                views.setTextViewText(R.id.w_line1, "🌴 Занятий нет")
                views.setTextViewText(R.id.w_line2, "")
                views.setTextViewText(R.id.w_time1, "")
                views.setTextViewText(R.id.w_time2, "")
                return views
            }

            val next = ScheduleLogic.nextLesson(data, group, sub, week, cal, nowMin)
            if (next == null) {
                views.setTextViewText(R.id.w_line1, "Пар больше нет 🎉")
                views.setTextViewText(R.id.w_line2, "")
                views.setTextViewText(R.id.w_time1, "")
                views.setTextViewText(R.id.w_time2, "")
                return views
            }

            val dayLabel = when (next.daysAhead) {
                0 -> "Сегодня"
                1 -> "Завтра"
                else -> dayName(next.dow)
            }

            views.setTextViewText(R.id.w_time1, next.time)
            views.setTextViewText(
                R.id.w_line1,
                "$dayLabel · ${next.lesson.subject}"
            )
            views.setTextViewText(
                R.id.w_time2,
                if (next.startsInMin != null && next.daysAhead == 0) "через ${formatMin(next.startsInMin)}" else ""
            )
            views.setTextViewText(
                R.id.w_line2,
                listOfNotNull(
                    next.lesson.roomDisplay().takeIf { it.isNotBlank() },
                    next.lesson.teacher
                ).joinToString(" · ")
            )
            return views
        }

        fun formatMin(m: Long): String = when {
            m < 60 -> "$m мин"
            m % 60 == 0L -> "${m / 60} ч"
            else -> "${m / 60} ч ${m % 60} мин"
        }

        fun dayName(dow: Int): String = when (dow) {
            1 -> "Пн"; 2 -> "Вт"; 3 -> "Ср"; 4 -> "Чт"; 5 -> "Пт"; 6 -> "Сб"; else -> "Вс"
        }
    }
}
