package store.artefamily.fknschedule.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import store.artefamily.fknschedule.sync.Notifier
import store.artefamily.fknschedule.sync.SyncWorker
import store.artefamily.fknschedule.widget.TodayWidgetProvider
import store.artefamily.fknschedule.widget.TimerWidgetProvider

/**
 * Будильники живут в памяти системы и «умирают» при выключении устройства,
 * обновлении приложения и переводе часов — во всех этих случаях переставляем.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val ctx = context.applicationContext
        SyncWorker.enqueue(ctx)
        TodayWidgetProvider.updateAll(ctx)
        TimerWidgetProvider.updateAll(ctx)
        Notifier.scheduleNext(ctx)
    }

    companion object {
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,   // приложение обновилось — будильники слетели
            Intent.ACTION_TIME_CHANGED,          // пользователь перевёл часы
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
