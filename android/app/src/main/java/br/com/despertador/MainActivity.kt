package br.com.despertador

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.Date

/** Tela principal: status e os botões de horário, pareamento e teste. */
class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private lateinit var statusView: TextView
    private lateinit var fixButton: android.widget.Button
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        AlarmService.createChannel(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        root.addView(label("Despertador", 32f, bold = true))
        statusView = label("", 17f).apply { gravity = Gravity.START }
        root.addSpaced(statusView, 24)
        root.addSpaced(button("Definir horário") { pickTime() }, 24)
        root.addSpaced(button("Parear com o computador") { pair() })
        root.addSpaced(button("Testar em ${Config.TEST_DELAY_S} s") { test() })
        fixButton = button("Corrigir pendências") { fixNextIssue() }
        root.addSpaced(fixButton)
        setContentView(ScrollView(this).apply { addView(root) })

        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val issues = issues()
        val time = if (store.hasAlarm) "%02d:%02d (todos os dias)".format(store.alarmHour, store.alarmMinute) else "não definido"
        val pc = store.host?.let { "pareado ($it)" } ?: "NÃO pareado"
        val verify = store.verifyAt.takeIf { it > 0 }?.let {
            "\nVerificação pendente às ${DateFormat.format("HH:mm", Date(it))}"
        } ?: ""
        val warn = if (issues.isEmpty()) "\n\nTudo certo com as permissões ✔" else
            "\n\nPendências:\n" + issues.joinToString("\n") { "• ${it.text}" }
        statusView.text = "Horário: $time\nComputador: $pc$verify$warn"
        fixButton.visibility = if (issues.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ----------------------------------------------------------------- ações
    private fun pickTime() {
        val h = store.alarmHour.takeIf { it >= 0 } ?: 7
        TimePickerDialog(this, { _, hour, minute ->
            store.alarmHour = hour
            store.alarmMinute = minute
            Scheduler.scheduleDaily(this, store)
            toast("Alarme definido para %02d:%02d".format(hour, minute))
            render()
        }, h, store.alarmMinute, true).show()
    }

    private fun pair() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val pairing = barcode.rawValue?.let(Protocol::parsePairing)
                if (pairing == null) {
                    toast("QR inválido. Escaneie o QR de pareamento do programa no computador.")
                } else {
                    store.savePairing(pairing)
                    toast("Pareado com ${pairing.host}")
                    render()
                }
            }
            .addOnFailureListener { toast("Leitor de QR indisponível: ${it.message}") }
    }

    private fun test() {
        if (issues().any { it.blocksRinging }) {
            toast("Resolva as pendências antes de testar.")
            return
        }
        if (Scheduler.schedule(this, System.currentTimeMillis() + Config.TEST_DELAY_S * 1000, Kind.TEST)) {
            toast("Vai tocar em ${Config.TEST_DELAY_S} s. Bloqueie a tela para testar de verdade.")
        } else {
            toast("Sem permissão para alarmes exatos.")
        }
    }

    // ------------------------------------------------------------- pendências
    private class Issue(val text: String, val blocksRinging: Boolean, val fix: () -> Unit)

    private fun issues(): List<Issue> = buildList {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            add(Issue("Notificações bloqueadas (sem elas a tela do alarme não abre)", true) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            })
        }
        if (Build.VERSION.SDK_INT >= 37 && !granted(LOCAL_NETWORK)) {
            add(Issue("Sem acesso à rede local (não consigo falar com o computador)", false) {
                permissionLauncher.launch(arrayOf(LOCAL_NETWORK))
            })
        }
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            add(Issue("Notificações em tela cheia desativadas", true) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            })
        }
        if (!Scheduler.canScheduleExact(this@MainActivity)) {
            add(Issue("Alarmes exatos não permitidos", true) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            })
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            add(Issue("Otimização de bateria ativa (pode atrasar ou matar o alarme)", false) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            })
        }
        if (!store.isPaired) add(Issue("Celular ainda não pareado (toque em “Parear com o computador”)", false) { pair() })
    }

    private fun fixNextIssue() {
        val issue = issues().firstOrNull() ?: return
        AlertDialog.Builder(this).setMessage(issue.text).setPositiveButton("Corrigir") { _, _ -> issue.fix() }
            .setNegativeButton("Agora não", null).show()
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37 && !granted(LOCAL_NETWORK)) add(LOCAL_NETWORK)
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
