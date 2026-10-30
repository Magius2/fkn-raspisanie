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
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TimerWidgetProvider::class.java))
            for (id in ids) mgr.updateAppWidget(id, render(ctx, id))
        }

        private fun render(ctx: Context, id: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.timer_widget)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.wt_root, open)

            val data = Store.loadData(ctx)
            if (data == null) {
                views.setTextViewText(R.id.wt_big, "—")
                views.setTextViewText(R.id.wt_label, "Нет данных")
                views.setTextViewText(R.id.wt_sub, "Откройте приложение")
                return views
            }

            val cal = Store.loadCal(ctx)
            val sel = Store.getSel(ctx)
            val (group, sub) = parseSel(sel)
            val week = Store.getManualWeek(ctx) ?: ScheduleLogic.autoWeek(cal)
            val now = LocalDateTime.now(ScheduleLogic.MSK)
            val nowMin = now.hour * 60 + now.minute

            if (ScheduleLogic.holidayToday(cal) != null) {
                views.setTextViewText(R.id.wt_big, "🌴")
                views.setTextViewText(R.id.wt_label, "Каникулы")
                views.setTextViewText(R.id.wt_sub, ScheduleLogic.holidayToday(cal) ?: "")
                return views
            }

            val next = ScheduleLogic.nextLesson(data, group, sub, week, cal, nowMin)
            if (next == null) {
                views.setTextViewText(R.id.wt_big, "🎉")
                views.setTextViewText(R.id.wt_label, "Пар нет")
                views.setTextViewText(R.id.wt_sub, "Отдыхайте")
                return views
            }

            if (next.daysAhead == 0 && next.startsInMin != null) {
                views.setTextViewText(R.id.wt_big, TodayWidgetProvider.formatMin(next.startsInMin))
                views.setTextViewText(R.id.wt_label, "до пары · ${next.time}")
                views.setTextViewText(R.id.wt_sub, next.lesson.subject)
            } else {
                val dayLabel = if (next.daysAhead == 1) "завтра" else TodayWidgetProvider.dayName(next.dow)
                views.setTextViewText(R.id.wt_big, dayLabel.replaceFirstChar { it.uppercase() })
                views.setTextViewText(R.id.wt_label, "${next.time} · группа ${parseSel(Store.getSel(ctx)).first}")
                views.setTextViewText(R.id.wt_sub, next.lesson.subject)
            }
            return views
        }
    }
}
