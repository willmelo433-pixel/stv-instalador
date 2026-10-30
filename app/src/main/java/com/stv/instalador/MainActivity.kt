package com.stv.instalador

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var apk: File? = null
    private var installedPackage: String? = null
    private var waitingPermission = false
    private var waitingInstall = false
    private var downloading = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        web = WebView(this)
        web.settings.apply {
            userAgentString = "$userAgentString STVInstaller/3"
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { startActivityForResult(params.createIntent(), 10); true }
                catch (_: Exception) { fileCallback = null; callback.onReceiveValue(null); false }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = navigate(request.url)
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = navigate(Uri.parse(url))
        }
        web.setDownloadListener { url, _, _, _, _ ->
            val uri = Uri.parse(url)
            if (uri.scheme == "https" && uri.host?.endsWith(".supabase.co") == true && uri.path?.startsWith("/storage/v1/object/sign/apk/") == true) {
                web.evaluateJavascript("(document.body.innerText.match(/SHA-256:\\s*([a-fA-F0-9]{64})/)||[])[1]||''") { value ->
                    val checksum = value.trim('"')
                    if (Regex("^[a-fA-F0-9]{64}$").matches(checksum)) download(url, checksum)
                    else message("Não foi possível verificar o arquivo. Valide o código novamente.")
                }
            } else external(uri)
        }
        setContentView(web)
        if (state == null || web.restoreState(state) == null) web.loadUrl(BuildConfig.PANEL_URL)
    }

    private fun navigate(uri: Uri): Boolean {
        val site = Uri.parse(BuildConfig.PANEL_URL)
        if (uri.scheme == "stv-install" || uri.scheme == "stv-manual") {
            if (Uri.parse(web.url ?: "").host != site.host) return true
            val url = uri.getQueryParameter("url") ?: return true
            val target = Uri.parse(url)
            if (target.scheme != "https" || target.host?.endsWith(".supabase.co") != true || target.path?.startsWith("/storage/v1/object/sign/apk/") != true) return true
            if (uri.scheme == "stv-manual") external(target)
            else {
                val hash = uri.getQueryParameter("checksum") ?: return true
                if (Regex("^[a-fA-F0-9]{64}$").matches(hash)) download(url, hash)
            }
            return true
        }
        if (uri.scheme == "https" && (uri.host == site.host || (uri.host?.endsWith(".supabase.co") == true && uri.path?.startsWith("/storage/v1/object/sign/apk/") == true))) return false
        external(uri)
        return true
    }

    private fun external(uri: Uri) {
        if (uri.scheme !in listOf("https", "http", "mailto")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: Exception) { message("Não foi possível abrir este link.") }
    }

    private fun download(url: String, checksum: String) {
        if (downloading) return
        downloading = true
        message("Baixando aplicativo…")
        thread {
            val file = File(File(filesDir, "apk").apply { mkdirs() }, "stv.apk")
            var connection: HttpURLConnection? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                connection = c
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.instanceFollowRedirects = false
                check(c.responseCode == 200) { "Link expirado ou download indisponível." }
                val hash = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var count = input.read(buffer)
                    while (count > 0) {
                        output.write(buffer, 0, count)
                        hash.update(buffer, 0, count)
                        count = input.read(buffer)
                    }
                } }
                val actual = hash.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(checksum, true)) { "Arquivo corrompido. Tente novamente." }
                val pkg = packageManager.getPackageArchiveInfo(file.path, 0)?.packageName ?: error("Arquivo APK inválido.")
                runOnUiThread { apk = file; installedPackage = pkg; install() }
            } catch (e: Exception) {
                file.delete()
                runOnUiThread { message(e.message ?: "Falha no download. Tente novamente.") }
            } finally {
                connection?.disconnect()
                runOnUiThread { downloading = false }
            }
        }
    }

    private fun install() {
        val file = apk ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                waitingPermission = true
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            waitingInstall = true
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { waitingInstall = false; message("Não foi possível abrir a instalação do Android.") }
    }

    override fun onResume() {
        super.onResume()
        if (waitingPermission) {
            waitingPermission = false
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) install()
            else AlertDialog.Builder(this).setMessage("A instalação precisa da sua permissão.").setPositiveButton("PERMITIR") { _, _ -> install() }.setNegativeButton("CANCELAR", null).show()
        } else if (waitingInstall) {
            waitingInstall = false
            val pkg = installedPackage ?: return
            val launch = packageManager.getLaunchIntentForPackage(pkg) ?: packageManager.getLeanbackLaunchIntentForPackage(pkg)
            if (launch != null) AlertDialog.Builder(this).setMessage("Aplicativo disponível neste aparelho.").setPositiveButton("ABRIR APLICATIVO") { _, _ -> startActivity(launch) }.setNegativeButton("VOLTAR AO SITE", null).show()
            else AlertDialog.Builder(this).setMessage("Instalação não concluída.").setPositiveButton("TENTAR NOVAMENTE") { _, _ -> install() }.setNegativeButton("VOLTAR", null).show()
        }
    }

    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10) { fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCallback = null }
    }
    override fun onSaveInstanceState(state: Bundle) { web.saveState(state); super.onSaveInstanceState(state) }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onPause() { CookieManager.getInstance().flush(); super.onPause() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
