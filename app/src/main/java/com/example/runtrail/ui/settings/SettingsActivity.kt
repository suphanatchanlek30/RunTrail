package com.example.runtrail.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import com.example.runtrail.databinding.DialogReminderBinding
import com.example.runtrail.domain.ReminderTime
import com.example.runtrail.reminder.ReminderScheduler
import java.util.Calendar
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.runtrail.R
import com.example.runtrail.App
import com.android.volley.Request
import com.example.runtrail.net.ApiConfig
import com.example.runtrail.net.ApiRequest
import com.example.runtrail.net.VolleyApi
import org.json.JSONObject
import com.example.runtrail.data.Accounts
import com.example.runtrail.data.BackupInfo
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.RunBackup
import com.example.runtrail.data.RunRepository
import com.example.runtrail.sync.SyncManager
import com.example.runtrail.ui.auth.LoginActivity
import com.example.runtrail.ui.common.editServerUrl
import com.example.runtrail.ui.common.runAsync
import com.example.runtrail.ui.common.userMessage
import com.example.runtrail.ui.home.MainActivity
import com.example.runtrail.databinding.ActivitySettingsBinding
import com.example.runtrail.databinding.ViewSettingRowBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.Units
import com.example.runtrail.sensor.SensorHelper
import com.example.runtrail.ui.common.BottomNav
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.onboarding.ProfileSetupActivity
import com.example.runtrail.ui.sensor.SensorCheckActivity
import com.example.runtrail.ui.common.Sheets

/**
 * 17 · Settings — ค่าทั้งหมดเก็บใน SharedPreferences (MA09)
 * "ทดสอบคืนสถานะหน้าจอ" เรียก recreate() แล้วนับจำนวนครั้งที่ Activity ถูกสร้างใหม่ผ่าน onSaveInstanceState
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsHelper
    private var createdCount = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.content.applySystemBarsPadding(top = true)
        prefs = PrefsHelper(this)
        createdCount = (savedInstanceState?.getInt(KEY_CREATED) ?: 0) + 1

        BottomNav.setup(this, binding.navContainer.bottomNav, R.id.nav_settings)

        setupRow(binding.rowUnit, R.drawable.ic_ruler, R.string.setting_unit)
        setupRow(binding.rowGoal, R.drawable.ic_target, R.string.setting_weekly_goal)
        setupRow(binding.rowPace, R.drawable.ic_gauge, R.string.setting_target_pace)
        setupRow(binding.rowAutoPause, R.drawable.ic_pause, R.string.setting_auto_pause, toggle = true)
        setupRow(binding.rowKmAlert, R.drawable.ic_bell, R.string.setting_km_alert, toggle = true)
        setupRow(binding.rowReminder, R.drawable.ic_clock, R.string.setting_reminder)
        setupRow(binding.rowTheme, R.drawable.ic_sun, R.string.setting_theme)
        setupRow(binding.rowLanguage, R.drawable.ic_globe, R.string.setting_language)
        setupRow(binding.rowAccount, R.drawable.ic_shield, R.string.setting_account)
        setupRow(binding.rowSync, R.drawable.ic_cloud_up, R.string.setting_sync)
        setupRow(binding.rowLeaderboard, R.drawable.ic_globe, R.string.setting_leaderboard, toggle = true)
        setupRow(binding.rowUpdates, R.drawable.ic_bell, R.string.setting_updates, toggle = true)
        setupRow(binding.rowCountry, R.drawable.ic_globe, R.string.setting_country)
        setupRow(binding.rowServer, R.drawable.ic_server, R.string.setting_server)
        setupRow(binding.rowExport, R.drawable.ic_download, R.string.setting_export)
        setupRow(binding.rowImport, R.drawable.ic_upload, R.string.setting_import)
        setupRow(binding.rowPassword, R.drawable.ic_lock, R.string.setting_password)
        setupRow(binding.rowLogout, R.drawable.ic_x, R.string.setting_logout)
        setupRow(binding.rowDeleteAccount, R.drawable.ic_trash, R.string.setting_delete_account)
        binding.rowDeleteAccount.tvTitle.setTextColor(getColor(R.color.danger))
        setupRow(binding.rowSensors, R.drawable.ic_cpu, R.string.setting_sensors)
        setupRow(binding.rowRecreate, R.drawable.ic_rotate, R.string.setting_recreate)

        binding.cardProfile.setOnClickListener {
            startActivity(Intent(this, ProfileSetupActivity::class.java).putExtra(ProfileSetupActivity.EXTRA_EDIT, true))
        }
        binding.rowUnit.root.setOnClickListener { chooseUnit() }
        binding.rowGoal.root.setOnClickListener { chooseGoal() }
        binding.rowTheme.root.setOnClickListener { chooseTheme() }
        binding.rowLanguage.root.setOnClickListener { chooseLanguage() }
        binding.rowPace.root.setOnClickListener { choosePace() }
        binding.rowAutoPause.root.setOnClickListener { binding.rowAutoPause.swToggle.toggle() }
        binding.rowKmAlert.root.setOnClickListener { binding.rowKmAlert.swToggle.toggle() }
        binding.rowAutoPause.swToggle.setOnCheckedChangeListener { _, checked -> prefs.autoPause = checked }
        binding.rowKmAlert.swToggle.setOnCheckedChangeListener { _, checked -> prefs.kmAlert = checked }
        binding.rowReminder.root.setOnClickListener { editReminder() }
        // MA09 SAF: ให้ผู้ใช้เลือกเองว่าจะบันทึก/เปิดไฟล์ที่ไหน
        binding.rowExport.root.setOnClickListener {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            exportLauncher.launch(getString(R.string.backup_file_name, date))
        }
        binding.rowImport.root.setOnClickListener { importLauncher.launch(arrayOf(RunBackup.MIME, "text/plain", "application/octet-stream")) }
        binding.rowServer.root.setOnClickListener { editServerUrl { render() } }
        binding.rowAccount.root.setOnClickListener {
            if (!Accounts.isLoggedIn) startActivity(Intent(this, LoginActivity::class.java))
        }
        binding.rowSync.root.setOnClickListener {
            if (!Accounts.isLoggedIn) startActivity(Intent(this, LoginActivity::class.java))
            else { SyncManager.requestSync(this); toast(getString(R.string.setting_sync_started)) }
        }
        binding.rowLeaderboard.root.setOnClickListener { binding.rowLeaderboard.swToggle.toggle() }
        binding.rowLeaderboard.swToggle.setOnCheckedChangeListener { _, checked ->
            if (checked != prefs.leaderboardOptIn) {
                prefs.leaderboardOptIn = checked
                SyncManager.requestSync(this)
            }
        }
        binding.rowUpdates.root.setOnClickListener { binding.rowUpdates.swToggle.toggle() }
        // ประเทศสำหรับอันดับ "ประเทศของฉัน" — เดิมตั้งได้เฉพาะตอนสมัคร (AUDIT A7)
        binding.rowCountry.root.setOnClickListener {
            com.example.runtrail.ui.common.CountryPicker.show(this, prefs.countryCode) { code ->
                prefs.countryCode = code          // ตั้ง profileDirty → ซิงก์ส่งขึ้น server
                SyncManager.requestSync(this)
                render()
            }
        }
        binding.rowUpdates.swToggle.setOnCheckedChangeListener { _, checked -> prefs.updatesAlert = checked }
        binding.rowPassword.root.setOnClickListener { changePassword() }
        binding.rowLogout.root.setOnClickListener { confirmLogout() }
        binding.rowDeleteAccount.root.setOnClickListener { confirmDeleteAccount() }
        binding.rowSensors.root.setOnClickListener { startActivity(Intent(this, SensorCheckActivity::class.java)) }
        binding.rowRecreate.root.setOnClickListener { recreate() }
    }

    override fun onResume() {
        super.onResume()
        binding.navContainer.bottomNav.selectedItemId = R.id.nav_settings
        RunRepository.addListener(onDataChanged)
        render()
    }

    private fun setupRow(row: ViewSettingRowBinding, icon: Int, title: Int, toggle: Boolean = false) {
        row.ivIcon.setImageResource(icon)
        row.tvTitle.setText(title)
        row.tvValue.visibility = if (toggle) View.GONE else View.VISIBLE
        row.ivChevron.visibility = if (toggle) View.GONE else View.VISIBLE
        row.swToggle.visibility = if (toggle) View.VISIBLE else View.GONE
    }

    private fun render() {
        val miles = prefs.useMiles
        binding.tvAvatar.text = prefs.initial
        binding.tvName.text = prefs.name.ifBlank { getString(R.string.settings_name_empty) }
        binding.tvBody.text = getString(R.string.settings_body, prefs.weightKg, prefs.heightCm, prefs.age)
        binding.rowUnit.tvValue.text = Fmt.unit(miles)
        // เป้าหมาย/เพซเป้าหมายเก็บเป็นหน่วย กม. (เหมือน server) — โหมดไมล์แปลงเพื่อแสดง (AUDIT A6)
        binding.rowGoal.tvValue.text = "${Units.goalText(prefs.weeklyGoalKm, miles)} ${Fmt.unit(miles)}"
        binding.rowPace.tvValue.text = "${Fmt.pace(prefs.targetPaceSec.toDouble(), miles)}${Fmt.paceUnit(miles)}"
        binding.rowTheme.tvValue.text = getString(THEME_LABELS[THEME_MODES.indexOf(prefs.themeMode).coerceAtLeast(0)])
        binding.rowLanguage.tvValue.text = getString(LANGUAGE_LABELS[currentLanguageIndex()])
        binding.rowAutoPause.swToggle.isChecked = prefs.autoPause
        binding.rowKmAlert.swToggle.isChecked = prefs.kmAlert
        binding.rowReminder.tvValue.text = if (prefs.reminderEnabled)
            Fmt.reminderSummary(prefs.reminderHour, prefs.reminderMinute, prefs.reminderDays)
        else getString(R.string.setting_reminder_off)
        val info = BackupInfo(this)
        fun backupText(e: BackupInfo.Entry?) =
            e?.let { getString(R.string.setting_backup_last, Fmt.cardDate(it.at), it.count) } ?: getString(R.string.setting_backup_never)
        binding.rowExport.tvValue.text = backupText(info.lastExport)
        binding.rowImport.tvValue.text = backupText(info.lastImport)
        binding.rowServer.tvValue.text = prefs.apiUrl.removePrefix("http://").removePrefix("https://")
        val session = Accounts.session
        binding.rowAccount.tvValue.text = session?.email ?: getString(R.string.setting_account_guest)
        binding.rowAccount.ivChevron.visibility = if (session == null) View.VISIBLE else View.GONE
        val pending = RunRepository.pendingCount()
        binding.rowSync.tvValue.text = when {
            session == null -> getString(R.string.setting_sync_login)
            pending > 0 -> getString(R.string.setting_sync_pending, pending)
            prefs.lastSyncAt > 0 -> getString(R.string.setting_sync_done, Fmt.time(prefs.lastSyncAt))
            else -> getString(R.string.setting_sync_never)
        }
        binding.rowLeaderboard.swToggle.isChecked = prefs.leaderboardOptIn
        binding.rowUpdates.swToggle.isChecked = prefs.updatesAlert
        binding.rowUpdates.root.visibility = if (session != null) View.VISIBLE else View.GONE
        binding.groupAccount.visibility = if (session != null) View.VISIBLE else View.GONE
        binding.rowLeaderboard.root.visibility = if (session != null) View.VISIBLE else View.GONE
        binding.rowCountry.root.visibility = if (session != null) View.VISIBLE else View.GONE
        binding.dividerCountry.visibility = if (session != null) View.VISIBLE else View.GONE
        binding.rowCountry.tvValue.text = com.example.runtrail.ui.common.CountryPicker.label(prefs.countryCode)
        binding.rowSensors.tvValue.text = "${SensorHelper.countAvailable(this)}/${SensorHelper.specs.size}"
        binding.rowRecreate.tvValue.text = getString(R.string.setting_recreate_value, createdCount)
    }

    private fun chooseUnit() {
        Sheets.choose(
            this, R.drawable.ic_ruler, getString(R.string.setting_unit), getString(R.string.sheet_unit_sub),
            listOf(
                Sheets.Option(getString(R.string.unit_km_full), getString(R.string.sheet_unit_km_hint)),
                Sheets.Option(getString(R.string.unit_mile_full), getString(R.string.sheet_unit_mile_hint)),
            ),
            selected = if (prefs.useMiles) 1 else 0,
        ) { which ->
            prefs.useMiles = which == 1
            render()
        }
    }

    /** ธีม: ตามระบบ / กลางวัน / กลางคืน — AppCompatDelegate จะ recreate ทุกหน้าที่เปิดอยู่ให้เปลี่ยนสีทันที */
    private fun chooseTheme() {
        Sheets.choose(
            this, R.drawable.ic_sun, getString(R.string.setting_theme), getString(R.string.sheet_theme_sub),
            THEME_LABELS.map { Sheets.Option(getString(it)) },
            selected = THEME_MODES.indexOf(prefs.themeMode).coerceAtLeast(0),
        ) { which ->
            prefs.themeMode = THEME_MODES[which]
            AppCompatDelegate.setDefaultNightMode(THEME_MODES[which])
        }
    }

    /** ภาษา: ตามระบบ / ไทย / English — เก็บด้วย per-app language (autoStoreLocales) แล้วทุกหน้าเปลี่ยนภาษาทันที */
    private fun chooseLanguage() {
        Sheets.choose(
            this, R.drawable.ic_globe, getString(R.string.setting_language), getString(R.string.sheet_language_sub),
            LANGUAGE_LABELS.map { Sheets.Option(getString(it)) },
            selected = currentLanguageIndex(),
        ) { which ->
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(LANGUAGE_TAGS[which]))
            (application as App).refreshLanguage()
        }
    }

    private fun currentLanguageIndex(): Int {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return LANGUAGE_TAGS.indexOfFirst { it.isNotEmpty() && tag.startsWith(it) }.takeIf { it >= 0 } ?: 0
    }

    private fun chooseGoal() {
        val values = (5..50 step 5).toList()
        val unit = Fmt.unit(false)   // ตัวเลือกเป็น กม. เสมอ
        Sheets.choose(
            this, R.drawable.ic_target, getString(R.string.setting_weekly_goal), getString(R.string.sheet_goal_sub),
            values.map { Sheets.Option(it.toString(), getString(R.string.sheet_goal_per_week, unit)) },
            // ค่าปัจจุบันไม่อยู่ในรายการ (เช่น 23 จากหน้าโปรไฟล์) → -1 ไม่มีช่องไหนถูกเลือกไว้ จึงกดได้ทุกช่อง (AUDIT A5)
            selected = values.indexOf(prefs.weeklyGoalKm),
            columns = 3,
        ) { which ->
            prefs.weeklyGoalKm = values[which]
            render()
        }
    }

    private fun choosePace() {
        val values = (270..480 step 15).toList()
        Sheets.choose(
            this, R.drawable.ic_gauge, getString(R.string.setting_target_pace), getString(R.string.sheet_pace_sub),
            values.map { Sheets.Option(Fmt.pace(it), paceLevel(it)) },
            selected = values.indexOf(prefs.targetPaceSec),
            columns = 3,
        ) { which ->
            prefs.targetPaceSec = values[which]
            render()
        }
    }

    /** คำอธิบายระดับเพซสั้น ๆ ใต้ตัวเลข */
    private fun paceLevel(sec: Int): String = getString(
        when {
            sec < 330 -> R.string.pace_level_fast
            sec < 390 -> R.string.pace_level_steady
            else -> R.string.pace_level_easy
        },
    )

    // ---------------- ส่งออก / นำเข้า (MA09: SAF CreateDocument / OpenDocument)

    /** ระบบเปิดหน้าเลือกที่บันทึกไฟล์ → ได้ Uri กลับมา (null = ผู้ใช้กดยกเลิก) */
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(RunBackup.MIME)) { uri ->
        if (uri != null) exportTo(uri)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    private fun exportTo(uri: Uri) {
        runAsync({
            val runs = RunRepository.allWithTracks()
            if (runs.isNotEmpty()) {
                // เขียนผ่าน ContentResolver → OutputStream (ไฟล์อยู่ที่ผู้ใช้เลือก เช่น Downloads / Google Drive)
                contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    out.write(RunBackup.toJson(runs).toString().toByteArray(Charsets.UTF_8))
                }
                BackupInfo(this).recordExport(runs.size)    // บันทึกลงไฟล์ส่วนตัวของแอป
            }
            runs.size
        }) { r ->
            r.onSuccess { n -> toast(if (n == 0) getString(R.string.backup_export_empty) else getString(R.string.backup_exported, n)); render() }
                .onFailure { toast(getString(R.string.backup_failed)) }
        }
    }

    private fun importFrom(uri: Uri) {
        runAsync({
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val added = RunRepository.importRuns(RunBackup.parse(text))
            BackupInfo(this).recordImport(added)
            added
        }) { r ->
            r.onSuccess { n ->
                toast(if (n == 0) getString(R.string.backup_imported_none) else getString(R.string.backup_imported, n))
                if (n > 0 && Accounts.isLoggedIn) SyncManager.requestSync(this)
                render()
            }.onFailure { e ->
                toast(getString(if (e is IllegalArgumentException) R.string.backup_invalid else R.string.backup_failed))
            }
        }
    }

    // ---------------- เตือนไปวิ่ง (MA13 AlarmManager + MA08 SwitchCompat / CheckBox / TimePickerDialog)

    /** Android 13+: ขอสิทธิ์แจ้งเตือนตอนเปิดการเตือน (ประกาศใน Manifest อย่างเดียวไม่พอ) */
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast(getString(R.string.reminder_need_notif))
    }

    private fun editReminder() {
        val b = DialogReminderBinding.inflate(layoutInflater)
        var hour = prefs.reminderHour
        var minute = prefs.reminderMinute
        // CheckBox ของแต่ละวัน จันทร์ → อาทิตย์ คู่กับ Calendar.DAY_OF_WEEK
        val days = listOf(
            b.cbMon to Calendar.MONDAY, b.cbTue to Calendar.TUESDAY, b.cbWed to Calendar.WEDNESDAY,
            b.cbThu to Calendar.THURSDAY, b.cbFri to Calendar.FRIDAY, b.cbSat to Calendar.SATURDAY,
            b.cbSun to Calendar.SUNDAY,
        )
        fun showTime() { b.tvTime.text = String.format(java.util.Locale.US, "%02d:%02d", hour, minute) }
        fun enableInputs(on: Boolean) {
            b.tvTime.isEnabled = on
            b.tvTime.alpha = if (on) 1f else 0.4f
            days.forEach { (cb, _) -> cb.isEnabled = on }
        }

        b.swReminder.isChecked = prefs.reminderEnabled
        days.forEach { (cb, day) ->
            cb.text = Fmt.dayShort(day)
            cb.isChecked = ReminderTime.has(prefs.reminderDays, day)
        }
        showTime()
        enableInputs(b.swReminder.isChecked)
        b.swReminder.setOnCheckedChangeListener { _, checked -> enableInputs(checked) }
        b.tvTime.setOnClickListener {
            TimePickerDialog(this, R.style.ThemeOverlay_RunTrail_Picker, { _, h, m ->
                hour = h; minute = m; showTime()
            }, hour, minute, true).show()
        }
        b.tvExact.visibility = if (ReminderScheduler.canExact(this)) View.GONE else View.VISIBLE
        b.tvExact.setOnClickListener { openExactAlarmSettings() }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_reminder)
            .setView(b.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mask = days.filter { it.first.isChecked }.sumOf { ReminderTime.bit(it.second) }
                val on = b.swReminder.isChecked
                if (on && mask == 0) { toast(getString(R.string.reminder_no_days)); return@setOnClickListener }
                prefs.reminderEnabled = on
                prefs.reminderHour = hour
                prefs.reminderMinute = minute
                if (mask != 0) prefs.reminderDays = mask
                val at = ReminderScheduler.schedule(this)
                dialog.dismiss()
                render()
                if (at == null) { toast(getString(R.string.reminder_turned_off)); return@setOnClickListener }
                toast(getString(R.string.reminder_saved, Fmt.cardDate(at)))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        dialog.show()
    }

    /** Android 12+: พาไปหน้าระบบเพื่ออนุญาต "การปลุกและการช่วยเตือน" (ACTION_REQUEST_SCHEDULE_EXACT_ALARM) */
    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun changePassword() {
        val box = layoutInflater.inflate(R.layout.dialog_password, null)
        val old = box.findViewById<android.widget.EditText>(R.id.etOld)
        val new = box.findViewById<android.widget.EditText>(R.id.etNew)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_password)
            .setView(box)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (new.text.length < 8) { new.error = getString(R.string.auth_password_short); return@setOnClickListener }
                val o = old.text.toString(); val n = new.text.toString()
                val body = JSONObject().apply {
                    put("old_password", o); put("new_password", n); put("device_name", App.services.deviceName)
                }
                // MA11: POST + body → ④ สำเร็จ / ⑤ ผิดพลาด
                val q = ApiRequest(
                    Request.Method.POST, ApiConfig.CHANGE_PASSWORD, body,
                    { r ->
                        // server ออก token ชุดใหม่ (เครื่องอื่นถูกออกจากระบบ) → เก็บไว้ใช้ต่อ
                        val sessions = App.services.sessions
                        sessions.get()?.let { s ->
                            sessions.save(s.copy(accessToken = r.getString("access_token"), refreshToken = r.getString("refresh_token")))
                        }
                        dialog.dismiss()
                        toast(getString(R.string.setting_password_changed))
                    },
                    { e -> old.error = e.userMessage() },
                )
                q.setTag(this)
                VolleyApi.getQueue(this).add(q)
            }
        }
        dialog.show()
    }

    private fun confirmLogout() {
        val pending = Accounts.unsyncedCount()
        Sheets.confirm(
            this, R.drawable.ic_x, getString(R.string.setting_logout_title),
            if (pending > 0) getString(R.string.setting_logout_pending, pending) else getString(R.string.setting_logout_body),
            confirmText = getString(R.string.setting_logout), cancelText = getString(R.string.action_cancel),
            tone = if (pending > 0) Sheets.Tone.DANGER else Sheets.Tone.NEUTRAL,
        ) {
            runAsync({ Accounts.logout(applicationContext) }) {
                toast(getString(R.string.setting_logged_out))
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
    }

    private fun confirmDeleteAccount() {
        Sheets.confirm(
            this, R.drawable.ic_trash, getString(R.string.setting_delete_title), getString(R.string.setting_delete_body),
            confirmText = getString(R.string.action_delete), cancelText = getString(R.string.action_cancel), tone = Sheets.Tone.DANGER,
        ) {
            runAsync({ Accounts.deleteAccount(applicationContext) }) { r ->
                r.onSuccess {
                    toast(getString(R.string.setting_deleted))
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }.onFailure { toast(it.userMessage()) }
            }
        }
    }

    private val onDataChanged: () -> Unit = { render() }

    override fun onPause() {
        super.onPause()
        RunRepository.removeListener(onDataChanged)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CREATED, createdCount)
    }

    companion object {
        private const val KEY_CREATED = "created_count"

        private val THEME_MODES = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES,
        ).toList()
        private val THEME_LABELS = listOf(R.string.theme_system, R.string.theme_day, R.string.theme_night)

        /** "" = ตามภาษาของเครื่อง */
        private val LANGUAGE_TAGS = listOf("", "th", "en")
        private val LANGUAGE_LABELS = listOf(R.string.language_system, R.string.language_th, R.string.language_en)
    }
}
