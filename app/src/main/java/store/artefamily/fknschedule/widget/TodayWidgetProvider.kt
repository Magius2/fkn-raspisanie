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
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Виджет «Сегодня»: 2 ближайшие пары (или первая пара завтрашнего дня).
 * Тап открывает приложение.
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(ctx)
    }

    companion object {
        /**
         * requestCode виджета: у PendingIntent идентичность = requestCode+intent.
         * У обоих виджетов intent одинаковый — с requestCode 0 они делили
         * один PendingIntent (безвредно, но хрупко), теперь у каждого свой.
         */
        private const val REQ_OPEN = 2001
        private const val STALE_MS = 24 * 3600_000L

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TodayWidgetProvider::class.java))
            for (id in ids) mgr.updateAppWidget(id, render(ctx))
        }

        private fun render(ctx: Context): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.today_widget)
            views.setOnClickPendingIntent(
                R.id.w_root,
                PendingIntent.getActivity(
                    ctx, REQ_OPEN, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val data = Store.loadData(ctx)
            if (data == null) {
                views.setTextViewText(R.id.w_title, ctx.getString(R.string.app_name))
                views.setTextViewText(R.id.w_line1, ctx.getString(R.string.widget_need_data))
                clearLesson(views)
                return views
            }

            // Группа ещё не выбрана — молчаливый дефолт "1" не показываем.
            if (!Store.getSelChosen(ctx)) {
                views.setTextViewText(R.id.w_title, ctx.getString(R.string.app_name))
                views.setTextViewText(R.id.w_line1, ctx.getString(R.string.widget_pick_group))
                clearLesson(views)
                return views
            }

            val cal = Store.loadCal(ctx)
            val sel = Store.getSel(ctx)
            val (group, sub) = parseSel(sel)
            val week = Store.getManualWeek(ctx) ?: ScheduleLogic.autoWeek(cal)
            val now = LocalDateTime.now(ScheduleLogic.MSK)
            val nowMin = now.hour * 60 + now.minute

            val holiday = ScheduleLogic.holidayToday(cal)
            views.setTextViewText(
                R.id.w_title,
                (if (holiday != null) holiday
                else ctx.getString(R.string.widget_group_week, group, ScheduleLogic.weekName(week).lowercase()))
                        + staleSuffix(ctx)
            )

            if (holiday != null) {
                views.setTextViewText(R.id.w_line1, ctx.getString(R.string.widget_holiday_no_lessons))
                views.setTextViewText(R.id.w_line2, "")
                views.setTextViewText(R.id.w_time1, "")
                views.setTextViewText(R.id.w_time2, "")
                return views
            }

            val next = ScheduleLogic.nextLesson(data, group, sub, week, cal, nowMin)
            if (next == null) {
                views.setTextViewText(R.id.w_line1, ctx.getString(R.string.widget_no_lessons))
                views.setTextViewText(R.id.w_line2, "")
                views.setTextViewText(R.id.w_time1, "")
                views.setTextViewText(R.id.w_time2, "")
                return views
            }

            val dayLabel = when (next.daysAhead) {
                0 -> ctx.getString(R.string.widget_today)
                1 -> ctx.getString(R.string.widget_tomorrow)
                else -> dayName(next.dow)
            }

            views.setTextViewText(R.id.w_time1, next.time)
            views.setTextViewText(R.id.w_line1, "$dayLabel · ${next.lesson.subject}")
            views.setTextViewText(
                R.id.w_time2,
                if (next.startsInMin != null && next.daysAhead == 0)
                    ctx.getString(R.string.widget_in, formatMin(next.startsInMin)) else ""
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

        private fun clearLesson(views: RemoteViews) {
            views.setTextViewText(R.id.w_line2, "")
            views.setTextViewText(R.id.w_time1, "")
            views.setTextViewText(R.id.w_time2, "")
        }

        /** «данные от …» к заголовку, если кэшу больше суток — намёк открыть приложение. */
        fun staleSuffix(ctx: Context): String {
            val t = Store.lastSync(ctx)
            if (t == 0L || System.currentTimeMillis() - t < STALE_MS) return ""
            return " · " + ctx.getString(R.string.widget_data_from, formatSyncAge(t))
        }

        /** «14:32» для сегодняшних данных, «28 окт» для более старых. */
        fun formatSyncAge(epochMs: Long): String {
            val dt = Instant.ofEpochMilli(epochMs).atZone(ScheduleLogic.MSK).toLocalDateTime()
            return if (dt.toLocalDate() == ScheduleLogic.today())
                dt.format(DateTimeFormatter.ofPattern("HH:mm"))
            else dt.format(DateTimeFormatter.ofPattern("d MMM"))
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
