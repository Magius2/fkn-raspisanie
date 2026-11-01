package store.artefamily.fknschedule

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import store.artefamily.fknschedule.logic.ScheduleLogic
import store.artefamily.fknschedule.model.Lesson
import store.artefamily.fknschedule.model.ScheduleData

/** «Окно» — перерыв между парами не короче этого (минут). */
private const val WINDOW_GAP_MIN = 90

class DayAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var data: ScheduleData? = null
    private var group = "1"
    private var sub: String? = null
    private var week = "ch"
    private var todayDow = -1
    private var nowMin = -1
    private var holiday = false

    /** У группы нет пар ни в один день недели — показываем пустое состояние. */
    private var nothingThisWeek = false

    fun submit(d: ScheduleData, g: String, s: String?, w: String, dow: Int, nm: Int, hol: Boolean) {
        data = d
        group = g
        sub = s
        week = w
        todayDow = dow
        nowMin = nm
        holiday = hol
        nothingThisWeek = !hol && d.days.all { day ->
            ScheduleLogic.lessonsFor(d, g, s, w, day.dow).isEmpty()
        }
        notifyDataSetChanged()
    }

    class DayVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.dayTitle)
        val badge: TextView = v.findViewById(R.id.dayBadge)
        val list: RecyclerView = v.findViewById(R.id.dayLessons)
    }

    class EmptyVH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == TYPE_EMPTY) R.layout.item_empty else R.layout.item_day
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return if (viewType == TYPE_EMPTY) EmptyVH(v) else DayVH(v)
    }

    override fun getItemViewType(pos: Int) =
        if (nothingThisWeek) TYPE_EMPTY else TYPE_DAY

    override fun getItemCount(): Int {
        val d = data ?: return 0
        return when {
            holiday -> 0
            nothingThisWeek -> 1
            else -> d.days.size
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        if (h !is DayVH) return
        val d = data ?: return
        val day = d.days[pos]
        val isToday = day.dow == todayDow

        h.title.text = day.name
        h.badge.visibility = if (isToday) View.VISIBLE else View.GONE
        (h.itemView as ViewGroup).background = if (isToday)
            ContextCompat.getDrawable(h.itemView.context, R.drawable.day_today_bg)
        else null

        // «Окна» между парами показываем на всех днях (как на сайте),
        // метку «сейчас» — только для сегодняшнего.
        val lessons = ScheduleLogic.lessonsFor(d, group, sub, week, day.dow)
        val items = mutableListOf<Any>()
        var prevEnd: Int? = null
        for ((slot, l) in lessons) {
            val from = slot.fromMin
            if (from != null && prevEnd != null && from - prevEnd >= WINDOW_GAP_MIN) {
                items.add(WindowItem(prevEnd, from))
            }
            if (slot.toMin != null) prevEnd = slot.toMin
            items.add(LessonItem(slot.time, l, slot.fromMin, slot.toMin, isToday))
        }

        h.list.layoutManager = LinearLayoutManager(h.itemView.context)
        h.list.adapter = LessonAdapter(items, nowMin)
        h.list.isNestedScrollingEnabled = false
    }

    companion object {
        private const val TYPE_DAY = 0
        private const val TYPE_EMPTY = 1
    }
}

data class LessonItem(
    val time: String,
    val lesson: Lesson,
    val fromMin: Int?,
    val toMin: Int?,
    val isToday: Boolean
)

data class WindowItem(val from: Int?, val to: Int?)

class LessonAdapter(
    private val items: List<Any>,
    private val nowMin: Int
) : RecyclerView.Adapter<LessonAdapter.LVH>() {

    class LVH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.lessonTime)
        val subject: TextView = v.findViewById(R.id.lessonSubject)
        val meta: TextView = v.findViewById(R.id.lessonMeta)
        // В item_window.xml этого view нет — у «окна» нет тегов.
        val tags: TextView? = v.findViewById(R.id.lessonTags)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LVH {
        val layout = if (viewType == 1) R.layout.item_window else R.layout.item_lesson
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return LVH(v)
    }

    override fun getItemViewType(pos: Int) = if (items[pos] is WindowItem) 1 else 0

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: LVH, pos: Int) {
        val ctx = h.itemView.context
        when (val it = items[pos]) {
            is WindowItem -> {
                h.subject.text = ctx.getString(R.string.window_label)
                h.meta.text = ctx.getString(R.string.window_free)
                h.time.text = "—"
                h.tags?.visibility = View.GONE
            }
            is LessonItem -> {
                h.time.text = it.time
                h.subject.text = it.lesson.subject
                val meta = listOfNotNull(
                    it.lesson.teacher,
                    it.lesson.roomDisplay().takeIf { x -> x.isNotBlank() }
                ).joinToString(" · ")
                h.meta.text = meta
                h.meta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

                // «сейчас» — включая минуту окончания пары (8:00–9:35 → до 9:35)
                val isNow = it.isToday && it.fromMin != null && it.toMin != null &&
                        nowMin >= it.fromMin && nowMin <= it.toMin
                val tags = mutableListOf<String>()
                if (it.lesson.isRemote) tags.add(ctx.getString(R.string.tag_remote))
                if (it.lesson.week != "all") tags.add(ScheduleLogic.weekName(it.lesson.week))
                if (isNow) tags.add(ctx.getString(R.string.tag_now))
                val tvTags = h.tags
                if (tvTags != null) {
                    tvTags.text = tags.joinToString("   ")
                    tvTags.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
                }

                if (isNow) {
                    h.itemView.background = ContextCompat.getDrawable(ctx, R.drawable.lesson_now_bg)
                    h.subject.setTypeface(h.subject.typeface, Typeface.BOLD)
                } else {
                    h.itemView.background = ContextCompat.getDrawable(ctx, R.drawable.lesson_bg)
                    h.subject.setTypeface(null, Typeface.NORMAL)
                }
            }
        }
    }
}
