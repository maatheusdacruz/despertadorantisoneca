package br.com.despertador

import kotlin.random.Random

/** Plano B: [Config.MATH_COUNT] contas seguidas `a × b + c`; errar uma zera a contagem. */
class MathChallenge(private val random: Random = Random.Default, private val total: Int = Config.MATH_COUNT) {
    data class Problem(val a: Int, val b: Int, val c: Int) {
        val answer: Int get() = a * b + c
        override fun toString() = "$a × $b + $c"
    }

    enum class Result { CORRECT, WRONG, DONE }

    var current: Problem = newProblem()
        private set
    var streak: Int = 0
        private set

    fun submit(value: Int?): Result {
        val ok = value == current.answer
        streak = if (ok) streak + 1 else 0
        current = newProblem()
        return when {
            !ok -> Result.WRONG
            streak >= total -> Result.DONE
            else -> Result.CORRECT
        }
    }

    private fun newProblem() = Problem(random.nextInt(12, 50), random.nextInt(3, 10), random.nextInt(11, 100))
}
