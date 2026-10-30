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

class DayAdapter : RecyclerView.Adapter<DayAdapter.VH>() {

    private var data: ScheduleData? = null
    private var group = "1"
    private var sub: String? = null
    private var week = "ch"
    private var todayDow = -1
    private var nowMin = -1
    private var holiday = false

    fun submit(d: ScheduleData, g: String, s: String?, w: String, dow: Int, nm: Int, hol: Boolean) {
        data = d; group = g; sub = s; week = w; todayDow = dow; nowMin = nm; holiday = hol
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.dayTitle)
        val badge: TextView = v.findViewById(R.id.dayBadge)
        val list: RecyclerView = v.findViewById(R.id.dayLessons)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_day, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int {
        val d = data ?: return 0
        return if (holiday) 0 else d.days.size
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val d = data ?: return
        val day = d.days[pos]
        val isToday = day.dow == todayDow

        h.title.text = day.name
        h.badge.visibility = if (isToday) View.VISIBLE else View.GONE
        (h.itemView as ViewGroup).background = if (isToday)
            ContextCompat.getDrawable(h.itemView.context, R.drawable.day_today_bg)
        else null

        val lessons = ScheduleLogic.lessonsFor(d, group, sub, week, day.dow)
        val windows = ScheduleLogic.windowsIn(d, group, sub, week, day.dow)

        val items = mutableListOf<Any>()
        var prevEnd: Int? = null
        for ((slot, l) in lessons) {
            val slotObj = d.days.firstOrNull { it.dow == day.dow }?.slots?.firstOrNull { it.time == slot.time }
            val from = slotObj?.fromMin
            if (isToday && from != null && prevEnd != null && from - prevEnd >= 90) {
                items.add(WindowItem(prevEnd, from))
            }
            if (slotObj?.toMin != null) prevEnd = slotObj?.toMin
            items.add(LessonItem(slot.time, l, slotObj?.fromMin, slotObj?.toMin, isToday))
        }

        h.list.layoutManager = LinearLayoutManager(h.itemView.context)
        h.list.adapter = LessonAdapter(items, nowMin, isToday)
        h.list.isNestedScrollingEnabled = false
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
    private val nowMin: Int,
    private val dayIsToday: Boolean
) : RecyclerView.Adapter<LessonAdapter.LVH>() {

    class LVH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.lessonTime)
        val subject: TextView = v.findViewById(R.id.lessonSubject)
        val meta: TextView = v.findViewById(R.id.lessonMeta)
        val tags: TextView = v.findViewById(R.id.lessonTags)
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
                h.subject.text = "Окно"
                h.meta.text = "нет пар"
                h.time.text = "—"
                h.tags.visibility = View.GONE
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

                val isNow = it.isToday && it.fromMin != null && it.toMin != null &&
                        nowMin >= it.fromMin && nowMin <= it.toMin
                val tags = mutableListOf<String>()
                if (it.lesson.isRemote) tags.add("🖥 дистанционно")
                if (it.lesson.week != "all") tags.add(ScheduleLogic.weekName(it.lesson.week))
                if (isNow) tags.add("● сейчас")
                h.tags.text = tags.joinToString("   ")
                h.tags.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE

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
