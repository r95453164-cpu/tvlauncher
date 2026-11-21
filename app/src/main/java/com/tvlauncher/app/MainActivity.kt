package com.tvlauncher.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

            webView = WebView(this)
            webView.setBackgroundColor(Color.BLACK)
            WebView.setWebContentsDebuggingEnabled(true)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    Log.e("LAUNCHER", "WebView error: ${error.description} url=${request.url}")
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("LAUNCHER-JS", "${msg.message()} @${msg.lineNumber()}")
                    return true
                }
            }

            webView.addJavascriptInterface(Bridge(this, webView), "Android")
            webView.loadUrl("file:///android_asset/launcher.html")
            setContentView(webView)

            // Kiosk включаем ТОЛЬКО если реально Device Owner
            if (dpm.isDeviceOwnerApp(packageName)) {
                try {
                    dpm.setLockTaskPackages(admin, arrayOf(packageName))
                    startLockTask()
                } catch (e: Exception) {
                    Log.e("LAUNCHER", "Lock task failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("LAUNCHER", "FATAL onCreate: ${e.stackTraceToString()}")
            Toast.makeText(this, "Критическая ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() { /* блокируем */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }
}

class Bridge(private val ctx: Context, private val webView: WebView) {

    @JavascriptInterface
    fun getInstalledApps(): String {
        return try {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val apps = pm.queryIntentActivities(intent, 0)
            val arr = JSONArray()
            val seen = mutableSetOf<String>()
            for (ri in apps) {
                val pkg = ri.activityInfo.packageName
                if (seen.contains(pkg) || pkg == ctx.packageName) continue
                seen.add(pkg)
                val obj = JSONObject()
                obj.put("package", pkg)
                obj.put("name", ri.loadLabel(pm).toString())
                try {
                    val drawable = ri.loadIcon(pm)
                    val bmp = drawableToBitmap(drawable, 144)
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 80, baos)
                    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    obj.put("icon", "data:image/png;base64,$b64")
                } catch (_: Exception) {}
                arr.put(obj)
            }
            arr.toString()
        } catch (e: Exception) {
            Log.e("LAUNCHER", "getInstalledApps failed: ${e.message}")
            "[]"
        }
    }

    @JavascriptInterface
    fun launchApp(pkg: String) {
        try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Не удалось запустить: $pkg", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun exitKiosk() {
        if (ctx is MainActivity) {
            try {
                ctx.stopLockTask()
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, MyDeviceAdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                    dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
                }
                Toast.makeText(ctx, "Лаунчер отключён.", Toast.LENGTH_LONG).show()
                ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun isDeviceOwner(): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(ctx.packageName)
    }

    @JavascriptInterface
    fun hasRoot(): Boolean = try {
        Runtime.getRuntime().exec("su -c id").waitFor() == 0
    } catch (_: Exception) { false }

    @JavascriptInterface
    fun runAsRoot(cmd: String): String = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).inputStream.bufferedReader().readText()
    } catch (_: Exception) { "" }

    @JavascriptInterface
    fun openSettings() {
        try {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }
}
