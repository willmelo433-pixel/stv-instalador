package com.stv.instalador

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {
 private lateinit var web: WebView
 private lateinit var setup: SetupFlow
 private var fileCallback: ValueCallback<Array<Uri>>? = null
 @SuppressLint("SetJavaScriptEnabled")
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  setup = SetupFlow(this)
  web = WebView(this)
  web.settings.apply {
   userAgentString = "$userAgentString STVInstaller/5"
   javaScriptEnabled = true; domStorageEnabled = true
   allowFileAccess = false; allowContentAccess = false
   mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
   setSupportMultipleWindows(false)
  }
  CookieManager.getInstance().setAcceptCookie(true)
  web.webChromeClient = object : WebChromeClient() {
   override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
    fileCallback?.onReceiveValue(null); fileCallback = callback
    return try { startActivityForResult(params.createIntent(), 10); true }
    catch (_: Exception) { fileCallback = null; callback.onReceiveValue(null); false }
   }
  }
  web.webViewClient = object : WebViewClient() {
   override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = navigate(request.url)
   @Suppress("DEPRECATION")
   override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = navigate(Uri.parse(url))
  }
  web.setDownloadListener { url, _, _, _, _ -> external(Uri.parse(url)) }
  setContentView(web)
  if (state == null || web.restoreState(state) == null) web.loadUrl(BuildConfig.PANEL_URL)
  setup.offerResume()
 }
 private fun navigate(uri: Uri): Boolean {
  val site = Uri.parse(BuildConfig.PANEL_URL)
  val current = Uri.parse(web.url ?: "")
  if (uri.scheme == "stv-setup") {
   if (current.scheme == "https" && current.host == site.host) setup.start(uri)
   return true
  }
  if (uri.scheme == "stv-manual" || uri.scheme == "stv-install") {
   if (current.scheme == "https" && current.host == site.host) {
    val target = Uri.parse(uri.getQueryParameter("url") ?: "")
    if (target.scheme == "https" && target.host?.endsWith(".supabase.co") == true && target.path?.startsWith("/storage/v1/object/sign/apk/") == true) external(target)
   }
   return true
  }
  if (uri.scheme == "https" && uri.host == site.host) return false
  external(uri); return true
 }
 private fun external(uri: Uri) {
  if (uri.scheme !in listOf("https", "http", "mailto")) return
  try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
  catch (_: Exception) { Toast.makeText(this, "Não foi possível abrir este link.", Toast.LENGTH_LONG).show() }
 }
 override fun onResume() { super.onResume(); if (::setup.isInitialized) setup.onResume() }
 @Suppress("DEPRECATION")
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if (setup.onResult(requestCode, resultCode, data)) return
  if (requestCode == 10) { fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCallback = null }
 }
 override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
  super.onRequestPermissionsResult(requestCode, permissions, grantResults); setup.onPermissions(requestCode, grantResults)
 }
 override fun onSaveInstanceState(state: Bundle) { web.saveState(state); super.onSaveInstanceState(state) }
 @Suppress("DEPRECATION")
 override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
 override fun onPause() { CookieManager.getInstance().flush(); super.onPause() }
 override fun onDestroy() { web.destroy(); super.onDestroy() }
}
