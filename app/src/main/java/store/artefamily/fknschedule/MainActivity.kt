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

    /** Метки в выпадающем списке и их значения ("8", "8.2") — по одному индексу. */
    private var groupLabels: List<String> = emptyList()
    private var groupValues: List<String> = emptyList()

    /** Защита от рекурсии: выставление кнопок недели не должно считаться выбором пользователя. */
    private var bindingWeek = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) Notifier.scheduleNext(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Notifier.ensureChannel(this)
        SyncWorker.enqueue(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = DayAdapter()

        binding.groupSel.onItemClickListener = object : android.widget.AdapterView.OnItemClickListener {
            override fun onItemClick(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                val value = groupValues.getOrNull(pos) ?: return
                Store.setSel(this@MainActivity, value)
                binding.groupSel.dismissDropDown()
                render()
                refreshWidgets()
            }
        }

        binding.weekToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || bindingWeek) return@addOnButtonCheckedListener
            val week = when (checkedId) {
                R.id.weekCh -> "ch"
                R.id.weekZn -> "zn"
                else -> return@addOnButtonCheckedListener
            }
            if (Store.getManualWeek(this) != week) {
                Store.setManualWeek(this, week)
            }
            render()
            refreshWidgets()
        }

        binding.btnRefresh.setOnClickListener { refresh(true) }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.swipe.setOnRefreshListener { refresh(true) }

        refresh(false)
    }

    override fun onResume() {
        super.onResume()
        // Вернулись в приложение — день мог смениться, а виджеты отстать.
        if (data != null) {
            render()
            refreshWidgets()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshWidgets() {
        TodayWidgetProvider.updateAll(this)
        TimerWidgetProvider.updateAll(this)
        Notifier.scheduleNext(this)
    }

    private fun refresh(force: Boolean) {
        if (loading) return
        loading = true
        binding.swipe.isRefreshing = force
        binding.progress.visibility = if (force) View.VISIBLE else View.GONE

        scope.launch {
            val okData = withContext(Dispatchers.IO) {
                fetchAndSave("${Store.BASE_URL}/data.json") { Store.saveData(this@MainActivity, it) }
            }
            withContext(Dispatchers.IO) {
                fetchAndSave("${Store.BASE_URL}/cal.json") { Store.saveCal(this@MainActivity, it) }
            }

            data = Store.loadData(this@MainActivity)
            cal = Store.loadCal(this@MainActivity)
            loading = false
            binding.swipe.isRefreshing = false
            binding.progress.visibility = View.GONE

            val d = data
            if (d == null) {
                Toast.makeText(this@MainActivity, R.string.error_load, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (okData) refreshWidgets()
            setupControls()
            binding.toolbar.subtitle = "${d.title} · ${d.subtitle}"
            render()
        }
    }

    private fun setupControls() {
        val d = data ?: return
        val sel = Store.getSel(this)

        val labels = mutableListOf<String>()
        val values = mutableListOf<String>()
        for (g in d.groups) {
            val n = d.subgroups[g] ?: 0
            if (n == 0) {
                labels.add("Группа $g"); values.add(g)
            } else {
                labels.add("Группа $g — все подгруппы"); values.add(g)
                for (i in 1..n) {
                    labels.add("Группа $g — $i-я подгруппа"); values.add("$g.$i")
                }
            }
        }
        groupLabels = labels
        groupValues = values

        // Сохранённый выбор мог исчезнуть после обновления расписания — откатываемся на группу.
        val pos = values.indexOf(sel).let { if (it >= 0) it else 0 }
        val current = values.getOrNull(pos) ?: "1"
        if (current != sel) Store.setSel(this, current)

        binding.groupSel.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        binding.groupSel.setText(labels.getOrNull(pos) ?: "", false)
    }

    private fun render() {
        val d = data ?: return
        val (group, sub) = parseSel(Store.getSel(this))
        val manual = Store.getManualWeek(this)
        val auto = ScheduleLogic.autoWeek(cal)
        val todayDow = ScheduleLogic.today().dayOfWeek.value

        // Воскресенье без ручного выбора — показываем уже следующую неделю (как на сайте).
        val shownWeek = when {
            manual != null -> manual
            todayDow == 7 -> auto
            else -> auto
        }

        bindingWeek = true
        binding.weekToggle.check(if (shownWeek == "ch") R.id.weekCh else R.id.weekZn)
        bindingWeek = false

        val now = LocalDateTime.now(ScheduleLogic.MSK)
        val nowMin = now.hour * 60 + now.minute
        val holiday = ScheduleLogic.holidayToday(cal)

        binding.weekHint.text = buildString {
            if (holiday != null) {
                append("🌴 $holiday — занятий нет")
            } else {
                append("Текущая неделя — ")
                append(ScheduleLogic.weekNameAccusative(auto))
                if (manual != null && manual != auto) {
                    append(" · показан ")
                    append(ScheduleLogic.weekNameAccusative(manual))
                }
                if (ScheduleLogic.resolveDay(ScheduleLogic.today(), cal).src != "авто") {
                    append(" (задано вручную)")
                }
            }
            append(" · ")
            append(ScheduleLogic.weekName(shownWeek))
        }

        val adapter = binding.recycler.adapter as DayAdapter
        adapter.submit(d, group, sub, shownWeek, todayDow, nowMin, holiday != null)
    }

    private fun showSettings() {
        val items = arrayOf(
            "Напоминания: ${if (Store.getNotificationsEnabled(this)) "включены" else "выключены"}",
            "Напомнить за: ${Store.getNotifMinutes(this)} мин",
            "Проверить обновление расписания"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        Store.setNotificationsEnabled(this, !Store.getNotificationsEnabled(this))
                        if (Store.getNotificationsEnabled(this) &&
                            Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            Notifier.scheduleNext(this)
                        }
                        showSettings()
                    }
                    1 -> {
                        val options = arrayOf("5", "10", "15", "30", "60")
                        val current = options.indexOf(Store.getNotifMinutes(this).toString())
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Напомнить за (минут)")
                            .setSingleChoiceItems(options, current) { dlg, i ->
                                Store.setNotifMinutes(this, options[i].toInt())
                                Notifier.scheduleNext(this)
                                dlg.dismiss()
                            }
                            .show()
                    }
                    2 -> refresh(true)
                }
            }
            .show()
    }
}