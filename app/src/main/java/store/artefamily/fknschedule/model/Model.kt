package store.artefamily.fknschedule.model

import org.json.JSONObject

data class Lesson(
    val groups: List<String>,
    val week: String,        // "ch" | "zn" | "all"
    val subject: String,
    val teacher: String?,
    val room: String?,
    val sub: String?         // подгруппа, null = вся группа
) {
    val isRemote: Boolean
        get() = room?.let { Regex("(^|,\\s*)ДО(\\s*,|$)").containsMatchIn(it) } ?: false

    fun roomDisplay(): String {
        val rest = (room ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "ДО" }
            .joinToString(", ")
        return if (!isRemote) rest
        else if (rest.isNotEmpty()) "дистанционно + $rest" else "дистанционно"
    }
}

data class Slot(
    val time: String,        // "8:00–9:35"
    val lessons: List<Lesson>
) {
    val fromMin: Int? get() = parseMin(time.substringBefore("–").ifBlank { time.substringBefore("-") })
    val toMin: Int? get() = parseMin(time.substringAfter("–").ifBlank { time.substringAfter("-") })

    private fun parseMin(s: String): Int? {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(s.trim()) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }
}

data class Day(
    val name: String,        // "Понедельник"
    val dow: Int,            // 1..6
    val slots: List<Slot>
)

data class ScheduleData(
    val title: String,
    val subtitle: String,
    val updated: String,
    val groups: List<String>,
    val subgroups: Map<String, Int>,
    val days: List<Day>
) {
    companion object {
        fun fromJson(text: String): ScheduleData {
            val root = JSONObject(text)
            val groups = buildList {
                val arr = root.getJSONArray("groups")
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
            val subgroups = buildMap {
                val obj = root.optJSONObject("subgroups") ?: JSONObject()
                for (key in obj.keys()) put(key, obj.getInt(key))
            }
            val days = buildList {
                val arr = root.getJSONArray("days")
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    val slots = buildList {
                        val sarr = d.getJSONArray("slots")
                        for (j in 0 until sarr.length()) {
                            val s = sarr.getJSONObject(j)
                            val lessons = buildList {
                                val larr = s.getJSONArray("lessons")
                                for (k in 0 until larr.length()) {
                                    val l = larr.getJSONObject(k)
                                    add(
                                        Lesson(
                                            groups = buildList {
                                                val g = l.getJSONArray("groups")
                                                for (m in 0 until g.length()) add(g.getString(m))
                                            },
                                            week = l.getString("week"),
                                            subject = l.getString("subject"),
                                            teacher = l.optString("teacher").orNull(),
                                            room = l.optString("room").orNull(),
                                            sub = l.optString("sub").orNull()
                                        )
                                    )
                                }
                            }
                            add(Slot(s.getString("time"), lessons))
                        }
                    }
                    add(Day(d.getString("name"), d.getInt("dow"), slots))
                }
            }
            return ScheduleData(
                title = root.getString("title"),
                subtitle = root.getString("subtitle"),
                updated = root.getString("updated"),
                groups = groups,
                subgroups = subgroups,
                days = days
            )
        }
    }
}

/** Календарь правок: каникулы, принудительный тип недели, точечные дни. */
data class CalData(
    val weeks: Map<Int, String>,   // ISO-неделя -> "ch"|"zn"|"holiday"
    val days: Map<String, String>, // дата -> тип
    val holidays: List<Holiday>
) {
    data class Holiday(val from: String, val to: String, val note: String?)

    companion object {
        fun fromJson(text: String): CalData {
            val root = JSONObject(text)
            val weeks = buildMap {
                val obj = root.optJSONObject("weeks") ?: JSONObject()
                for (key in obj.keys()) put(key.toInt(), obj.getString(key))
            }
            val days = buildMap {
                val obj = root.optJSONObject("days") ?: JSONObject()
                for (key in obj.keys()) put(key, obj.getString(key))
            }
            val holidays = buildList {
                val arr = root.optJSONArray("holidays") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val h = arr.getJSONObject(i)
                    add(Holiday(h.getString("from"), h.getString("to"), h.optString("note").orNull()))
                }
            }
            return CalData(weeks, days, holidays)
        }
    }
}

fun String.orNull(): String? = if (this.isEmpty() || this == "null") null else this
