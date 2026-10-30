package store.artefamily.fknschedule.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import store.artefamily.fknschedule.sync.Notifier
import store.artefamily.fknschedule.sync.SyncWorker
import store.artefamily.fknschedule.widget.TodayWidgetProvider
import store.artefamily.fknschedule.widget.TimerWidgetProvider

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val ctx = context.applicationContext
        SyncWorker.enqueue(ctx)
        TodayWidgetProvider.updateAll(ctx)
        TimerWidgetProvider.updateAll(ctx)
        Notifier.scheduleNext(ctx)
    }
}
