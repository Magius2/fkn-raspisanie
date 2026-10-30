package store.artefamily.fknschedule.sync

import android.content.Context
import store.artefamily.fknschedule.model.CalData
import store.artefamily.fknschedule.model.ScheduleData
import java.io.File

/**
 * Локальное хранилище: JSON-файлы в filesDir + SharedPreferences для выбора.
 * Данные тянутся с raspisanie.artefamily.online, офлайн — последняя копия.
 */
object Store {
    private const val PREFS = "prefs"
    private const val KEY_DATA = "data.json"
    private const val KEY_CAL = "cal.json"
    private const val SEL = "sel"                 // "8" или "8.2"
    private const val WEEK = "week"               // ручной выбор
    private const val WEEK_ISO = "week.iso"
    const val BASE_URL = "https://raspisanie.artefamily.online"

    fun dataFile(ctx: Context) = File(ctx.filesDir, KEY_DATA)
    fun calFile(ctx: Context) = File(ctx.filesDir, KEY_CAL)

    fun saveData(ctx: Context, json: String) = dataFile(ctx).writeText(json)
    fun saveCal(ctx: Context, json: String) = calFile(ctx).writeText(json)

    fun loadData(ctx: Context): ScheduleData? = try {
        if (dataFile(ctx).exists()) ScheduleData.fromJson(dataFile(ctx).readText()) else null
    } catch (e: Exception) { null }

    fun loadCal(ctx: Context): CalData? = try {
        if (calFile(ctx).exists()) CalData.fromJson(calFile(ctx).readText()) else null
    } catch (e: Exception) { null }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getSel(ctx: Context): String = prefs(ctx).getString(SEL, "1") ?: "1"
    fun setSel(ctx: Context, v: String) = prefs(ctx).edit().putString(SEL, v).apply()

    fun getManualWeek(ctx: Context): String? {
        val w = prefs(ctx).getString(WEEK, null) ?: return null
        val iso = prefs(ctx).getString(WEEK_ISO, null) ?: return null
        // Ручной выбор действителен только на текущей неделе
        if (iso != ScheduleLogicHelper.currentIso().toString()) {
            clearManualWeek(ctx)
            return null
        }
        return w
    }

    fun setManualWeek(ctx: Context, w: String) = prefs(ctx).edit()
        .putString(WEEK, w)
        .putString(WEEK_ISO, ScheduleLogicHelper.currentIso().toString())
        .apply()

    fun clearManualWeek(ctx: Context) = prefs(ctx).edit()
        .remove(WEEK).remove(WEEK_ISO).apply()

    fun getNotificationsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("notif", true)
    fun setNotificationsEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("notif", v).apply()

    fun getNotifMinutes(ctx: Context): Int = prefs(ctx).getInt("notif_min", 15)
    fun setNotifMinutes(ctx: Context, v: Int) = prefs(ctx).edit().putInt("notif_min", v).apply()
}

private object ScheduleLogicHelper {
    fun currentIso(): Int =
        java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow"))
            .get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
}
