package store.artefamily.fknschedule.sync

import android.content.Context
import store.artefamily.fknschedule.logic.ScheduleLogic
import store.artefamily.fknschedule.model.CalData
import store.artefamily.fknschedule.model.ScheduleData
import java.io.File

/**
 * Локальное хранилище: JSON-файлы в filesDir + SharedPreferences для выбора.
 * Данные тянутся с raspisanie.artefamily.online, офлайн — последняя копия.
 */
object Store {
    private const val PREFS = "prefs"
    private const val FILE_DATA = "data.json"
    private const val FILE_CAL = "cal.json"
    private const val SEL = "sel"                 // "8" или "8.2"
    private const val SEL_CHOSEN = "sel.chosen"   // пользователь сам выбрал группу (не дефолт "1")
    private const val LAST_SYNC = "last_sync"     // epoch ms последней успешной загрузки расписания
    private const val LAST_NOTE_ID = "last_note"  // id последнего показанного напоминания
    private const val WEEK = "week"               // ручной выбор
    private const val WEEK_ISO = "week.iso"
    const val BASE_URL = "https://raspisanie.artefamily.online"

    fun dataFile(ctx: Context) = File(ctx.filesDir, FILE_DATA)
    fun calFile(ctx: Context) = File(ctx.filesDir, FILE_CAL)

    /**
     * Сохраняет расписание. Сначала проверяем, что JSON разбирается,
     * потом пишем атомарно: плохой ответ сервера не может затереть
     * последний рабочий офлайн-кэш.
     */
    fun saveData(ctx: Context, json: String) {
        ScheduleData.fromJson(json) // невалидный JSON бросит исключение ДО записи
        atomicWrite(dataFile(ctx), json)
        prefs(ctx).edit().putLong(LAST_SYNC, System.currentTimeMillis()).apply()
    }

    fun saveCal(ctx: Context, json: String) {
        CalData.fromJson(json)
        atomicWrite(calFile(ctx), json)
    }

    /**
     * Атомарная запись (tmp + rename): читатели — приложение, виджеты,
     * тик-ресивер, WorkManager — видят либо старый файл целиком, либо
     * новый целиком, но никогда наполовину записанный.
     */
    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                // rename поверх существующего файла срабатывает не везде —
                // тогда классическая пара delete + rename.
                target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            }
        } finally {
            tmp.delete()
        }
    }

    fun loadData(ctx: Context): ScheduleData? = try {
        if (dataFile(ctx).exists()) ScheduleData.fromJson(dataFile(ctx).readText()) else null
    } catch (e: Exception) { null }

    fun loadCal(ctx: Context): CalData? = try {
        if (calFile(ctx).exists()) CalData.fromJson(calFile(ctx).readText()) else null
    } catch (e: Exception) { null }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getSel(ctx: Context): String = prefs(ctx).getString(SEL, "1") ?: "1"
    fun setSel(ctx: Context, v: String) = prefs(ctx).edit().putString(SEL, v).apply()

    /**
     * Пользователь сам выбирал группу? Дефолт "1" не должен молча попадать
     * в виджеты и уведомления — до выбора они просят выбрать группу.
     */
    fun getSelChosen(ctx: Context): Boolean = prefs(ctx).getBoolean(SEL_CHOSEN, false)
    fun setSelChosen(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(SEL_CHOSEN, v).apply()

    /** Время последней успешной загрузки расписания (epoch ms, 0 — никогда). */
    fun lastSync(ctx: Context): Long = prefs(ctx).getLong(LAST_SYNC, 0L)

    fun getManualWeek(ctx: Context): String? {
        val w = prefs(ctx).getString(WEEK, null) ?: return null
        val iso = prefs(ctx).getString(WEEK_ISO, null) ?: return null
        // Ручной выбор действителен только на текущей неделе
        if (iso != ScheduleLogic.isoWeek(ScheduleLogic.today()).toString()) {
            clearManualWeek(ctx)
            return null
        }
        return w
    }

    fun setManualWeek(ctx: Context, w: String) = prefs(ctx).edit()
        .putString(WEEK, w)
        .putString(WEEK_ISO, ScheduleLogic.isoWeek(ScheduleLogic.today()).toString())
        .apply()

    fun clearManualWeek(ctx: Context) = prefs(ctx).edit()
        .remove(WEEK).remove(WEEK_ISO).apply()

    fun getNotificationsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("notif", true)
    fun setNotificationsEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("notif", v).apply()

    fun getNotifMinutes(ctx: Context): Int = prefs(ctx).getInt("notif_min", 15)
    fun setNotifMinutes(ctx: Context, v: Int) = prefs(ctx).edit().putInt("notif_min", v).apply()

    /** id последнего показанного напоминания — чтобы снять его, когда окно прошло. */
    fun setLastNoteId(ctx: Context, id: Int) = prefs(ctx).edit().putInt(LAST_NOTE_ID, id).apply()

    /** Достать и забыть id прошлого напоминания (0 — не было). */
    fun takeLastNoteId(ctx: Context): Int {
        val v = prefs(ctx).getInt(LAST_NOTE_ID, 0)
        if (v != 0) prefs(ctx).edit().remove(LAST_NOTE_ID).apply()
        return v
    }
}
