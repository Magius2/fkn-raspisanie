package store.artefamily.fknschedule

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import store.artefamily.fknschedule.databinding.ActivityMainBinding
import store.artefamily.fknschedule.logic.ScheduleLogic
import store.artefamily.fknschedule.model.CalData
import store.artefamily.fknschedule.model.ScheduleData
import store.artefamily.fknschedule.sync.Notifier
import store.artefamily.fknschedule.sync.Store
import store.artefamily.fknschedule.sync.SyncWorker
import store.artefamily.fknschedule.sync.fetchAndSave
import store.artefamily.fknschedule.sync.parseSel
import store.artefamily.fknschedule.widget.TodayWidgetProvider
import store.artefamily.fknschedule.widget.TimerWidgetProvider
import kotlinx.coroutines.*
import java.time.LocalDateTime

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var data: ScheduleData? = null
    private var cal: CalData? = null
    private var loading = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Notifier.ensureChannel(this)
        SyncWorker.enqueue(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = DayAdapter()

        binding.groupSel.onItemClickListener = object : android.widget.AdapterView.OnItemClickListener {
            override fun onItemClick(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                val sel = binding.groupSel.adapter.getItem(pos) as String
                val value = sel.substringAfter("Группа ").substringBefore(" ").let { g ->
                    val m = Regex("(\\d+)\\.(\\d+)").find(sel)
                    if (sel.contains("подгрупп")) "$g.${
                        Regex("(\\d+)-я подгруппа").find(sel)?.groupValues?.get(1) ?: "1"
                    }" else g
                }
                Store.setSel(this@MainActivity, value)
                render()
            }
        }

        binding.weekCh.setOnClickListener {
            Store.setManualWeek(this, "ch"); render()
        }
        binding.weekZn.setOnClickListener {
            Store.setManualWeek(this, "zn"); render()
        }
        binding.btnRefresh.setOnClickListener { refresh(true) }
        binding.btnSettings.setOnClickListener { showSettings() }

        binding.swipe.setOnRefreshListener { refresh(true) }

        refresh(false)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh(force: Boolean) {
        if (loading) return
        loading = true
        binding.swipe.isRefreshing = force
        binding.progress.visibility = if (force) View.VISIBLE else View.GONE

        scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                fetchAndSave("${Store.BASE_URL}/data.json") { Store.saveData(this@MainActivity, it) } or
                        fetchAndSave("${Store.BASE_URL}/cal.json") { Store.saveCal(this@MainActivity, it) }
            }
            data = Store.loadData(this@MainActivity)
            cal = Store.loadCal(this@MainActivity)
            loading = false
            binding.swipe.isRefreshing = false
            binding.progress.visibility = View.GONE

            if (data == null) {
                Toast.makeText(this@MainActivity, R.string.error_load, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (fetched) {
                TodayWidgetProvider.updateAll(this@MainActivity)
                TimerWidgetProvider.updateAll(this@MainActivity)
                Notifier.scheduleNext(this@MainActivity)
            }
            setupControls()
            render()
        }
    }

    private fun setupControls() {
        val d = data ?: return
        val sel = Store.getSel(this)
        val options = mutableListOf<String>()
        val values = mutableListOf<String>()
        for (g in d.groups) {
            val n = d.subgroups[g] ?: 0
            if (n == 0) {
                options.add("Группа $g"); values.add(g)
            } else {
                options.add("Группа $g — все подгруппы"); values.add(g)
                for (i in 1..n) {
                    options.add("Группа $g — $i-я подгруппа"); values.add("$g.$i")
                }
            }
        }
        val pos = values.indexOf(sel).let { if (it >= 0) it else 0 }
        val curSel = values.getOrNull(pos) ?: "1"
        if (curSel != sel) Store.setSel(this, curSel)

        binding.groupSel.setAdapter(ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options))
        binding.groupSel.setText(options.getOrNull(pos) ?: "", false)
        binding.groupSel.dismissDropDown()
    }

    private fun render() {
        val d = data ?: return
        val sel = Store.getSel(this)
        val (group, sub) = parseSel(sel)
        val week = Store.getManualWeek(this) ?: ScheduleLogic.autoWeek(cal)
        val now = LocalDateTime.now(ScheduleLogic.MSK)
        val nowMin = now.hour * 60 + now.minute
        val todayDow = ScheduleLogic.today().dayOfWeek.value

        // Воскресенье без ручного выбора — уже следующая неделя
        val shownWeek = if (todayDow == 7 && Store.getManualWeek(this) == null)
            ScheduleLogic.autoWeek(cal) else week

        binding.weekCh.isSelected = shownWeek == "ch"
        binding.weekZn.isSelected = shownWeek == "zn"

        val holiday = ScheduleLogic.holidayToday(cal)
        val title = if (holiday != null) {
            "$holiday — занятий нет"
        } else {
            "Текущая неделя — ${ScheduleLogic.weekNameAccusative(shownWeek)}"
        }
        binding.weekHint.text = buildString {
            append(title)
            append(" · ")
            append(d.updated)
        }

        val adapter = binding.recycler.adapter as DayAdapter
        adapter.submit(d, group, sub, shownWeek, todayDow, nowMin, holiday != null)
    }

    private fun showSettings() {
        val items = arrayOf(
            "Напоминания: ${if (Store.getNotificationsEnabled(this)) "вкл" else "выкл"}",
            "За сколько минут: ${Store.getNotifMinutes(this)}",
            "Источник: raspisanie.artefamily.online"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> {
                        Store.setNotificationsEnabled(this, !Store.getNotificationsEnabled(this))
                        Notifier.scheduleNext(this)
                        showSettings()
                    }
                    1 -> {
                        val options = arrayOf("10", "15", "30", "60")
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Минут до пары")
                            .setItems(options) { _, i ->
                                Store.setNotifMinutes(this, options[i].toInt())
                                Notifier.scheduleNext(this)
                            }
                            .show()
                    }
                }
            }
            .show()
    }
}
