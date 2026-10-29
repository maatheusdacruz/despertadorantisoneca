package br.com.despertador

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Foreground service (mediaPlayback) que toca o alarme e avisa o computador (POST /ring a cada 2 s).
 * Tudo que muda estado roda na thread principal; só a rede roda na thread do executor.
 */
class AlarmService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private var mode: RingMode? = null
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var executor: ScheduledExecutorService? = null
    private var rampStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        createChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            if (mode != null) finishRinging() else stopSelf()
            return START_NOT_STICKY
        }
        // Reinício pelo sistema (intent nulo) retoma o que estava persistido.
        val requested = intent?.getStringExtra(Scheduler.EXTRA_MODE)?.let { runCatching { RingMode.valueOf(it) }.getOrNull() }
            ?: store.ringMode
        // Quem chamou startForegroundService exige startForeground em poucos segundos, sempre.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(requested ?: RingMode.ALARM),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (requested == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        beginRing(requested)
        return START_STICKY
    }

    override fun onDestroy() {
        releaseRingResources()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ ciclo
    private fun beginRing(request: RingMode) {
        if (mode == RingMode.ALARM && request == RingMode.VERIFY) return // alarme em curso prevalece
        releaseRingResources()
        Scheduler.cancelVerification(this)
        store.verifyAt = 0

        val resumingVerify = request == RingMode.VERIFY && store.ringMode == RingMode.VERIFY && store.verifyDeadline > 0
        when {
            request == RingMode.ALARM -> store.escalated = true
            !resumingVerify -> {
                store.verifyDeadline = System.currentTimeMillis() + Config.VERIFY_WINDOW_MIN * 60_000
                store.escalated = false
            }
        }
        mode = request
        store.ringMode = request

        RingStatus.failures = 0
        RingStatus.connected = false
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "despertador:ring").apply { acquire(WAKE_LOCK_MS) }
        rampStartedAt = System.currentTimeMillis()
        startAudio()
        startPolling()
        scheduleEscalation()
        updateNotification()
        RingStatus.changed()
    }

    private fun finishRinging() {
        val finished = mode ?: return
        val needsVerification = CycleLogic.needsVerificationAfterDismiss(finished, store.escalated)
        releaseRingResources()
        mode = null
        store.ringMode = null
        store.escalated = false
        store.verifyDeadline = 0
        if (needsVerification) {
            val at = System.currentTimeMillis() + Config.VERIFY_DELAY_MIN * 60_000
            store.verifyAt = at
            Scheduler.schedule(this, at, Kind.VERIFY)
        } else {
            store.verifyAt = 0
        }
        RingStatus.changed()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseRingResources() {
        main.removeCallbacksAndMessages(null)
        executor?.shutdownNow()
        executor = null
        player?.run { runCatching { stop() }; release() }
        player = null
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ---------------------------------------------------------------- escalada
    private fun scheduleEscalation() {
        if (mode != RingMode.VERIFY || store.escalated) return
        val wait = (store.verifyDeadline - System.currentTimeMillis()).coerceAtLeast(0)
        main.postDelayed({ escalate() }, wait)
    }

    private fun escalate() {
        if (mode != RingMode.VERIFY) return
        store.escalated = true
        applyStreamVolume()
        player?.setVolume(1f, 1f)
        updateNotification()
    }

    private fun isLoud() = mode == RingMode.ALARM || store.escalated

    // ------------------------------------------------------------------- áudio
    private fun applyStreamVolume() {
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = if (isLoud()) max else (max * Config.VERIFY_VOLUME_FRACTION).roundToInt().coerceAtLeast(1)
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Não consegui ajustar o volume do alarme", e)
        }
    }

    private fun startAudio() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val am = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
            .also { am.requestAudioFocus(it) }
        applyStreamVolume()
        try {
            val p = MediaPlayer()
            player = p
            p.setAudioAttributes(attrs)
            resources.openRawResourceFd(R.raw.alarme).use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            p.isLooping = true
            p.setOnErrorListener { failed, _, _ ->
                failed.release()
                if (player === failed) player = null
                main.postDelayed({ if (mode != null && player == null) startAudio() }, 1000)
                true
            }
            p.prepare()
            p.setVolume(currentPlayerVolume(), currentPlayerVolume())
            p.start()
            main.post(rampTick)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao tocar o alarme", e)
            player?.release()
            player = null
            main.postDelayed({ if (mode != null && player == null) startAudio() }, 2000)
        }
    }

    private fun currentPlayerVolume(): Float {
        if (!isLoud()) return 1f
        val elapsed = System.currentTimeMillis() - rampStartedAt
        val fraction = (elapsed.toFloat() / Config.VOLUME_RAMP_MS).coerceIn(0f, 1f)
        return Config.VOLUME_RAMP_START + (1f - Config.VOLUME_RAMP_START) * fraction
    }

    private val rampTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val v = currentPlayerVolume()
            runCatching { p.setVolume(v, v) }
            if (v < 1f) main.postDelayed(this, 500)
        }
    }

    // -------------------------------------------------------------------- rede
    private fun startPolling() {
        val ex = Executors.newSingleThreadScheduledExecutor()
        executor = ex
        ex.scheduleWithFixedDelay({
            val host = store.host
            val key = store.key
            val outcome = if (host != null && key != null) RingClient.ring(host, key) else null
            main.post { onPollResult(outcome) }
        }, 0, Config.RING_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun onPollResult(outcome: RingOutcome?) {
        if (mode == null) return
        when (outcome) {
            is RingOutcome.Ok -> {
                RingStatus.failures = 0
                RingStatus.connected = true
                if (outcome.dismissed) {
                    finishRinging()
                    return
                }
            }
            is RingOutcome.Fail, null -> {
                RingStatus.failures++
                RingStatus.connected = false
                if (outcome is RingOutcome.Fail) Log.w(TAG, "/ring falhou: ${outcome.reason}")
            }
        }
        RingStatus.changed()
    }

    // ------------------------------------------------------------- notificação
    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(mode ?: RingMode.ALARM))
    }

    private fun buildNotification(ringMode: RingMode) = run {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            ringMode == RingMode.ALARM -> "Acorde! Escaneie o QR no computador."
            store.escalated -> "Prazo da verificação estourado! Repita o ritual no computador."
            else -> "Verificação: repita o ritual no computador."
        }
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Despertador")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "alarme"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_MS = 2 * 60 * 60 * 1000L
        const val ACTION_DISMISS = "br.com.despertador.DISMISS"

        fun start(context: Context, mode: RingMode) {
            val intent = Intent(context, AlarmService::class.java).putExtra(Scheduler.EXTRA_MODE, mode.name)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Não foi possível iniciar o serviço do alarme", e)
            }
        }

        /** Desliga o alarme (plano B resolvido). */
        fun dismiss(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_DISMISS))
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Alarme", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarme tocando"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
