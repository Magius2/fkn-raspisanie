package store.artefamily.fknschedule.logic

import store.artefamily.fknschedule.model.CalData
import store.artefamily.fknschedule.model.Lesson
import store.artefamily.fknschedule.model.ScheduleData
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields

/**
 * Логика недель числитель/знаменатель — зеркалит app.js сайта:
 * чётные ISO-недели = числитель, каникулы не отменяют тип недели,
 * воскресенье показываем уходящую неделю (переключение — в понедельник).
 */
object ScheduleLogic {

    val MSK: ZoneId = ZoneId.of("Europe/Moscow")

    fun today(): LocalDate = LocalDate.now(MSK)

    fun isoWeek(date: LocalDate): Int =
        date.get(WeekFields.ISO.weekOfWeekBasedYear())

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

    data class NextLesson(
        val lesson: Lesson,
        val time: String,
        val daysAhead: Int,          // 0 = сегодня
        val startsInMin: Long?,      // null = не сегодня
        val dow: Int
    )

    /**
     * Следующая пара: сегодня (если ещё не поздно) или в ближайшие дни.
     * Праздники и каникулы из cal.json учитываются: отменённый день не
     * становится «следующей парой» — напоминания и виджеты его пропускают.
     * Возвращает null, если пар больше нет.
     */
    fun nextLesson(data: ScheduleData, group: String, sub: String?, week: String, cal: CalData?, nowMin: Int): NextLesson? {
        val today = today()
        val todayDow = today.dayOfWeek.value

        // Сегодня — если это не праздник из cal.json
        if (resolveDay(today, cal).kind != "holiday") {
            val todayList = lessonsFor(data, group, sub, week, todayDow)
            for ((slot, l) in todayList) {
                val from = slot.fromMin
                if (from != null && nowMin < from) {
                    return NextLesson(l, slot.time, 0, (from - nowMin).toLong(), todayDow)
                }
            }
        }

        // Ближайшие дни до конца недели — та же отображаемая неделя
        // (учитывает ручной выбор), праздничные дни пропускаем.
        for (offset in 1..(7 - todayDow)) {
            val date = today.plusDays(offset.toLong())
            if (date.dayOfWeek.value == 7) continue
            if (resolveDay(date, cal).kind == "holiday") continue
            val list = lessonsFor(data, group, sub, week, date.dayOfWeek.value)
            if (list.isNotEmpty()) {
                val (slot, l) = list.first()
                return NextLesson(l, slot.time, offset, null, date.dayOfWeek.value)
            }
        }

        // Следующая ISO-неделя: с её понедельника, каждый день — свой тип
        // из cal.json (переносы, принудительный числитель/знаменатель).
        val nextMonday = today.plusDays((8 - todayDow).toLong())
        for (i in 0..5) {
            val date = nextMonday.plusDays(i.toLong())
            if (resolveDay(date, cal).kind == "holiday") continue
            val list = lessonsFor(data, group, sub, resolveDay(date, cal).kind, date.dayOfWeek.value)
            if (list.isNotEmpty()) {
                val (slot, l) = list.first()
                return NextLesson(l, slot.time, (8 - todayDow) + i, null, date.dayOfWeek.value)
            }
        }
        return null
    }
}
