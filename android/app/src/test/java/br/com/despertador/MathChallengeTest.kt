package br.com.despertador

import br.com.despertador.MathChallenge.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MathChallengeTest {
    @Test fun `contas respeitam as faixas`() {
        val c = MathChallenge(Random(1))
        repeat(500) {
            val p = c.current
            assertTrue(p.a in 12..49 && p.b in 3..9 && p.c in 11..99)
            c.submit(p.answer)
        }
    }

    @Test fun `oito acertos seguidos terminam`() {
        val c = MathChallenge(Random(2))
        repeat(7) { assertEquals(Result.CORRECT, c.submit(c.current.answer)) }
        assertEquals(Result.DONE, c.submit(c.current.answer))
    }

    @Test fun `erro zera a contagem`() {
        val c = MathChallenge(Random(3))
        repeat(7) { c.submit(c.current.answer) }
        assertEquals(7, c.streak)
        assertEquals(Result.WRONG, c.submit(c.current.answer + 1))
        assertEquals(0, c.streak)
        repeat(7) { assertEquals(Result.CORRECT, c.submit(c.current.answer)) }
        assertEquals(Result.DONE, c.submit(c.current.answer))
    }

    @Test fun `resposta vazia conta como erro`() {
        val c = MathChallenge(Random(4))
        assertEquals(Result.WRONG, c.submit(null))
    }
}
