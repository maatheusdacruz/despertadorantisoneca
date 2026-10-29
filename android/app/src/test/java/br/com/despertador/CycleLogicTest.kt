package br.com.despertador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CycleLogicTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private fun at(h: Int, m: Int, s: Int = 0, day: Int = 10) =
        ZonedDateTime.of(2026, 3, day, h, m, s, 0, sp).toInstant().toEpochMilli()

    @Test fun `alarme normal sempre agenda verificacao`() {
        assertTrue(CycleLogic.needsVerificationAfterDismiss(RingMode.ALARM, escalated = true))
    }

    @Test fun `verificacao cumprida no prazo encerra o ciclo`() {
        assertFalse(CycleLogic.needsVerificationAfterDismiss(RingMode.VERIFY, escalated = false))
    }

    @Test fun `verificacao estourada agenda nova verificacao`() {
        assertTrue(CycleLogic.needsVerificationAfterDismiss(RingMode.VERIFY, escalated = true))
    }

    @Test fun `proximo disparo e hoje se ainda nao passou`() {
        assertEquals(at(7, 0), CycleLogic.nextDailyTrigger(at(6, 0), 7, 0, sp))
    }

    @Test fun `proximo disparo e amanha se ja passou ou e agora`() {
        assertEquals(at(7, 0, day = 11), CycleLogic.nextDailyTrigger(at(7, 0, 0), 7, 0, sp))
        assertEquals(at(7, 0, day = 11), CycleLogic.nextDailyTrigger(at(7, 0, 1), 7, 0, sp))
        assertEquals(at(7, 0, day = 11), CycleLogic.nextDailyTrigger(at(23, 59), 7, 0, sp))
    }

    @Test fun `plano B apos 30 falhas ou sem pareamento`() {
        assertFalse(CycleLogic.shouldOfferPlanB(29, paired = true))
        assertTrue(CycleLogic.shouldOfferPlanB(30, paired = true))
        assertTrue(CycleLogic.shouldOfferPlanB(0, paired = false))
    }
}
