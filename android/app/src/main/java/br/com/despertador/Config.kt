package br.com.despertador

/** Constantes ajustáveis do despertador. */
object Config {
    /** Minutos entre desligar o alarme e a verificação. */
    const val VERIFY_DELAY_MIN = 5L

    /** Prazo (minutos) para repetir o ritual durante a verificação. */
    const val VERIFY_WINDOW_MIN = 2L

    /** Falhas seguidas de /ring (a cada 2 s) até oferecer o plano B (30 ≈ 60 s). */
    const val FAILS_BEFORE_PLAN_B = 30

    /** Contas seguidas, sem erro, para desligar pelo plano B. */
    const val MATH_COUNT = 8

    /** Volume do stream de alarme na verificação (fração do máximo). */
    const val VERIFY_VOLUME_FRACTION = 0.25f

    const val RING_INTERVAL_MS = 2_000L
    const val RING_TIMEOUT_MS = 1_500
    const val VOLUME_RAMP_MS = 30_000L
    const val VOLUME_RAMP_START = 0.2f

    /** Atraso (s) para religar o alarme depois do boot (o Android 15+ proíbe FGS mediaPlayback no BOOT_COMPLETED). */
    const val BOOT_DELAY_S = 8L

    const val TEST_DELAY_S = 10L
}
