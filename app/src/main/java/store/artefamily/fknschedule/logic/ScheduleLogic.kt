package store.artefamily.fknschedule.logic

import store.artefamily.fknschedule.model.CalData
import store.artefamily.fknschedule.model.Lesson
import store.artefamily.fknschedule.model.ScheduleData
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Логика недель числитель/знаменатель — зеркалит app.js сайта:
 * чётные ISO-недели = числитель, каникулы не отменяют тип недели,
 * воскресенье вечером показываем уже следующую неделю.
 */
object ScheduleLogic {

    val MSK: ZoneId = ZoneId.of("Europe/Moscow")

    fun today(): LocalDate = LocalDate.now(MSK)

    fun isoWeek(date: LocalDate): Int =
        date.get(WeekFields.ISO.weekOfWeekBasedYear())

    fun mondayOf(date: LocalDate): LocalDate =
        date.with(DayOfWeek.MONDAY)

    /** Что действует в дату: 'ch' | 'zn' | 'holiday'. */
    fun resolveDay(date: LocalDate, cal: CalData?): Resolved {
        if (cal != null) {
            cal.days[date.toString()]?.let { return Resolved(it, "день") }
            cal.holidays.firstOrNull { date.toString() in it.from..it.to }
                ?.let { return Resolved("holiday", it.note ?: "каникулы") }
            cal.weeks[isoWeek(date)]?.let { return Resolved(it, "неделя ${isoWeek(date)}") }
        }
        return Resolved(if (isoWeek(date) % 2 == 0) "ch" else "zn", "авто")
    }

    data class Resolved(val kind: String, val src: String)

    /** Автоматический тип недели для сегодня (каникулы -> чётность ISO). */
    fun autoWeek(cal: CalData?): String {
        val r = resolveDay(today(), cal)
        return if (r.kind == "holiday") {
            if (isoWeek(today()) % 2 == 0) "ch" else "zn"
        } else r.kind
    }

    fun holidayToday(cal: CalData?): String? {
        val r = resolveDay(today(), cal)
        return if (r.kind == "holiday") r.src else null
    }

    fun weekName(w: String): String = when (w) {
        "ch" -> "Числитель"
        "zn" -> "Знаменатель"
        else -> ""
    }

    fun weekNameAccusative(w: String): String = when (w) {
        "ch" -> "числитель"
        "zn" -> "знаменатель"
        else -> ""
    }

    /**
     * Пары группы на день с учётом типа недели и подгруппы.
     * sub == null -> обе подгруппы.
     */
    fun lessonsFor(data: ScheduleData, group: String, sub: String?, week: String, dow: Int): List<Pair<Slot_, Lesson>> {
        val day = data.days.firstOrNull { it.dow == dow } ?: return emptyList()
        val result = mutableListOf<Pair<Slot_, Lesson>>()
        for (slot in day.slots) {
            for (l in slot.lessons) {
                if (!l.groups.contains(group)) continue
                if (l.week != "all" && l.week != week) continue
                if (sub != null && l.sub != null && l.sub != sub) continue
                result.add(Slot_(slot.time, slot.fromMin, slot.toMin) to l)
            }
        }
        return result
    }

    data class Slot_(val time: String, val fromMin: Int?, val toMin: Int?)

    /** «Окна» — пропуски в целый слот между парами дня. */
    fun windowsIn(data: ScheduleData, group: String, sub: String?, week: String, dow: Int): List<Pair<IntRange, String>> {
        val day = data.days.firstOrNull { it.dow == dow } ?: return emptyList()
        val result = mutableListOf<Pair<IntRange, String>>()
        var prevEnd: Int? = null
        for (slot in day.slots) {
            val has = slot.lessons.any { l ->
                l.groups.contains(group) &&
                        (l.week == "all" || l.week == week) &&
                        (sub == null || l.sub == null || l.sub == sub)
            }
            if (has && prevEnd != null && slot.fromMin != null) {
                val from = slot.fromMin!!
                if (from - prevEnd >= 90) {
                    result.add((prevEnd + 1)..(from - 1) to "окно")
                }
            }
            if (has && slot.toMin != null) prevEnd = slot.toMin
        }
        return result
    }

    /**
     * Следующая пара: сегодня (если ещё не поздно) или в ближайшие дни.
     * Возвращает null, если на этой неделе пар больше нет.
     */
    data class NextLesson(
        val lesson: Lesson,
        val time: String,
        val daysAhead: Int,          // 0 = сегодня
        val startsInMin: Long?,      // null = не сегодня
        val dow: Int
    )

    fun nextLesson(data: ScheduleData, group: String, sub: String?, week: String, cal: CalData?, nowMin: Int): NextLesson? {
        val today = today()
        val todayDow = today.dayOfWeek.value

        // Сегодня
        val todayList = lessonsFor(data, group, sub, week, todayDow)
        for ((slot, l) in todayList) {
            val from = slot.fromMin
            if (from != null && nowMin < from) {
                return NextLesson(l, slot.time, 0, (from - nowMin).toLong(), todayDow)
            }
        }

        // Ближайшие дни до конца «недели» (до воскресенья)
        for (offset in 1..(7 - todayDow)) {
            val date = today.plusDays(offset.toLong())
            // Воскресенье вечером расписание уже следующей недели
            val wk = if (offset == 7 - todayDow && todayDow == 0) autoWeek(cal) else week
            if (date.dayOfWeek.value == 7) continue
            val list = lessonsFor(data, group, sub, wk, date.dayOfWeek.value)
            if (list.isNotEmpty()) {
                val (slot, l) = list.first()
                return NextLesson(l, slot.time, offset, null, date.dayOfWeek.value)
            }
        }

        // Если и на следующей неделе пусто — берём её первым днём с парами
        val nextWeek = if (week == "ch") "zn" else "ch"
        for (dow in 1..6) {
            val list = lessonsFor(data, group, sub, nextWeek, dow)
            if (list.isNotEmpty()) {
                val (slot, l) = list.first()
                return NextLesson(l, slot.time, 7 - todayDow + dow, null, dow)
            }
        }
        return null
    }
}
