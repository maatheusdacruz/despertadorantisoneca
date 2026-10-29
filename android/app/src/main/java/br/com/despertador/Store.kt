package br.com.despertador

import android.content.Context

/** Estado persistido (sobrevive a reinício do app e do celular). */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("despertador", Context.MODE_PRIVATE)

    var alarmHour: Int
        get() = sp.getInt("hour", -1)
        set(v) = put { putInt("hour", v) }
    var alarmMinute: Int
        get() = sp.getInt("minute", 0)
        set(v) = put { putInt("minute", v) }
    val hasAlarm: Boolean get() = alarmHour in 0..23

    var host: String?
        get() = sp.getString("host", null)
        set(v) = put { putString("host", v) }
    var key: String?
        get() = sp.getString("key", null)
        set(v) = put { putString("key", v) }
    val isPaired: Boolean get() = host != null && key != null

    /** Não nulo enquanto o alarme/verificação está tocando. */
    var ringMode: RingMode?
        get() = sp.getString("ringMode", null)?.let { runCatching { RingMode.valueOf(it) }.getOrNull() }
        set(v) = put { putString("ringMode", v?.name) }

    /** Verificação já escalou ao volume máximo (prazo estourado). */
    var escalated: Boolean
        get() = sp.getBoolean("escalated", false)
        set(v) = put { putBoolean("escalated", v) }

    /** Fim do prazo da verificação que está tocando (epoch ms). */
    var verifyDeadline: Long
        get() = sp.getLong("verifyDeadline", 0)
        set(v) = put { putLong("verifyDeadline", v) }

    /** Horário (epoch ms) da verificação pendente; 0 se não houver. */
    var verifyAt: Long
        get() = sp.getLong("verifyAt", 0)
        set(v) = put { putLong("verifyAt", v) }

    fun savePairing(p: Pairing) {
        put { putString("host", p.host); putString("key", p.key) }
    }

    // commit() síncrono: o estado precisa estar em disco antes de um possível reinício.
    private inline fun put(block: android.content.SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).commit()
    }
}
