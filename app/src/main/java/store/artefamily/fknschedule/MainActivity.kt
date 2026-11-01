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

    /** Значения выпадающего списка ("8", "8.2") — по индексу. */
    private var groupValues: List<String> = emptyList()

    /** Защита от рекурсии: выставление кнопок недели не должно считаться выбором пользователя. */
    private var bindingWeek = false

    /** Разрешение на уведомления спрашиваем ПОСЛЕ первого рендера, а не на первом кадре. */
    private var notifPermissionAsked = false

    /** Сеть молча провалилась, но есть кэш — показываем в weekHint, а не Toast. */
    private var offline = false

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

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = DayAdapter()

        binding.groupSel.onItemClickListener = object : android.widget.AdapterView.OnItemClickListener {
            override fun onItemClick(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                val value = groupValues.getOrNull(pos) ?: return
                Store.setSel(this@MainActivity, value)
                Store.setSelChosen(this@MainActivity, true) // группа выбрана осознанно
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
        // RemoteViews/AlarmManager безопасны вне главного потока, а здесь
        // читаются и парсятся data.json/cal.json — уводим в IO.
        scope.launch(Dispatchers.IO) {
            TodayWidgetProvider.updateAll(this@MainActivity)
            TimerWidgetProvider.updateAll(this@MainActivity)
            Notifier.scheduleNext(this@MainActivity)
        }
    }

    private fun refresh(force: Boolean) {
        if (loading) return
        loading = true
        binding.swipe.isRefreshing = force
        binding.progress.visibility = if (data == null) View.VISIBLE else View.GONE

        scope.launch {
            // 1) Кэш — сразу: экран оживает даже без сети (метро, лифт, кампус).
            //    Раньше кэш грузился только ПОСЛЕ сетевых таймаутов —
            //    до минуты пустого экрана при живой офлайн-копии.
            if (data == null) {
                val cached = withContext(Dispatchers.IO) {
                    Store.loadData(this@MainActivity) to Store.loadCal(this@MainActivity)
                }
                val cachedData = cached.first
                if (cachedData != null && data == null) {
                    data = cachedData
                    cal = cached.second
                    setupControls()
                    binding.toolbar.subtitle = "${cachedData.title} · ${cachedData.subtitle}"
                    render()
                }
            }

            // 2) Сеть: оба файла параллельно, а не друг за другом.
            val (okData, _) = withContext(Dispatchers.IO) {
                coroutineScope {
                    val d = async { fetchAndSave("${Store.BASE_URL}/data.json") { Store.saveData(this@MainActivity, it) } }
                    val c = async { fetchAndSave("${Store.BASE_URL}/cal.json") { Store.saveCal(this@MainActivity, it) } }
                    d.await() to c.await()
                }
            }

            // 3) Перечитываем с диска (IO, не на главном потоке) и перерисовываем.
            data = withContext(Dispatchers.IO) { Store.loadData(this@MainActivity) }
            cal = withContext(Dispatchers.IO) { Store.loadCal(this@MainActivity) }
            // Тихий провал: кэш есть, сети нет — не пугаем Toast, помечаем в weekHint.
            offline = !okData
            loading = false
            binding.swipe.isRefreshing = false
            binding.progress.visibility = View.GONE

            val d = data
            if (d == null) {
                Toast.makeText(this@MainActivity, R.string.error_load, Toast.LENGTH_LONG).show()
                return@launch
            }
            // Безусловно: будильники и виджеты обязаны жить даже при полном
            // офлайне (холодный старт после force-stop без сети — раньше
            // цепочка тиков оставалась мёртвой до первого успешного sync).
            refreshWidgets()
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
                labels.add(getString(R.string.group_label, g)); values.add(g)
            } else {
                labels.add(getString(R.string.group_all_subgroups, g)); values.add(g)
                for (i in 1..n) {
                    labels.add(getString(R.string.group_subgroup, g, i)); values.add("$g.$i")
                }
            }
        }
        groupValues = values

        // Сохранённый выбор мог исчезнуть после обновления расписания — откатываемся
        // на группу и требуем выбрать заново (подгруппа-то пропала).
        val pos = values.indexOf(sel).let { if (it >= 0) it else 0 }
        val current = values.getOrNull(pos) ?: "1"
        if (current != sel) {
            Store.setSel(this, current)
            Store.setSelChosen(this, false)
        }

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

        // Воскресенье — фича: показываем уходящую неделю (как на сайте),
        // автоматическое переключение на новую происходит в понедельник.
        val shownWeek = manual ?: auto

        bindingWeek = true
        binding.weekToggle.check(if (shownWeek == "ch") R.id.weekCh else R.id.weekZn)
        bindingWeek = false

        val now = LocalDateTime.now(ScheduleLogic.MSK)
        val nowMin = now.hour * 60 + now.minute
        val holiday = ScheduleLogic.holidayToday(cal)

        binding.weekHint.text = buildString {
            if (holiday != null) {
                append(getString(R.string.week_holiday, holiday))
            } else {
                append(getString(R.string.week_current, ScheduleLogic.weekNameAccusative(auto)))
                if (manual != null && manual != auto) {
                    append(getString(R.string.week_shown, ScheduleLogic.weekNameAccusative(manual)))
                }
                // Тип недели на сегодня задан календарём (каникулы, переносы),
                // а не чётностью ISO — говорим об этом честно.
                if (ScheduleLogic.resolveDay(ScheduleLogic.today(), cal).src != "авто") {
                    append(getString(R.string.week_by_calendar))
                }
            }
            // Свежесть данных: «данные от 14:32», а при тихом офлайне — «нет связи · …».
            val sync = Store.lastSync(this@MainActivity)
            if (sync > 0L) {
                append(" · ")
                append(
                    if (offline) getString(R.string.offline_updated, TodayWidgetProvider.formatSyncAge(sync))
                    else getString(R.string.updated_at, TodayWidgetProvider.formatSyncAge(sync))
                )
            }
        }

        val adapter = binding.recycler.adapter as DayAdapter
        adapter.submit(d, group, sub, shownWeek, todayDow, nowMin, holiday != null)

        // Разрешение на уведомления — после первого рендера: пользователь видит
        // расписание, а не системный диалог поверх пустого экрана.
        if (!notifPermissionAsked) {
            notifPermissionAsked = true
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showSettings() {
        // Показываем реальное состояние: включено в приложении ≠ разрешено системой.
        val notifOn = Store.getNotificationsEnabled(this)
        val permMissing = !Notifier.canPost(this)
        val items = arrayOf(
            getString(if (notifOn) R.string.settings_notif_on else R.string.settings_notif_off) +
                    if (notifOn && permMissing) getString(R.string.settings_notif_no_perm) else "",
            getString(R.string.settings_lead, Store.getNotifMinutes(this)),
            getString(R.string.settings_refresh)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        Store.setNotificationsEnabled(this, !Store.getNotificationsEnabled(this))
                        if (Store.getNotificationsEnabled(this) && !Notifier.canPost(this)) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            scope.launch(Dispatchers.IO) { Notifier.scheduleNext(this@MainActivity) }
                        }
                        reopenSettings()
                    }
                    1 -> {
                        val options = arrayOf("5", "10", "15", "30", "60")
                        val current = options.indexOf(Store.getNotifMinutes(this).toString())
                        android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.settings_lead_title)
                            .setSingleChoiceItems(options, current) { dlg, i ->
                                Store.setNotifMinutes(this, options[i].toInt())
                                scope.launch(Dispatchers.IO) { Notifier.scheduleNext(this@MainActivity) }
                                dlg.dismiss()
                            }
                            // И после выбора, и после отмены — назад в настройки,
                            // а не в пустоту (диалог закрывался «в никуда»).
                            .setOnDismissListener { reopenSettings() }
                            .show()
                    }
                    2 -> refresh(true)
                }
            }
            .show()
    }

    /** Активити могли уничтожить (поворот экрана), пока диалог был открыт. */
    private fun reopenSettings() {
        if (!isFinishing && !isDestroyed) showSettings()
    }
}