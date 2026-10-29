package br.com.despertador

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Quem disparou o AlarmReceiver. */
enum class Kind { DAILY, TEST, VERIFY, RESUME }

/** Agendamentos via AlarmManager.setAlarmClock (isento de Doze e das restrições de início de FGS). */
object Scheduler {
    private const val TAG = "Scheduler"
    const val EXTRA_KIND = "kind"
    const val EXTRA_MODE = "mode"

    private fun requestCode(kind: Kind) = when (kind) {
        Kind.DAILY -> 1
        Kind.VERIFY, Kind.RESUME -> 2
        Kind.TEST -> 3
    }

    private fun operation(ctx: Context, kind: Kind, mode: RingMode?): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_MODE, mode?.name)
        return PendingIntent.getBroadcast(
            ctx, requestCode(kind), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Agenda o próximo disparo diário, se houver horário definido. */
    fun scheduleDaily(ctx: Context, store: Store = Store(ctx)) {
        if (!store.hasAlarm) return
        val at = CycleLogic.nextDailyTrigger(System.currentTimeMillis(), store.alarmHour, store.alarmMinute)
        schedule(ctx, at, Kind.DAILY, null)
    }

    fun schedule(ctx: Context, atMillis: Long, kind: Kind, mode: RingMode? = null): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showIntent(ctx)), operation(ctx, kind, mode))
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Sem permissão para alarmes exatos", e)
            false
        }
    }

    /** Cancela verificação/retomada pendentes. */
    fun cancelVerification(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(operation(ctx, Kind.VERIFY, null))
    }

    /** `canScheduleExactAlarms` só existe a partir do API 31; antes disso o agendamento exato é livre. */
    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}
