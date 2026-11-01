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
 * Виджет-таймер: крупный отсчёт до начала следующей пары.
 */
class TimerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(ctx)
    }

    companion object {
        /** См. комментарий в TodayWidgetProvider: у каждого виджета свой requestCode. */
        private const val REQ_OPEN = 2002

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TimerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = render(ctx) // один раз на все инстансы
            for (id in ids) mgr.updateAppWidget(id, views)
        }

        private fun render(ctx: Context): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.timer_widget)
            views.setOnClickPendingIntent(
                R.id.wt_root,
                PendingIntent.getActivity(
                    ctx, REQ_OPEN, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val data = Store.loadData(ctx)
            if (data == null) {
                views.setTextViewText(R.id.wt_big, "—")
                views.setTextViewText(R.id.wt_label, ctx.getString(R.string.timer_no_data))
                views.setTextViewText(R.id.wt_sub, ctx.getString(R.string.timer_open_app))
                return views
            }

            // Группа ещё не выбрана — дефолт "1" молча не подставляем.
            if (!Store.getSelChosen(ctx)) {
                views.setTextViewText(R.id.wt_big, "—")
                views.setTextViewText(R.id.wt_label, ctx.getString(R.string.widget_pick_group))
                views.setTextViewText(R.id.wt_sub, "")
                return views
            }

            val cal = Store.loadCal(ctx)
            val (group, sub) = parseSel(Store.getSel(ctx)) // sel парсим один раз
            val week = Store.getManualWeek(ctx) ?: ScheduleLogic.autoWeek(cal)
            val now = LocalDateTime.now(ScheduleLogic.MSK)
            val nowMin = now.hour * 60 + now.minute
            val stale = TodayWidgetProvider.staleSuffix(ctx)

            val holiday = ScheduleLogic.holidayToday(cal)
            if (holiday != null) {
                views.setTextViewText(R.id.wt_big, "🌴")
                views.setTextViewText(R.id.wt_label, ctx.getString(R.string.timer_holiday))
                views.setTextViewText(R.id.wt_sub, holiday)
                return views
            }

            val next = ScheduleLogic.nextLesson(data, group, sub, week, cal, nowMin)
            if (next == null) {
                views.setTextViewText(R.id.wt_big, "🎉")
                views.setTextViewText(R.id.wt_label, ctx.getString(R.string.timer_no_lessons))
                views.setTextViewText(R.id.wt_sub, ctx.getString(R.string.timer_rest))
                return views
            }

            if (next.daysAhead == 0 && next.startsInMin != null) {
                views.setTextViewText(R.id.wt_big, TodayWidgetProvider.formatMin(next.startsInMin))
                views.setTextViewText(R.id.wt_label, ctx.getString(R.string.timer_until_lesson, next.time) + stale)
                views.setTextViewText(R.id.wt_sub, next.lesson.subject)
            } else {
                val dayLabel = if (next.daysAhead == 1) ctx.getString(R.string.widget_tomorrow).lowercase()
                else TodayWidgetProvider.dayName(next.dow)
                views.setTextViewText(R.id.wt_big, dayLabel.replaceFirstChar { it.uppercase() })
                views.setTextViewText(R.id.wt_label, ctx.getString(R.string.timer_day_group, next.time, group) + stale)
                views.setTextViewText(R.id.wt_sub, next.lesson.subject)
            }
            return views
        }
    }
}
