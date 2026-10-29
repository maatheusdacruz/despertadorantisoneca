package br.com.despertador

import java.time.Instant
import java.time.ZoneId

enum class RingMode { ALARM, VERIFY }

/** Regras do ciclo alarme → verificação, sem dependências do Android. */
object CycleLogic {
    /**
     * Ao desligar: o alarme normal sempre agenda verificação; a verificação cumprida no prazo
     * (sem ter escalado ao volume máximo) encerra o ciclo; se escalou, agenda nova verificação.
     */
    fun needsVerificationAfterDismiss(mode: RingMode, escalated: Boolean): Boolean =
        mode == RingMode.ALARM || escalated

    /** Próximo disparo diário (estritamente depois de [nowMillis]) às [hour]:[minute] no fuso [zone]. */
    fun nextDailyTrigger(nowMillis: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var t = now.toLocalDate().atTime(hour, minute).atZone(zone)
        if (!t.isAfter(now)) t = now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone)
        return t.toInstant().toEpochMilli()
    }

    fun shouldOfferPlanB(consecutiveFailures: Int, paired: Boolean): Boolean =
        !paired || consecutiveFailures >= Config.FAILS_BEFORE_PLAN_B
}
