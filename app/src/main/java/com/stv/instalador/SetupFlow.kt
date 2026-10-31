package com.stv.instalador

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Private durable operation state. No JS bridge; every file is checked before use. */
class SetupFlow(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("verified_setup", Activity.MODE_PRIVATE)
    private var working = false
    private var waitingSettings = false
    private val apkFile get() = File(File(activity.filesDir, "setup").apply { mkdirs() }, "application.apk")
    private val configFile get() = File(File(activity.filesDir, "setup").apply { mkdirs() }, "original.config")
    private val state get() = prefs.getString("stage", "") ?: ""

    fun start(uri: Uri) {
        val url = uri.getQueryParameter("url") ?: return
        val checksum = uri.getQueryParameter("checksum") ?: return
        val configUrl = uri.getQueryParameter("configUrl") ?: return
        val configChecksum = uri.getQueryParameter("configChecksum") ?: return
        val code = uri.getQueryParameter("code") ?: return
        val device = uri.getQueryParameter("device") ?: return
        if (!validUrl(url) || !validUrl(configUrl) || !validHash(checksum) || !validHash(configChecksum) || !Regex("^[0-9]{16}$").matches(code) || device.length !in 8..100) return
        if (working) return
        prefs.edit().putString("url", url).putString("checksum", checksum).putString("configUrl", configUrl)
            .putString("configChecksum", configChecksum).putString("code", code).putString("device", device)
            .putString("stage", "download_apk").putBoolean("root_ok", false).putBoolean("android_ok", false).apply()
        resumeFlow()
    }
    private fun validUrl(value: String): Boolean {
        val u = Uri.parse(value)
        return u.scheme == "https" && u.host?.endsWith(".supabase.co") == true && u.path?.startsWith("/storage/v1/object/sign/apk/") == true
    }
    private fun validHash(value: String) = Regex("^[a-fA-F0-9]{64}$").matches(value)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); var n = input.read(b); while (n != -1) { md.update(b, 0, n); n = input.read(b) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    fun resumeFlow() {
        if (working || state.isEmpty() || state == "complete") return
        when (state) {
            "download_apk" -> downloadApk()
            "install" -> install()
            "confirm_install" -> confirmInstalled()
            "config" -> requestStorage()
        }
    }
    fun onResume() { if (waitingSettings) { waitingSettings = false; resumeFlow() } }
    fun offerResume() {
        if (state.isNotEmpty() && state != "complete") AlertDialog.Builder(activity).setMessage("Há uma instalação interrompida neste aparelho.")
            .setPositiveButton("RETOMAR") { _, _ -> resumeFlow() }.setNegativeButton("DEPOIS", null).show()
    }
    private fun showError(message: String) {
        working = false
        AlertDialog.Builder(activity).setMessage(message).setPositiveButton("TENTAR NOVAMENTE") { _, _ -> resumeFlow() }
            .setNegativeButton("VOLTAR AO SITE", null).show()
    }
    private fun authorize() {
        val connection = URL(BuildConfig.PANEL_URL + "/api/public/validate").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true; connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("code", prefs.getString("code", "")).put("device", prefs.getString("device", ""))
                .put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            check(connection.responseCode == 200) { "Falha ao renovar a autorização." }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(json.optBoolean("ok")) { "Código indisponível: ${json.optString("reason")}." }
            val config = json.getJSONObject("config")
            val url = json.getString("url"); val cu = config.getString("url")
            check(validUrl(url) && validUrl(cu)) { "Endereço de download inválido." }
            check(json.getString("checksum") == prefs.getString("checksum", "") && config.getString("checksum") == prefs.getString("configChecksum", "")) {
                "Os arquivos foram substituídos pelo administrador. Valide seu código novamente no site para iniciar a versão atual."
            }
            prefs.edit().putString("url", url).putString("configUrl", cu).apply()
        } finally { connection.disconnect() }
    }
    private fun fetchFile(url: String, destination: File, expected: String, max: Long) {
        check(validUrl(url) && validHash(expected)) { "Arquivo não autorizado." }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 30000
            check(c.responseCode == 200) { "Download indisponível. Tente novamente." }
            c.inputStream.use { input -> destination.outputStream().use { output ->
                val b = ByteArray(65536); var n = input.read(b); var total = 0L
                while (n != -1) { total += n; check(total <= max) { "Arquivo excedeu o tamanho permitido." }; output.write(b, 0, n); n = input.read(b) }
            } }
            check(hash(destination).equals(expected, true)) { "Integridade do arquivo inválida." }
        } finally { c.disconnect() }
    }
    private fun downloadApk() {
        working = true; Toast.makeText(activity, "Baixando aplicativo…", Toast.LENGTH_LONG).show()
        thread {
            try {
                if (!apkFile.exists() || hash(apkFile) != prefs.getString("checksum", "")) {
                    authorize(); fetchFile(prefs.getString("url", "") ?: "", apkFile, prefs.getString("checksum", "") ?: "", 1024L * 1024 * 1024)
                }
                prefs.edit().putString("stage", "install").apply()
                activity.runOnUiThread { working = false; install() }
            } catch (e: Exception) { activity.runOnUiThread { showError(e.message ?: "Falha ao baixar APK.") } }
        }
    }
    private fun install() {
        try {
            if (!apkFile.exists() || hash(apkFile) != prefs.getString("checksum", "")) { prefs.edit().putString("stage", "download_apk").apply(); downloadApk(); return }
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(activity).setMessage("Autorize o STV Instalador a instalar o aplicativo no seu aparelho.")
                    .setPositiveButton("PERMITIR") { _, _ -> waitingSettings = true; activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }.setNegativeButton("DEPOIS", null).show()
                return
            }
            prefs.edit().putString("stage", "confirm_install").apply()
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apkFile)
            activity.startActivityForResult(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true), 51)
        } catch (e: Exception) { prefs.edit().putString("stage", "install").apply(); showError("Não foi possível abrir o instalador Android.") }
    }
    @Suppress("DEPRECATION")
    private fun confirmInstalled() {
        try {
            val archive = activity.packageManager.getPackageArchiveInfo(apkFile.path, PackageManager.GET_SIGNATURES) ?: error("APK inválido.")
            val installed = activity.packageManager.getPackageInfo(archive.packageName, PackageManager.GET_SIGNATURES)
            val sameVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode == archive.longVersionCode else installed.versionCode == archive.versionCode
            val signatures = installed.signatures?.map { it.toCharsString() }?.toSet()
            check(sameVersion && signatures != null && signatures.isNotEmpty() && signatures == archive.signatures?.map { it.toCharsString() }?.toSet()) { "Instalação não confirmada." }
            prefs.edit().putString("package", archive.packageName).putString("stage", "config").apply()
            requestStorage()
        } catch (e: Exception) { prefs.edit().putString("stage", "install").apply(); showError("Instalação não concluída ou não confirmada. Confirme a instalação do APK para continuar.") }
    }
    private fun requestStorage() {
        val permitted = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (permitted) { configure(); return }
        AlertDialog.Builder(activity).setMessage("PERMISSÃO NECESSÁRIA: autorize o acesso ao armazenamento para que o WTV Instalador possa configurar automaticamente os arquivos necessários no seu dispositivo.")
            .setPositiveButton("AUTORIZAR") { _, _ ->
                try {
                    if (Build.VERSION.SDK_INT >= 30) { waitingSettings = true; activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))) }
                    else if (Build.VERSION.SDK_INT >= 23) activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 52)
                } catch (e: Exception) { showError("Este aparelho não disponibiliza a autorização de armazenamento necessária.") }
            }.setNegativeButton("DEPOIS", null).show()
    }
    private fun configure() {
        if (working) return; working = true
        thread {
            var rootError: String? = null; var androidError: String? = null
            try {
                if (!configFile.exists() || hash(configFile) != prefs.getString("configChecksum", "")) {
                    authorize(); fetchFile(prefs.getString("configUrl", "") ?: "", configFile, prefs.getString("configChecksum", "") ?: "", 50L * 1024 * 1024)
                }
                val bytes = configFile.readBytes(); val expected = prefs.getString("configChecksum", "") ?: ""
                val root = Environment.getExternalStorageDirectory()
                try { writeChecked(File(root, ".config"), bytes, expected); prefs.edit().putBoolean("root_ok", true).apply() }
                catch (e: Exception) { prefs.edit().putBoolean("root_ok", false).apply(); rootError = "Raiz do armazenamento interno (/.config): ${e.message}" }
                try {
                    val tree = prefs.getString("androidTree", null)
                    if (tree == null) writeChecked(File(root, "Android/.config"), bytes, expected)
                    else writeTree(Uri.parse(tree), bytes, expected)
                    prefs.edit().putBoolean("android_ok", true).apply()
                } catch (e: Exception) { prefs.edit().putBoolean("android_ok", false).apply(); androidError = "Pasta Android (Android/.config): ${e.message}" }
                activity.runOnUiThread {
                    working = false
                    if (rootError == null && androidError == null) complete()
                    else {
                        val message = listOfNotNull(rootError, androidError).joinToString("\n\n")
                        AlertDialog.Builder(activity).setMessage("Configuração incompleta.\n$message\n\nO Android pode bloquear a pasta Android mesmo com permissão.")
                            .setPositiveButton("TENTAR NOVAMENTE") { _, _ -> requestStorage() }
                            .setNeutralButton("SELECIONAR ANDROID") { _, _ -> selectAndroid() }.setNegativeButton("DEPOIS", null).show()
                    }
                }
            } catch (e: Exception) { activity.runOnUiThread { showError(e.message ?: "Falha no download da configuração.") } }
        }
    }
    private fun writeChecked(target: File, bytes: ByteArray, expected: String) {
        check(target.parentFile?.isDirectory == true) { "Diretório indisponível." }
        target.outputStream().use { it.write(bytes); it.fd.sync() }
        check(hash(target) == expected) { "A cópia gravada não corresponde ao arquivo original." }
    }
    private fun writeTree(uri: Uri, bytes: ByteArray, expected: String) {
        check(uri.authority == "com.android.externalstorage.documents" && DocumentsContract.getTreeDocumentId(uri) == "primary:Android") { "Selecione diretamente a pasta Android do armazenamento interno." }
        val tree = DocumentFile.fromTreeUri(activity, uri) ?: error("Pasta indisponível.")
        val file = tree.findFile(".config") ?: tree.createFile("application/octet-stream", ".config") ?: error("Não foi possível criar .config.")
        check(file.name == ".config") { "O aparelho alterou o nome do arquivo." }
        val out = activity.contentResolver.openOutputStream(file.uri, "wt") ?: error("Acesso de gravação negado.")
        out.use { it.write(bytes) }
        val input = activity.contentResolver.openInputStream(file.uri) ?: error("Não foi possível verificar a cópia.")
        check(input.use { digest(it.readBytes()) } == expected) { "Integridade da cópia inválida." }
    }
    private fun selectAndroid() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= 26) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid"))
            activity.startActivityForResult(intent, 53)
        } catch (e: Exception) { showError("Este aparelho não possui seletor de diretórios compatível.") }
    }
    fun onResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request == 51) { confirmInstalled(); return true }
        if (request != 53) return false
        val uri = data?.data
        if (result != Activity.RESULT_OK || uri == null) { showError("Seleção da pasta Android cancelada. Nenhuma conclusão foi registrada."); return true }
        try {
            check(uri.authority == "com.android.externalstorage.documents" && DocumentsContract.getTreeDocumentId(uri) == "primary:Android") { "Selecione a pasta Android, sem subdiretório." }
            activity.contentResolver.takePersistableUriPermission(uri, (data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
            prefs.edit().putString("androidTree", uri.toString()).apply(); configure()
        } catch (e: Exception) { showError(e.message ?: "Permissão do diretório negada.") }
        return true
    }
    fun onPermissions(request: Int, granted: IntArray): Boolean {
        if (request != 52) return false
        if (granted.isNotEmpty() && granted.all { it == PackageManager.PERMISSION_GRANTED }) configure()
        else showError("Permissão de armazenamento negada. As duas cópias não foram concluídas.")
        return true
    }
    private fun complete() {
        prefs.edit().putString("stage", "complete").remove("code").remove("url").remove("configUrl").apply()
        AlertDialog.Builder(activity).setMessage("INSTALAÇÃO CONCLUÍDA COM SUCESSO! Aplicativo instalado e arquivos de configuração aplicados.")
            .setPositiveButton("ABRIR APLICATIVO") { _, _ ->
                val pkg = prefs.getString("package", "") ?: ""
                val intent = activity.packageManager.getLaunchIntentForPackage(pkg) ?: activity.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                if (intent != null) activity.startActivity(intent)
            }.setNegativeButton("VOLTAR AO SITE", null).show()
    }
}
