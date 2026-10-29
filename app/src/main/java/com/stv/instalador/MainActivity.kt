package com.stv.instalador

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var codeInput: EditText
    private lateinit var validateBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var installBtn: Button
    private lateinit var otherBtn: Button
    private lateinit var spinner: ProgressBar
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var status: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var authorized: ActivationResult.Authorized? = null
    private var downloaded: File? = null
    private var lastFailedAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        codeInput = findViewById(R.id.codeInput)
        validateBtn = findViewById(R.id.validateBtn)
        downloadBtn = findViewById(R.id.downloadBtn)
        installBtn = findViewById(R.id.installBtn)
        otherBtn = findViewById(R.id.otherBtn)
        spinner = findViewById(R.id.spinner)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        status = findViewById(R.id.status)

        validateBtn.setOnClickListener { validate() }
        downloadBtn.setOnClickListener { startDownload() }
        installBtn.setOnClickListener { install() }
        otherBtn.setOnClickListener { reset() }
        codeInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); validateBtn.requestFocus(); validate(); true } else false
        }
        codeInput.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (pendingInstall) { pendingInstall = false; install() }
    }

    @SuppressLint("HardwareIds")
    private fun deviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "desconhecido"

    private fun validate() {
        val code = codeInput.text.toString().trim().uppercase()
        if (code.length < 4) { showStatus("Digite um código de ativação válido.", error = true); return }
        authorized = null
        downloaded = null
        setBusy(true)
        downloadBtn.visibility = View.GONE
        installBtn.visibility = View.GONE
        showStatus("Validando código no painel…", error = false)
        io.execute {
            try {
                val result = PanelApi.activate(code, deviceId())
                main.post {
                    setBusy(false)
                    when (result) {
                        is ActivationResult.Authorized -> {
                            authorized = result
                            showStatus("✓ Código válido! Download liberado.", error = false, ok = true)
                            codeInput.isEnabled = false
                            validateBtn.visibility = View.GONE
                            downloadBtn.visibility = View.VISIBLE
                            otherBtn.visibility = View.VISIBLE
                            downloadBtn.requestFocus()
                        }
                        is ActivationResult.Denied -> {
                            showStatus(result.message ?: deniedText(result.reason), error = true)
                            codeInput.requestFocus()
                        }
                    }
                }
            } catch (e: Exception) {
                main.post { failWithRetry("Falha de conexão: ${e.message ?: "verifique a internet"}", ::validate) }
            }
        }
    }

    private fun deniedText(reason: String) = when (reason.lowercase()) {
        "expirado" -> "Código expirado. Solicite um novo código."
        "bloqueado" -> "Código ou aparelho bloqueado. Entre em contato com o suporte."
        "usado" -> "Este código já foi usado em outro aparelho."
        else -> "Código inválido. Confira e tente novamente."
    }

    private fun startDownload() {
        val auth = authorized ?: return
        downloadBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.progress = 0
        progressText.text = "Iniciando download…"
        showStatus("Baixando aplicativo STV…", error = false)
        val target = File(File(filesDir, "apk"), "stv.apk")
        io.execute {
            try {
                val hash = PanelApi.download(auth.downloadUrl, target, auth.size) { pct, bytes ->
                    main.post {
                        val mb = "%.1f MB".format(bytes / 1_048_576.0)
                        if (pct >= 0) { progress.progress = pct; progressText.text = "$pct% · $mb" }
                        else { progress.isIndeterminate = true; progressText.text = mb }
                    }
                }
                main.post {
                    if (!hash.equals(auth.sha256, ignoreCase = true)) {
                        target.delete()
                        failWithRetry("O arquivo baixado não passou na verificação de integridade (SHA-256). Tente novamente.", ::startDownload)
                        return@post
                    }
                    downloaded = target
                    progress.isIndeterminate = false
                    progress.progress = 100
                    progressText.text = "100% · integridade verificada"
                    showStatus("✓ Download concluído! Arquivo verificado.", error = false, ok = true)
                    downloadBtn.visibility = View.GONE
                    installBtn.visibility = View.VISIBLE
                    installBtn.requestFocus()
                }
            } catch (e: Exception) {
                target.delete()
                main.post {
                    authorized = null
                    downloadBtn.visibility = View.GONE
                    progress.visibility = View.GONE
                    progressText.visibility = View.GONE
                    codeInput.isEnabled = true
                    validateBtn.visibility = View.VISIBLE
                    failWithRetry("Falha no download: ${e.message ?: "conexão interrompida"}. Toque em VALIDAR CÓDIGO para tentar de novo.", ::validate)
                }
            }
        }
    }

    private var pendingInstall = false

    private fun install() {
        val file = downloaded ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            showStatus("Permita a instalação de apps pelo STV Instalador e volte para continuar.", error = false)
            pendingInstall = true
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (e: Exception) {
            showStatus("Não foi possível abrir o instalador do Android: ${e.message}", error = true)
        }
    }

    private fun reset() {
        authorized = null
        downloaded?.delete(); downloaded = null
        codeInput.isEnabled = true
        codeInput.setText("")
        validateBtn.visibility = View.VISIBLE
        validateBtn.isEnabled = true
        downloadBtn.visibility = View.GONE
        downloadBtn.isEnabled = true
        installBtn.visibility = View.GONE
        otherBtn.visibility = View.GONE
        progress.visibility = View.GONE
        progressText.visibility = View.GONE
        status.visibility = View.GONE
        codeInput.requestFocus()
    }

    private fun failWithRetry(msg: String, retry: () -> Unit) {
        setBusy(false)
        lastFailedAction = retry
        showStatus(msg, error = true)
        downloadBtn.isEnabled = true
        validateBtn.requestFocus()
    }

    private fun setBusy(busy: Boolean) {
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        validateBtn.isEnabled = !busy
        codeInput.isEnabled = !busy && authorized == null
    }

    private fun showStatus(msg: String, error: Boolean, ok: Boolean = false) {
        status.visibility = View.VISIBLE
        status.text = msg
        status.setTextColor(ContextCompat.getColor(this, when { error -> R.color.error; ok -> R.color.ok; else -> R.color.muted }))
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(codeInput.windowToken, 0)
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
