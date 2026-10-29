package br.com.despertador

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Tela do alarme: mostra por cima do bloqueio, escaneia o QR do computador e exibe o código. */
class RingActivity : ComponentActivity() {
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private var scannerBroken = false
    private var challenge: MathChallenge? = null

    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var scanButton: View
    private lateinit var codeBox: LinearLayout
    private lateinit var codeView: TextView
    private lateinit var planBButton: View
    private lateinit var planBBox: LinearLayout
    private lateinit var problemView: TextView
    private lateinit var progressView: TextView
    private lateinit var answerInput: EditText

    private val onStatus: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // O botão voltar não encerra o alarme.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContentView(buildUi())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render()
    }

    override fun onStart() {
        super.onStart()
        RingStatus.addListener(onStatus)
        main.post(ticker)
    }

    override fun onStop() {
        RingStatus.removeListener(onStatus)
        main.removeCallbacks(ticker)
        super.onStop()
    }

    // --------------------------------------------------------------------- UI
    private fun buildUi(): View {
        val white = Color.WHITE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        root.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            textSize = 72f
            setTextColor(white)
            gravity = Gravity.CENTER
        })
        titleView = label("", 26f, Color.parseColor("#FFB300"), bold = true)
        root.addSpaced(titleView)
        statusView = label("", 15f, Color.parseColor("#B0BEC5"))
        root.addSpaced(statusView, 8)

        scanButton = button("Escanear QR no computador") { onScanClicked() }
        root.addSpaced(scanButton, 28)

        codeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        codeBox.addView(label("Digite este código no computador:", 18f, white))
        codeView = label("", 64f, Color.parseColor("#69F0AE"), bold = true).apply { typeface = Typeface.MONOSPACE }
        codeBox.addView(codeView)
        root.addSpaced(codeBox, 20)

        planBButton = button("Plano B: resolver contas") { openPlanB() }.apply { visibility = View.GONE }
        root.addSpaced(planBButton, 28)

        planBBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        progressView = label("", 16f, Color.parseColor("#B0BEC5"))
        planBBox.addView(progressView)
        problemView = label("", 44f, white, bold = true)
        planBBox.addSpaced(problemView, 12)
        answerInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(white)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> submitAnswer(); true }
        }
        planBBox.addSpaced(answerInput, 8)
        planBBox.addSpaced(button("Responder") { submitAnswer() }, 8)
        planBBox.addSpaced(button("Voltar ao QR") { closePlanB() }, 8)
        root.addSpaced(planBBox, 12)

        return android.widget.ScrollView(this).apply { isFillViewport = true; addView(root) }
    }

    private fun render() {
        val mode = store.ringMode
        if (mode == null) {
            finishAndRemoveTask()
            return
        }
        titleView.text = when {
            mode == RingMode.ALARM -> "ACORDE!"
            store.escalated -> "PRAZO ESTOURADO!"
            else -> "Verificação"
        }
        statusView.text = buildStatus(mode)
        val offer = CycleLogic.shouldOfferPlanB(RingStatus.failures, store.isPaired) || scannerBroken
        planBButton.visibility = if (offer && planBBox.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun buildStatus(mode: RingMode): String {
        val link = when {
            !store.isPaired -> "Celular não pareado com o computador."
            RingStatus.connected -> "Computador avisado ✔"
            RingStatus.failures == 0 -> "Avisando o computador…"
            else -> "Sem resposta do computador (${RingStatus.failures} tentativas)"
        }
        if (mode == RingMode.VERIFY && !store.escalated) {
            val left = ((store.verifyDeadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            return "Repita o ritual em %d:%02d\n%s".format(left / 60, left % 60, link)
        }
        return link
    }

    // ------------------------------------------------------------------- scan
    private fun onScanClicked() {
        val key = store.key
        if (key == null) {
            toast("Celular não pareado. Use o plano B.")
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            startScan(key)
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = startScan(key)
            override fun onDismissCancelled() = toast("É preciso desbloquear o celular para escanear.")
            override fun onDismissError() = toast("Não foi possível pedir o desbloqueio.")
        })
    }

    private fun startScan(key: String) {
        // Sem auto-zoom de propósito: o QR tem de ser lido de perto, não da cama.
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val nonce = barcode.rawValue?.let(Protocol::parseAlarmQr)
                if (nonce == null) {
                    toast("Esse não é o QR do alarme. Escaneie o QR da tela do computador.")
                } else {
                    codeView.text = Protocol.computeCode(key, nonce).let { "${it.substring(0, 3)} ${it.substring(3)}" }
                    codeBox.visibility = View.VISIBLE
                }
            }
            .addOnFailureListener {
                scannerBroken = true
                toast("Leitor de QR indisponível (${it.message}). Use o plano B.")
                render()
            }
    }

    // ----------------------------------------------------------------- plano B
    private fun openPlanB() {
        challenge = MathChallenge().also { showProblem(it) }
        planBBox.visibility = View.VISIBLE
        scanButton.visibility = View.GONE
        codeBox.visibility = View.GONE
        render()
        answerInput.requestFocus()
    }

    private fun closePlanB() {
        planBBox.visibility = View.GONE
        scanButton.visibility = View.VISIBLE
        render()
    }

    private fun showProblem(c: MathChallenge) {
        problemView.text = "${c.current} = ?"
        progressView.text = "Acertos seguidos: ${c.streak} de ${Config.MATH_COUNT}"
        answerInput.setText("")
    }

    private fun submitAnswer() {
        val c = challenge ?: return
        when (c.submit(answerInput.text.toString().trim().toIntOrNull())) {
            MathChallenge.Result.DONE -> AlarmService.dismiss(this)
            MathChallenge.Result.WRONG -> {
                toast("Errou! Recomeçando a contagem.")
                showProblem(c)
            }
            MathChallenge.Result.CORRECT -> showProblem(c)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
