package br.com.despertador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Recebe os disparos do AlarmManager e inicia o serviço de toque. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(Scheduler.EXTRA_KIND)?.let { runCatching { Kind.valueOf(it) }.getOrNull() } ?: return
        val store = Store(context)
        when (kind) {
            Kind.DAILY -> {
                Scheduler.scheduleDaily(context, store) // reagenda o de amanhã antes de qualquer coisa
                AlarmService.start(context, RingMode.ALARM)
            }
            Kind.TEST -> AlarmService.start(context, RingMode.ALARM)
            Kind.VERIFY -> AlarmService.start(context, RingMode.VERIFY)
            Kind.RESUME -> AlarmService.start(context, store.ringMode ?: return)
        }
    }
}

/**
 * Depois do boot (ou de atualização do app / mudança de hora) reagenda tudo.
 * No Android 15+ um FGS mediaPlayback não pode iniciar a partir de BOOT_COMPLETED, então o
 * alarme interrompido é religado por um alarme exato alguns segundos depois.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        Scheduler.scheduleDaily(context, store)
        val soon = System.currentTimeMillis() + Config.BOOT_DELAY_S * 1000
        val restart = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        val mode = store.ringMode
        when {
            mode != null && restart -> Scheduler.schedule(context, soon, Kind.RESUME, mode)
            store.verifyAt > 0 -> Scheduler.schedule(context, maxOf(store.verifyAt, soon), Kind.VERIFY)
        }
        Log.i("BootReceiver", "${intent.action}: ringMode=$mode verifyAt=${store.verifyAt}")
    }
}
